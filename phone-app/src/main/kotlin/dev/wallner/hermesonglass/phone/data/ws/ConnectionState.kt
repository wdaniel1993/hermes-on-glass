package dev.wallner.hermesonglass.phone.data.ws

/**
 * Lifecycle of a [HermesWsClient]. Transitions:
 *
 *  Disconnected -> Connecting -> Connected -> Disconnected
 *  Connected -> Disconnecting -> Disconnected
 *  any -> Failed (on transport / handshake error; client backs off)
 *
 * The settings UI and chat UI both observe this state; the client is the
 * single source of truth.
 */
sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data object Connected : ConnectionState
    data object Disconnecting : ConnectionState
    data class Failed(val reason: String, val retryInMs: Long) : ConnectionState
}
