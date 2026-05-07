package dev.wallner.hermesonglass.phone.data.cxr

import com.google.gson.Gson
import com.rokid.cxr.Caps
import com.rokid.cxr.CXRServiceBridge
import dev.wallner.hermesonglass.phone.data.debug.DebugEventLog
import dev.wallner.hermesonglass.shared.CapsEnvelope
import dev.wallner.hermesonglass.shared.FrameParser
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * Phone-side production [CapsLink]. Mirrors the glasses-app's
 * `PhoneConnectionService`: subscribes to a single topic, decodes the
 * leading string Value into a typed [CapsEnvelope] via [FrameParser],
 * and sends outbound envelopes by encoding to JSON and writing into a
 * one-string [Caps] payload via `sendMessage`.
 *
 * Bulk media (audio chunks, image bytes) goes through [CXRServiceBridge.startAudioStream]
 * / `sendStream` instead and stays out of this control-plane channel.
 */
class CxrCapsLink(
    private val bridge: CXRServiceBridge = CXRServiceBridge(),
    private val topic: String = DEFAULT_TOPIC,
    private val gson: Gson = Gson(),
) : CapsLink {

    private val _inbound = MutableSharedFlow<CapsEnvelope>(extraBufferCapacity = 64)
    override val inbound: SharedFlow<CapsEnvelope> = _inbound.asSharedFlow()

    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()

    override fun start() {
        val rc = bridge.subscribe(topic, callback)
        if (rc != 0) Timber.w("CXRServiceBridge.subscribe('%s') returned %d", topic, rc)
        bridge.setStatusListener(statusListener)
    }

    override fun stop() {
        bridge.setStatusListener(null)
    }

    override fun send(envelope: CapsEnvelope): Boolean {
        val json = gson.toJson(envelope)
        val payload = json.toByteArray(Charsets.UTF_8)
        val decision = CapsFrameSizePolicy.decide(payload)
        if (decision is CapsFrameSizePolicy.Decision.Reject) {
            Timber.w("dropping caps envelope: %s", decision.reason)
            return false
        }
        DebugEventLog.record(DebugEventLog.Direction.OUT, DebugEventLog.Wire.CAPS, envelope.type, json)
        val caps = Caps()
        caps.write(json)
        val rc = bridge.sendMessage(topic, caps)
        if (rc != 0) Timber.w("CXRServiceBridge.sendMessage rc=%d", rc)
        return rc == 0
    }

    private val statusListener = object : CXRServiceBridge.StatusListener {
        override fun onConnected(deviceId: String?, name: String?, deviceType: Int) {
            _connected.value = true
        }
        override fun onConnecting(deviceId: String?, name: String?, deviceType: Int) = Unit
        override fun onDisconnected() { _connected.value = false }
        override fun onARTCStatus(quality: Float, isReady: Boolean) = Unit
        override fun onRokidAccountChanged(accountId: String?) = Unit
    }

    private val callback = object : CXRServiceBridge.MsgReplyCallback {
        override fun onReceive(
            name: String,
            msg: Caps,
            extra: ByteArray?,
            reply: CXRServiceBridge.Reply,
        ) {
            try {
                val json = if (msg.size() > 0) runCatching { msg.at(0)?.string }.getOrNull() else null
                if (json != null) {
                    val parsed = FrameParser.parseCapsEnvelope(json)
                    if (parsed != null) {
                        DebugEventLog.record(
                            DebugEventLog.Direction.IN, DebugEventLog.Wire.CAPS,
                            parsed.type, json,
                        )
                        _inbound.tryEmit(parsed)
                    } else {
                        Timber.d("dropping unrecognised caps payload on '%s'", name)
                    }
                } else {
                    Timber.d("caps payload on '%s' had no leading string", name)
                }
            } catch (e: Throwable) {
                Timber.w(e, "caps decode failed on '%s'", name)
            } finally {
                runCatching { reply.end(Caps()) }
            }
        }
    }

    companion object {
        const val DEFAULT_TOPIC: String = "hermes-on-glass"
    }
}
