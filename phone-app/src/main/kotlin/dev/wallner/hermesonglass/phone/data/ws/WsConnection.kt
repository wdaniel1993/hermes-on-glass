package dev.wallner.hermesonglass.phone.data.ws

import dev.wallner.hermesonglass.shared.WsFrame
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Public surface of [HermesWsClient] used by domain code. Lifted into an
 * interface so domain unit tests can pump frames through a fake without
 * spinning up a real WebSocket; the WS-client's own behavior is covered
 * separately by [HermesWsClientTest] against MockWebServer.
 */
interface WsConnection {
    val state: StateFlow<ConnectionState>
    val frames: SharedFlow<WsFrame>

    fun start()
    fun stop()
    fun shutdown()
    fun send(frame: WsFrame): Boolean
    fun setLastSessionKey(sessionKey: String?)
}
