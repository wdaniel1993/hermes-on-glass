package dev.wallner.hermesonglass.phone.data.cxr

import dev.wallner.hermesonglass.shared.CapsEnvelope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Phone-side surface of the Caps wire to the glasses HUD. Mirrors the
 * glasses-app's `PhoneLink` interface but viewed from the other end:
 * inbound is what the glasses send (user_input, voice_capture, ...);
 * outbound is what we push down (chat_stream, tool_progress, ...).
 *
 * Lifted into an interface so the [PhoneToGlassesBridge] is JVM-testable
 * without loading the native CXR libraries.
 */
interface CapsLink {
    val inbound: SharedFlow<CapsEnvelope>
    val connected: StateFlow<Boolean>

    fun start()
    fun stop()
    fun send(envelope: CapsEnvelope): Boolean
}
