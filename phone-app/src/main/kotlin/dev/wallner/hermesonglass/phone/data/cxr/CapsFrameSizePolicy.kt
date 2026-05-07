package dev.wallner.hermesonglass.phone.data.cxr

import timber.log.Timber

/**
 * Pre-send size guard for control envelopes going over CXR Caps.
 *
 * Resolves OQ8 conservatively: until a hardware probe surfaces a real cap,
 * we send unchunked but warn loud whenever a payload crosses
 * [WARN_BYTES] so we notice during dev. Bulk media (audio/image) goes
 * through `sendStream`, never this path.
 *
 * If a real Caps frame-size limit shows up on Rokid hardware (the spec says
 * MTU is per cxr-service.json device-type), bump the policy to fragment at
 * `<limit> - <header overhead>` and emit a [Decision.Fragment] from [decide].
 */
object CapsFrameSizePolicy {

    /** Soft warning threshold while we have no hardware-confirmed limit. */
    const val WARN_BYTES: Int = 64 * 1024

    /** Hard ceiling — beyond this we refuse the send to surface bugs early. */
    const val HARD_REJECT_BYTES: Int = 1024 * 1024

    sealed interface Decision {
        data object Send : Decision
        data class Reject(val reason: String) : Decision
        // Reserved: data class Fragment(val chunkSize: Int) when the real limit is known.
    }

    fun decide(payload: ByteArray): Decision {
        val size = payload.size
        if (size > HARD_REJECT_BYTES) {
            return Decision.Reject(
                "caps payload $size B exceeds $HARD_REJECT_BYTES B; route bulk via sendStream",
            )
        }
        if (size > WARN_BYTES) {
            Timber.w("caps payload %d B over soft cap %d B; consider sendStream", size, WARN_BYTES)
        }
        return Decision.Send
    }
}
