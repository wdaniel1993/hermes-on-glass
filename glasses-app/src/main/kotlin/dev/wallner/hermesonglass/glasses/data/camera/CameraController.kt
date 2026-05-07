package dev.wallner.hermesonglass.glasses.data.camera

import kotlinx.coroutines.flow.StateFlow

/**
 * Lifecycle of a single capture request issued via CXR-L `takePhoto`.
 *
 *   Idle       — no capture in flight
 *   Capturing  — `takePhoto(...)` was issued, awaiting onImageReceived
 *   Captured   — JPEG bytes ready (still raw, not yet downscaled)
 *   Failed     — onImageError or `takePhoto` returned false
 *
 * The bytes carried by [Captured] are the raw JPEG straight from the
 * Rokid camera service. Callers downscale via [JpegDownscaler] before
 * shipping over CXR / WS.
 */
sealed interface CameraState {
    data object Idle : CameraState
    data object Capturing : CameraState
    data class Captured(val jpeg: ByteArray) : CameraState {
        override fun equals(other: Any?): Boolean =
            other is Captured && jpeg.contentEquals(other.jpeg)
        override fun hashCode(): Int = jpeg.contentHashCode()
    }
    data class Failed(val reason: String) : CameraState
}

interface CameraController {
    val state: StateFlow<CameraState>

    /**
     * Issue a `takePhoto` request. Returns true if the request was
     * accepted by the SDK; false if a capture is already in flight or the
     * SDK refused (e.g. CXR-L not authorised).
     */
    fun takePhoto(): Boolean

    /** Bind the SDK callback. Idempotent. */
    fun start()

    /** Unbind the SDK callback. Pending capture state is preserved. */
    fun stop()

    /** Reset state to [CameraState.Idle] after a Captured/Failed has been consumed. */
    fun reset()
}
