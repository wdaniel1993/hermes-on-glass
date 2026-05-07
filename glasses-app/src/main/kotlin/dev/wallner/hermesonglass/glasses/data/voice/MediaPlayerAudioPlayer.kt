package dev.wallner.hermesonglass.glasses.data.voice

import android.content.Context
import android.media.MediaPlayer
import timber.log.Timber
import java.io.File
import java.util.UUID

/**
 * Production [AudioPlayer] backed by [MediaPlayer].
 *
 * The TTS path delivers the rendered Opus blob in one envelope, so we
 * write to a tmp file, point MediaPlayer at it, and start. Playing again
 * before the previous finishes stops the previous and starts the new one
 * (replace semantics — fits hold-to-talk turn-taking).
 */
class MediaPlayerAudioPlayer(
    private val context: Context,
) : AudioPlayer {

    private var player: MediaPlayer? = null
    private var pendingFile: File? = null

    @Synchronized
    override fun play(bytes: ByteArray, ext: String): Boolean {
        stop()
        val safeExt = ext.removePrefix(".").ifBlank { "ogg" }
        val file = File(context.cacheDir, "tts_${UUID.randomUUID().toString().take(8)}.$safeExt")
        return runCatching {
            file.writeBytes(bytes)
            val mp = MediaPlayer().apply {
                setOnCompletionListener { it.release(); file.delete() }
                setOnErrorListener { _, _, _ -> file.delete(); true }
                setDataSource(file.absolutePath)
                prepare()
                start()
            }
            player = mp
            pendingFile = file
            true
        }.onFailure {
            Timber.w(it, "MediaPlayer playback failed")
            file.delete()
        }.getOrDefault(false)
    }

    @Synchronized
    override fun stop() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        pendingFile?.delete()
        pendingFile = null
    }
}
