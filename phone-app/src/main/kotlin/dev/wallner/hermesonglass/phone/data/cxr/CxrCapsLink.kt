package dev.wallner.hermesonglass.phone.data.cxr

import com.google.gson.Gson
import com.rokid.cxr.Caps
import com.rokid.cxr.link.CXRLink
import com.rokid.cxr.link.callbacks.ICXRLinkCbk
import com.rokid.cxr.link.callbacks.ICustomCmdCbk
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
 * Phone-side production [CapsLink] over Rokid CXR-L (Hi Rokid AI app extension).
 *
 * Wire shape (D17 in `cxr-l-phone-migration/design.md`):
 *  - Phone → glasses: `cxrLink.sendCustomCmd(PHONE_TO_GLASSES_CHANNEL, caps.serialize())`
 *  - Glasses → phone: `setCXRCustomCmdCbk { onCustomCmdResult(key, payload) }`
 *    filtered to `key == GLASSES_TO_PHONE_CHANNEL`, decoded via `Caps.fromBytes(...)`.
 *
 * Connection state is `onCXRLConnected && onGlassBtConnected` — both the
 * CXR transport AND the BLE link to glasses (managed by Hi Rokid) must be up.
 *
 * The `CXRLink` itself is constructed once at Application scope so its
 * callbacks survive Activity destruction; this class wraps it and exposes
 * the platform-agnostic [CapsLink] surface.
 */
class CxrCapsLink(
    private val cxrLink: CXRLink,
    private val token: String,
    private val gson: Gson = Gson(),
) : CapsLink {

    private val _inbound = MutableSharedFlow<CapsEnvelope>(extraBufferCapacity = 64)
    override val inbound: SharedFlow<CapsEnvelope> = _inbound.asSharedFlow()

    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private var cxrUp: Boolean = false
    private var btUp: Boolean = false

    private val linkCallback = object : ICXRLinkCbk {
        override fun onCXRLConnected(connected: Boolean) {
            cxrUp = connected
            updateConnected()
        }
        override fun onGlassBtConnected(connected: Boolean) {
            btUp = connected
            updateConnected()
        }
        override fun onGlassAiAssistStart() = Unit
        override fun onGlassAiAssistStop() = Unit
    }

    private val cmdCallback = object : ICustomCmdCbk {
        override fun onCustomCmdResult(key: String?, payload: ByteArray?) {
            if (key != GLASSES_TO_PHONE_CHANNEL) return
            val caps = payload?.let { runCatching { Caps.fromBytes(it) }.getOrNull() } ?: return
            try {
                val json = if (caps.size() > 0) runCatching { caps.at(0)?.string }.getOrNull() else null
                if (json == null) {
                    Timber.d("caps payload on '%s' had no leading string", key)
                    return
                }
                val parsed = FrameParser.parseCapsEnvelope(json)
                if (parsed != null) {
                    DebugEventLog.record(
                        DebugEventLog.Direction.IN, DebugEventLog.Wire.CAPS,
                        parsed.type, json,
                    )
                    _inbound.tryEmit(parsed)
                } else {
                    Timber.d("dropping unrecognised caps payload on '%s'", key)
                }
            } catch (e: Throwable) {
                Timber.w(e, "caps decode failed on '%s'", key)
            }
        }
    }

    override fun start() {
        cxrLink.setCXRLinkCbk(linkCallback)
        cxrLink.setCXRCustomCmdCbk(cmdCallback)
        cxrLink.connect(token)
    }

    override fun stop() {
        // CXR-L exposes no documented disconnect hook; the link is held at
        // Application scope and outlives this wrapper. Drop our callbacks so
        // a re-`start()` re-installs them cleanly.
        cxrUp = false
        btUp = false
        updateConnected()
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
        return runCatching {
            cxrLink.sendCustomCmd(PHONE_TO_GLASSES_CHANNEL, caps.serialize())
            true
        }.getOrElse {
            Timber.w(it, "CXRLink.sendCustomCmd threw")
            false
        }
    }

    private fun updateConnected() {
        _connected.value = cxrUp && btUp
    }

    companion object {
        const val PHONE_TO_GLASSES_CHANNEL: String = "hermes-on-glass"
        const val GLASSES_TO_PHONE_CHANNEL: String = "hermes-on-glass-reply"
    }
}
