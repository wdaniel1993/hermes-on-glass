package dev.wallner.hermesonglass.glasses.debug

import com.google.gson.Gson
import dev.wallner.hermesonglass.glasses.data.PhoneLink
import dev.wallner.hermesonglass.shared.CapsEnvelope
import dev.wallner.hermesonglass.shared.FrameParser
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import timber.log.Timber
import java.net.URI

/**
 * Debug-only [PhoneLink] for emulator-only dev loops. Connects to the
 * phone-app's `DebugCapsBridgeServer` at `ws://10.0.2.2:8081` (the
 * Android emulator's host-loopback alias) and feeds parsed
 * [CapsEnvelope]s into [envelopes].
 *
 * Reconnects on failure with a fixed 2-second delay — small enough to be
 * snappy during dev, long enough not to spin pointlessly when the phone-app
 * isn't running.
 */
class DebugPhoneLinkClient(
    private val url: URI = URI("ws://10.0.2.2:$DEFAULT_PORT"),
    private val reconnectDelayMs: Long = 2_000,
    private val gson: Gson = Gson(),
) : PhoneLink {

    private val _envelopes = MutableSharedFlow<CapsEnvelope>(extraBufferCapacity = 64)
    override val envelopes: SharedFlow<CapsEnvelope> = _envelopes.asSharedFlow()

    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()

    @Volatile private var client: WebSocketClient? = null
    @Volatile private var stopped: Boolean = true

    override fun start() {
        if (!stopped) return
        stopped = false
        connectInternal()
    }

    override fun stop() {
        stopped = true
        runCatching { client?.close() }
        client = null
        _connected.value = false
    }

    override fun send(envelope: CapsEnvelope): Boolean {
        val live = client ?: return false
        if (!live.isOpen) return false
        return runCatching { live.send(gson.toJson(envelope)) }
            .onFailure { Timber.w(it, "[debug-phone-link] send failed") }
            .isSuccess
    }

    private fun connectInternal() {
        if (stopped) return
        client = object : WebSocketClient(url) {
            override fun onOpen(handshakedata: ServerHandshake) {
                _connected.value = true
                Timber.i("[debug-phone-link] connected to %s", url)
            }
            override fun onMessage(message: String) {
                FrameParser.parseCapsEnvelope(message)?.let { _envelopes.tryEmit(it) }
                    ?: Timber.d("[debug-phone-link] dropped unparseable inbound")
            }
            override fun onClose(code: Int, reason: String, remote: Boolean) {
                _connected.value = false
                Timber.i("[debug-phone-link] closed: %s", reason)
                if (!stopped) scheduleReconnect()
            }
            override fun onError(ex: Exception) {
                Timber.w(ex, "[debug-phone-link] error")
            }
        }.also { runCatching { it.connect() } }
    }

    private fun scheduleReconnect() {
        Thread {
            try { Thread.sleep(reconnectDelayMs) } catch (_: InterruptedException) { return@Thread }
            connectInternal()
        }.apply { isDaemon = true }.start()
    }

    companion object {
        const val DEFAULT_PORT: Int = 8081
    }
}

/**
 * Construct an emulator-mode [PhoneLink] in debug builds. The release
 * variant of this function returns `null`.
 */
fun createEmulatorPhoneLinkOrNull(): PhoneLink? =
    if (isAndroidEmulator()) DebugPhoneLinkClient() else null
