package dev.wallner.hermesonglass.glasses.data.camera

import dev.wallner.hermesonglass.glasses.data.PhoneLink
import dev.wallner.hermesonglass.shared.UserInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Base64
import java.util.UUID

/**
 * Orchestrates the photo path:
 *   - PHOTO menu activation calls [capturePhoto], which kicks off
 *     [CameraController.takePhoto].
 *   - The camera state flow surfaces [CameraState.Captured]; we downscale
 *     via [JpegDownscaler] and ship as a `UserInput { id, text="", imageBase64 }`
 *     envelope on [PhoneLink].
 *   - Errors land as [CameraState.Failed]; we expose [lastError] for the
 *     HUD to surface, then reset.
 *
 * Design D10 says "≤ 256 KB after downscale"; that lives in [JpegDownscaler.DEFAULT_MAX_BYTES].
 *
 * Pure orchestration logic — JVM-testable with fake [CameraController],
 * [JpegDownscaler], and [PhoneLink] implementations.
 */
class PhotoCaptureCoordinator(
    private val camera: CameraController,
    private val downscaler: JpegDownscaler,
    private val phoneLink: PhoneLink,
    private val maxBytes: Int = JpegDownscaler.DEFAULT_MAX_BYTES,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
    private val base64Encoder: (ByteArray) -> String = { Base64.getEncoder().encodeToString(it) },
) {
    @Volatile var lastError: String? = null
        private set

    private var collectorJob: Job? = null

    fun start() {
        if (collectorJob?.isActive == true) return
        camera.start()
        collectorJob = scope.launch {
            camera.state.collect { handleCameraState(it) }
        }
    }

    fun stop() {
        collectorJob?.cancel()
        collectorJob = null
        camera.stop()
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }

    /** Called when the user activates the PHOTO menu item on the HUD. */
    fun capturePhoto(): Boolean {
        lastError = null
        return camera.takePhoto()
    }

    private fun handleCameraState(state: CameraState) {
        when (state) {
            is CameraState.Captured -> {
                shipCapture(state.jpeg)
                camera.reset()
            }
            is CameraState.Failed -> {
                lastError = state.reason
                Timber.w("photo capture failed: %s", state.reason)
                camera.reset()
            }
            else -> Unit
        }
    }

    private fun shipCapture(rawJpeg: ByteArray) {
        val downscaled = runCatching { downscaler.downscale(rawJpeg, maxBytes) }
            .getOrElse {
                Timber.w(it, "downscaler threw; using raw bytes")
                rawJpeg
            }
        if (downscaled.size > maxBytes) {
            Timber.w("downscaled JPEG is still %d B (>%d B); sending anyway", downscaled.size, maxBytes)
        }
        val envelope = UserInput(
            id = UUID.randomUUID().toString().take(12),
            text = "",
            imageBase64 = base64Encoder(downscaled),
        )
        phoneLink.send(envelope)
    }
}
