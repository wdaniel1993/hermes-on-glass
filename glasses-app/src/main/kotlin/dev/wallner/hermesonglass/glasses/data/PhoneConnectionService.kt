package dev.wallner.hermesonglass.glasses.data

import com.google.gson.Gson
import com.rokid.cxr.Caps
import com.rokid.cxr.CXRServiceBridge
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
 * Bridges the CXR-S `CXRServiceBridge` topic onto a Kotlin flow of typed
 * [CapsEnvelope]s. The phone publishes JSON envelopes onto our subscribed
 * topic; we feed them through the shared parser and republish.
 *
 * Currently a stub: subscribes on [start], unsubscribes on [stop],
 * exposes [envelopes] for the UI layer to collect. Outbound `sendMessage`
 * stays as a placeholder — the real path lands in §11/§12 (sessions, wake)
 * once the matching protocol is wired up.
 */
class PhoneConnectionService(
    private val bridge: CXRServiceBridge = CXRServiceBridge(),
    private val topic: String = DEFAULT_TOPIC,
    private val gson: Gson = Gson(),
) : PhoneLink {
    private val _envelopes = MutableSharedFlow<CapsEnvelope>(extraBufferCapacity = 64)
    override val envelopes: SharedFlow<CapsEnvelope> = _envelopes.asSharedFlow()

    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()

    override fun start() {
        val rc = bridge.subscribe(topic, callback)
        if (rc != 0) {
            Timber.w("CXRServiceBridge.subscribe('%s') returned %d", topic, rc)
        }
        bridge.setStatusListener(statusListener)
    }

    override fun stop() {
        // The CXRServiceBridge API doesn't surface an unsubscribe entry —
        // we drop our local refs; the bridge GCs us on app shutdown.
        bridge.setStatusListener(null)
    }

    override fun send(envelope: CapsEnvelope): Boolean {
        val caps = Caps()
        caps.write(gson.toJson(envelope))
        val rc = bridge.sendMessage(topic, caps)
        if (rc != 0) {
            Timber.w("CXRServiceBridge.sendMessage rc=%d for %s", rc, envelope.type)
        }
        return rc == 0
    }

    private val statusListener = object : CXRServiceBridge.StatusListener {
        override fun onConnected(deviceId: String?, name: String?, deviceType: Int) {
            _connected.value = true
        }

        override fun onConnecting(deviceId: String?, name: String?, deviceType: Int) {
            // Treated as not-yet-connected; UI banner can interpret further.
        }

        override fun onDisconnected() {
            _connected.value = false
        }

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
                val json = readFirstStringValue(msg)
                if (json != null) {
                    val envelope = FrameParser.parseCapsEnvelope(json)
                    if (envelope != null) {
                        _envelopes.tryEmit(envelope)
                    } else {
                        Timber.d("dropping unrecognised caps payload on '%s'", name)
                    }
                } else {
                    Timber.d("caps payload on '%s' had no leading string", name)
                }
            } catch (e: Throwable) {
                Timber.w(e, "caps decode failed on '%s'", name)
            } finally {
                // Acknowledge so the phone's request/reply round-trip closes.
                runCatching { reply.end(Caps()) }
            }
        }
    }

    private fun readFirstStringValue(msg: Caps): String? {
        if (msg.size() <= 0) return null
        val v = msg.at(0) ?: return null
        return runCatching { v.string }.getOrNull()
    }

    companion object {
        const val DEFAULT_TOPIC: String = "hermes-on-glass"
    }
}
