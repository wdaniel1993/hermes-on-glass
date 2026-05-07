package dev.wallner.hermesonglass.glasses.data

import dev.wallner.hermesonglass.shared.CapsEnvelope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Public surface of [PhoneConnectionService] used by domain code. Lifted
 * into an interface so HUD-domain tests can pump envelopes through a fake
 * without spinning up CXR-S (whose native methods can't load on a JVM).
 */
interface PhoneLink {
    val envelopes: SharedFlow<CapsEnvelope>
    val connected: StateFlow<Boolean>

    fun start()
    fun stop()

    /**
     * Send an envelope back to the phone bridge. Returns false when the
     * underlying transport rejects the message (disconnected, MTU exceeded,
     * etc.); callers decide whether to retry or surface the failure.
     */
    fun send(envelope: CapsEnvelope): Boolean
}
