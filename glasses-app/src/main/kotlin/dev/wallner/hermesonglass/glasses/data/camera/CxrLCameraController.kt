package dev.wallner.hermesonglass.glasses.data.camera

import android.content.Context
import com.rokid.cxr.link.CXRLink
import com.rokid.cxr.link.callbacks.IImageStreamCbk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * Production [CameraController] backed by CXR-L. Constructs a [CXRLink]
 * the first time [start] is called, registers an [IImageStreamCbk], and
 * dispatches `takePhoto(width, height, quality)` requests through it.
 *
 * [width]/[height] match the Rokid camera's documented output for HUD-grade
 * captures (`takePhoto(1920, 1080, 80)` per `cxr-l/api-reference.md`); the
 * downscaler pulls them down to the ≤256 KB wire budget afterwards.
 */
class CxrLCameraController(
    private val context: Context,
    private val width: Int = DEFAULT_WIDTH,
    private val height: Int = DEFAULT_HEIGHT,
    private val quality: Int = DEFAULT_QUALITY,
    private val linkFactory: (Context) -> CXRLink = { CXRLink(it) },
) : CameraController {

    private val _state = MutableStateFlow<CameraState>(CameraState.Idle)
    override val state: StateFlow<CameraState> = _state.asStateFlow()

    @Volatile private var link: CXRLink? = null

    private val imageCallback = object : IImageStreamCbk {
        override fun onImageReceived(bytes: ByteArray) {
            _state.value = CameraState.Captured(bytes)
        }
        override fun onImageError(code: Int, message: String) {
            Timber.w("CXR-L image error %d: %s", code, message)
            _state.value = CameraState.Failed("code=$code message=$message")
        }
    }

    override fun start() {
        if (link != null) return
        val l = linkFactory(context.applicationContext)
        l.setCXRImageCbk(imageCallback)
        link = l
    }

    override fun stop() {
        link?.setCXRImageCbk(null)
        link = null
    }

    override fun takePhoto(): Boolean {
        val l = link ?: run {
            _state.value = CameraState.Failed("camera not started")
            return false
        }
        if (_state.value is CameraState.Capturing) return false
        _state.value = CameraState.Capturing
        val ok = runCatching { l.takePhoto(width, height, quality) }
            .onFailure { Timber.w(it, "takePhoto threw") }
            .getOrDefault(false)
        if (!ok) _state.value = CameraState.Failed("takePhoto returned false")
        return ok
    }

    override fun reset() {
        if (_state.value !is CameraState.Capturing) {
            _state.value = CameraState.Idle
        }
    }

    companion object {
        const val DEFAULT_WIDTH: Int = 1920
        const val DEFAULT_HEIGHT: Int = 1080
        const val DEFAULT_QUALITY: Int = 80
    }
}
