package dev.wallner.hermesonglass.glasses.data.voice

import dev.wallner.hermesonglass.glasses.data.PhoneLink
import dev.wallner.hermesonglass.glasses.ui.hud.HudIntent
import dev.wallner.hermesonglass.shared.CapsEnvelope
import dev.wallner.hermesonglass.shared.VoiceData
import dev.wallner.hermesonglass.shared.VoicePlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Base64
import java.util.UUID

/**
 * Wires the HUD's long-press intent to [AudioRecorder], emits captured
 * audio as a [VoiceData] envelope to the phone, and plays inbound
 * [VoicePlay] envelopes via [AudioPlayer].
 *
 * Exposes [onIntent] for the gesture layer to drive recording lifecycle:
 *   LongPressCenterDown — start AudioRecorder
 *   LongPressCenterUp   — stop AudioRecorder + send VoiceData
 *
 * The recorder may already be running when the user re-presses (rare) —
 * in that case the second start() is a no-op.
 */
class VoiceController(
    private val recorder: AudioRecorder,
    private val player: AudioPlayer,
    private val phoneLink: PhoneLink,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
    private val base64Encoder: (ByteArray) -> String = { Base64.getEncoder().encodeToString(it) },
    private val base64Decoder: (String) -> ByteArray = { Base64.getDecoder().decode(it) },
) {

    private var collectorJob: Job? = null

    fun start() {
        if (collectorJob?.isActive == true) return
        collectorJob = scope.launch {
            phoneLink.envelopes.collect { handleInbound(it) }
        }
    }

    fun stop() {
        collectorJob?.cancel()
        collectorJob = null
        runCatching { recorder.stop() }
        player.stop()
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }

    /** Returns true when the intent was consumed by the voice subsystem. */
    fun onIntent(intent: HudIntent): Boolean = when (intent) {
        HudIntent.LongPressCenterDown -> {
            recorder.start()
            true
        }
        HudIntent.LongPressCenterUp -> {
            val bytes = recorder.stop()
            if (bytes != null && bytes.isNotEmpty()) {
                val streamId = UUID.randomUUID().toString().take(8)
                phoneLink.send(VoiceData(
                    streamId = streamId,
                    bytesBase64 = base64Encoder(bytes),
                    ext = ".ogg",
                ))
            }
            true
        }
        else -> false
    }

    private fun handleInbound(envelope: CapsEnvelope) {
        if (envelope !is VoicePlay) return
        val bytes = runCatching { base64Decoder(envelope.bytesBase64) }
            .getOrElse {
                Timber.w(it, "decode VoicePlay payload failed")
                return
            }
        player.play(bytes, envelope.ext)
    }
}
