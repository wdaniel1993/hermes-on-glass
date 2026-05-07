package dev.wallner.hermesonglass.glasses

import android.app.Application
import dev.wallner.hermesonglass.glasses.data.CxrLBootstrap
import dev.wallner.hermesonglass.glasses.data.PhoneConnectionService
import dev.wallner.hermesonglass.glasses.data.PhoneLink
import dev.wallner.hermesonglass.glasses.data.camera.BitmapJpegDownscaler
import dev.wallner.hermesonglass.glasses.data.camera.CameraController
import dev.wallner.hermesonglass.glasses.data.camera.CxrLCameraController
import dev.wallner.hermesonglass.glasses.data.camera.JpegDownscaler
import dev.wallner.hermesonglass.glasses.data.camera.PhotoCaptureCoordinator
import dev.wallner.hermesonglass.glasses.data.voice.AudioPlayer
import dev.wallner.hermesonglass.glasses.data.voice.AudioRecorder
import dev.wallner.hermesonglass.glasses.data.voice.MediaPlayerAudioPlayer
import dev.wallner.hermesonglass.glasses.data.voice.MediaRecorderAudioRecorder
import dev.wallner.hermesonglass.glasses.data.voice.VoiceController
import dev.wallner.hermesonglass.glasses.debug.createEmulatorPhoneLinkOrNull
import dev.wallner.hermesonglass.glasses.domain.HudRepository
import dev.wallner.hermesonglass.shared.CapsSlashCommand
import dev.wallner.hermesonglass.shared.CapsSwitchSession
import timber.log.Timber

class GlassesApp : Application() {

    val cxrL: CxrLBootstrap by lazy { CxrLBootstrap() }
    val phoneLink: PhoneLink by lazy {
        // Debug + emulator: WebSocket client to the phone-app's
        // DebugCapsBridgeServer. Release / hardware: real CXR-S subscribe.
        createEmulatorPhoneLinkOrNull() ?: PhoneConnectionService()
    }
    val cameraController: CameraController by lazy { CxrLCameraController(this) }
    val jpegDownscaler: JpegDownscaler by lazy { BitmapJpegDownscaler() }
    val photoCoordinator: PhotoCaptureCoordinator by lazy {
        PhotoCaptureCoordinator(cameraController, jpegDownscaler, phoneLink)
    }
    val hudRepository: HudRepository by lazy {
        HudRepository(
            phone = phoneLink,
            onPhotoRequested = { photoCoordinator.capturePhoto() },
            onNewSessionRequested = { phoneLink.send(CapsSlashCommand("/new-session")) },
            onSessionPicked = { sessionKey -> phoneLink.send(CapsSwitchSession(sessionKey)) },
        )
    }
    val audioRecorder: AudioRecorder by lazy { MediaRecorderAudioRecorder(this) }
    val audioPlayer: AudioPlayer by lazy { MediaPlayerAudioPlayer(this) }
    val voiceController: VoiceController by lazy {
        VoiceController(audioRecorder, audioPlayer, phoneLink)
    }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        hudRepository.start()
        voiceController.start()
        photoCoordinator.start()
    }
}
