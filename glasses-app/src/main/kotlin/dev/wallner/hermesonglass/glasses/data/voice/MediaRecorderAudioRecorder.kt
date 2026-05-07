package dev.wallner.hermesonglass.glasses.data.voice

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import timber.log.Timber
import java.io.File
import java.util.UUID

/**
 * Production [AudioRecorder] backed by [MediaRecorder].
 *
 * Records Opus in OGG container from `AudioSource.MIC`. Opus encoding +
 * OGG output requires API 29+; the Rokid AOSP target is API 28-32 per
 * design, so on a stock Rokid image we degrade to AAC/M4A (which Hermes
 * also accepts via the cache_audio path).
 *
 * On `start()` we open a tmp file for the recorder; on `stop()` we read
 * it back into memory and delete it. For hold-to-talk durations
 * (~1-10 s, < 100 KB Opus) the round-trip-through-disk cost is trivial.
 */
class MediaRecorderAudioRecorder(
    private val context: Context,
    private val sampleRate: Int = 16_000,
) : AudioRecorder {

    @Volatile override var isRecording: Boolean = false
        private set

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    override fun start(): Boolean {
        if (isRecording) return true
        val file = File(context.cacheDir, "voice_${UUID.randomUUID().toString().take(8)}.${chosenExt()}")
        val rec = newRecorder(context).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            if (canUseOpus()) {
                setOutputFormat(MediaRecorder.OutputFormat.OGG)
                setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            } else {
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            }
            setAudioSamplingRate(sampleRate)
            setAudioChannels(1)
            setOutputFile(file.absolutePath)
        }
        return runCatching {
            rec.prepare()
            rec.start()
            recorder = rec
            outputFile = file
            isRecording = true
            true
        }.onFailure {
            Timber.w(it, "MediaRecorder start failed")
            runCatching { rec.release() }
            file.delete()
        }.getOrDefault(false)
    }

    override fun stop(): ByteArray? {
        val rec = recorder ?: return null
        val file = outputFile
        recorder = null
        outputFile = null
        isRecording = false
        return runCatching {
            rec.stop()
            rec.release()
            file?.takeIf { it.exists() }?.readBytes().also { file?.delete() }
        }.onFailure {
            Timber.w(it, "MediaRecorder stop failed")
            runCatching { rec.release() }
            file?.delete()
        }.getOrNull()
    }

    private fun chosenExt(): String = if (canUseOpus()) "ogg" else "m4a"

    private fun canUseOpus(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    @Suppress("DEPRECATION")
    private fun newRecorder(context: Context): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }
}
