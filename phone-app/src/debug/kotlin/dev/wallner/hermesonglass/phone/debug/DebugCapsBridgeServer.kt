package dev.wallner.hermesonglass.phone.debug

import com.google.gson.Gson
import dev.wallner.hermesonglass.phone.data.cxr.CapsLink
import dev.wallner.hermesonglass.shared.CapsEnvelope
import dev.wallner.hermesonglass.shared.FrameParser
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import timber.log.Timber
import java.net.InetSocketAddress

/**
 * Debug-only [CapsLink] for emulator-only dev loops. Hosts a tiny
 * WebSocket server on 0.0.0.0:[port]; the glasses-app debug build runs a
 * matching client (see glasses-app's `EmulatorPhoneLink`).
 *
 * Wire format mirrors the production CXR Caps payload: each WS text frame
 * is a JSON-encoded [CapsEnvelope]. Bulk media (audio chunks, image bytes)
 * goes over the same channel for now — there's no `sendStream` analogue;
 * the [CapsFrameSizePolicy] hard-reject ceiling protects against runaway
 * payloads.
 *
 * Only ships in debug builds (lives in src/debug). The release variant
 * resolves [createEmulatorCapsLinkOrNull] to `null` and uses
 * [dev.wallner.hermesonglass.phone.data.cxr.CxrCapsLink] instead.
 */
class DebugCapsBridgeServer(
    private val port: Int = DEFAULT_PORT,
    private val gson: Gson = Gson(),
) : CapsLink {

    private val _inbound = MutableSharedFlow<CapsEnvelope>(extraBufferCapacity = 64)
    override val inbound: SharedFlow<CapsEnvelope> = _inbound.asSharedFlow()

    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()

    @Volatile private var liveSocket: WebSocket? = null
    @Volatile private var server: WebSocketServer? = null

    override fun start() {
        if (server != null) return
        server = object : WebSocketServer(InetSocketAddress(port)) {
            override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
                liveSocket = conn
                _connected.value = true
                Timber.i("[debug-bridge] glasses connected from %s", conn.remoteSocketAddress)
            }
            override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
                if (liveSocket === conn) {
                    liveSocket = null
                    _connected.value = false
                }
                Timber.i("[debug-bridge] glasses disconnected: code=%d", code)
            }
            override fun onMessage(conn: WebSocket, message: String) {
                FrameParser.parseCapsEnvelope(message)?.let { _inbound.tryEmit(it) }
                    ?: Timber.d("[debug-bridge] dropped unparseable inbound frame")
            }
            override fun onError(conn: WebSocket?, ex: Exception) {
                Timber.w(ex, "[debug-bridge] socket error")
            }
            override fun onStart() {
                Timber.i("[debug-bridge] WS server listening on :%d", port)
                isReuseAddr = true
            }
        }.also { it.start() }
    }

    override fun stop() {
        runCatching { server?.stop(STOP_TIMEOUT_MS) }
        liveSocket = null
        _connected.value = false
        server = null
    }

    override fun send(envelope: CapsEnvelope): Boolean {
        val socket = liveSocket ?: return false
        return runCatching { socket.send(gson.toJson(envelope)) }
            .onFailure { Timber.w(it, "[debug-bridge] send failed") }
            .isSuccess
    }

    companion object {
        const val DEFAULT_PORT: Int = 8081
        private const val STOP_TIMEOUT_MS: Int = 1000
    }
}

/**
 * Construct an emulator-mode [CapsLink] in debug builds. The release
 * variant of this file returns `null` (see src/release/.../EmulatorBridge.kt).
 */
fun createEmulatorCapsLinkOrNull(): CapsLink? =
    if (isAndroidEmulator()) DebugCapsBridgeServer() else null
