package dev.wallner.hermesonglass.phone.data.ws

import com.google.gson.Gson
import dev.wallner.hermesonglass.phone.data.debug.DebugEventLog
import dev.wallner.hermesonglass.shared.ClientHello
import dev.wallner.hermesonglass.shared.FrameParser
import dev.wallner.hermesonglass.shared.PROTOCOL_VERSION
import dev.wallner.hermesonglass.shared.WsFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Outbound WebSocket client to the Hermes channel adapter. One instance per
 * configured URL+secret pair; the app reconfigures by tearing the old client
 * down and constructing a new one.
 *
 * Public surface:
 *  - [state]: [ConnectionState] flow for UI / status overlays.
 *  - [frames]: inbound [WsFrame] flow (caller filters by type).
 *  - [start] / [stop]: lifecycle. [start] is idempotent.
 *  - [send]: serialise + send a [WsFrame]; returns false if not connected.
 *
 * Reconnect policy: exponential backoff with full jitter, base 1s, cap 30s.
 * Stops backing off only when [stop] is called.
 */
class HermesWsClient(
    private val url: String,
    private val sharedSecret: String,
    private val deviceId: String? = null,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val gson: Gson = Gson(),
    private val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val initialBackoffMs: Long = INITIAL_BACKOFF_MS,
    private val maxBackoffMs: Long = MAX_BACKOFF_MS,
    private val random: Random = Random.Default,
) : WsConnection {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _frames = MutableSharedFlow<WsFrame>(extraBufferCapacity = 64)
    override val frames: SharedFlow<WsFrame> = _frames.asSharedFlow()

    private var supervisorJob: Job? = null
    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var attempt: Int = 0
    @Volatile private var manualStop: Boolean = false
    @Volatile private var lastSessionKey: String? = null

    override fun start() {
        if (supervisorJob?.isActive == true) return
        manualStop = false
        attempt = 0
        supervisorJob = coroutineScope.launch { runConnectLoop() }
    }

    override fun stop() {
        manualStop = true
        _state.value = ConnectionState.Disconnecting
        webSocket?.close(NORMAL_CLOSURE, "client stop")
        webSocket = null
        supervisorJob?.cancel()
        supervisorJob = null
        _state.value = ConnectionState.Disconnected
    }

    override fun send(frame: WsFrame): Boolean {
        val ws = webSocket ?: return false
        if (_state.value != ConnectionState.Connected) return false
        val json = gson.toJson(frame)
        DebugEventLog.record(DebugEventLog.Direction.OUT, DebugEventLog.Wire.WS, frame.type, json)
        return ws.send(json)
    }

    /** For testing — closes the underlying scope. The client cannot be restarted afterwards. */
    override fun shutdown() {
        stop()
        coroutineScope.cancel()
    }

    override fun setLastSessionKey(sessionKey: String?) {
        lastSessionKey = sessionKey
    }

    private suspend fun runConnectLoop() {
        while (!manualStop) {
            attemptConnect()
            if (manualStop) break

            val delayMs = backoffDelayMs(attempt)
            _state.value = ConnectionState.Failed(
                reason = (_state.value as? ConnectionState.Failed)?.reason ?: "disconnected",
                retryInMs = delayMs,
            )
            attempt += 1
            delay(delayMs)
        }
    }

    private suspend fun attemptConnect() {
        _state.value = ConnectionState.Connecting
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $sharedSecret")
            .build()
        val signal = kotlinx.coroutines.CompletableDeferred<Unit>()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                this@HermesWsClient.webSocket = webSocket
                _state.value = ConnectionState.Connected
                attempt = 0
                val hello = ClientHello(
                    protocolVersion = PROTOCOL_VERSION,
                    deviceId = deviceId,
                    currentSessionKey = lastSessionKey,
                )
                webSocket.send(gson.toJson(hello))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = FrameParser.parseWsFrame(text) ?: return
                DebugEventLog.record(DebugEventLog.Direction.IN, DebugEventLog.Wire.WS, frame.type, text)
                _frames.tryEmit(frame)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val text = bytes.utf8()
                val frame = FrameParser.parseWsFrame(text) ?: return
                DebugEventLog.record(DebugEventLog.Direction.IN, DebugEventLog.Wire.WS, frame.type, text)
                _frames.tryEmit(frame)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                this@HermesWsClient.webSocket = null
                val httpStatus = response?.code
                val reason = if (httpStatus != null) "http $httpStatus" else (t.message ?: t.javaClass.simpleName)
                _state.value = ConnectionState.Failed(reason, retryInMs = 0)
                signal.complete(Unit)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // OkHttp's WS contract: respond with NORMAL_CLOSURE (1000)
                // regardless of the peer's code. Echoing a non-1000 code
                // some servers treat as a protocol error and close hard.
                webSocket.close(NORMAL_CLOSURE, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                this@HermesWsClient.webSocket = null
                if (manualStop) {
                    _state.value = ConnectionState.Disconnected
                } else {
                    _state.value = ConnectionState.Failed("closed: $reason", retryInMs = 0)
                }
                signal.complete(Unit)
            }
        }

        httpClient.newWebSocket(request, listener)

        // Suspend until onClosed/onFailure fires. OkHttp's WebSocket has no
        // built-in await semantics; the CompletableDeferred bridges the
        // listener callbacks back into structured concurrency.
        signal.await()
    }

    private fun backoffDelayMs(attempt: Int): Long {
        val cap = maxBackoffMs.toDouble()
        val scaled = (initialBackoffMs.toDouble() * Math.pow(2.0, attempt.toDouble()))
            .coerceAtMost(cap)
            .toLong()
        // Full jitter — pick uniformly in [0, scaled]. AWS-style; smooths reconnect storms.
        return random.nextLong(scaled.coerceAtLeast(1L) + 1)
    }

    companion object {
        const val NORMAL_CLOSURE: Int = 1000
        const val INITIAL_BACKOFF_MS: Long = 1_000
        const val MAX_BACKOFF_MS: Long = 30_000

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .build()
    }
}
