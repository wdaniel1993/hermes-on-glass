package dev.wallner.hermesonglass.phone.data.cxr

import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import com.google.gson.Gson
import com.rokid.cxr.Caps
import com.rokid.cxr.link.CXRLink
import com.rokid.cxr.link.callbacks.ICXRLinkCbk
import com.rokid.cxr.link.callbacks.ICustomCmdCbk
import dev.wallner.hermesonglass.phone.data.debug.DebugEventLog
import dev.wallner.hermesonglass.phone.data.rokid.HiRokidPresence
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
    private val context: Context,
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
            Timber.i("CXRLink onCXRLConnected=%s", connected)
            cxrUp = connected
            updateConnected()
        }
        override fun onGlassBtConnected(connected: Boolean) {
            Timber.i("CXRLink onGlassBtConnected=%s", connected)
            btUp = connected
            updateConnected()
        }
        override fun onGlassAiAssistStart() {
            Timber.d("CXRLink onGlassAiAssistStart")
        }
        override fun onGlassAiAssistStop() {
            Timber.d("CXRLink onGlassAiAssistStop")
        }
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
        Timber.i("CxrCapsLink.start() — registering cbks and binding (token len=%d)", token.length)
        cxrLink.setCXRLinkCbk(linkCallback)
        cxrLink.setCXRCustomCmdCbk(cmdCallback)
        // Bypass the SDK's CXRLink.connect(token), which hardcodes
        // setPackage("com.rokid.sprite.aiapp") and misses the global Hi Rokid
        // build (com.rokid.sprite.global.aiapp). We bind to whichever package
        // is installed ourselves, passing the token as an Intent extra. The
        // SDK's internal ServiceConnection is reflected out so the bound
        // service still reaches the SDK's onServiceConnected logic. Pattern
        // verified against Anezium/RokidBrew which exercises the same SDK on
        // the same global Hi Rokid build.
        if (!bindHiRokidService()) {
            Timber.w("Manual bind failed; falling back to SDK connect()")
            runCatching { cxrLink.connect(token) }
                .onFailure { Timber.w(it, "CXRLink.connect threw") }
        }
    }

    private fun bindHiRokidService(): Boolean {
        val packageName = when {
            isPackageInstalled(HiRokidPresence.PACKAGE_GLOBAL) -> HiRokidPresence.PACKAGE_GLOBAL
            isPackageInstalled(HiRokidPresence.PACKAGE_CHINA) -> HiRokidPresence.PACKAGE_CHINA
            else -> {
                Timber.w("No Hi Rokid AI app installed")
                return false
            }
        }
        val sc = findServiceConnection(cxrLink) ?: run {
            Timber.w("Could not reflect CXRLink ServiceConnection field")
            return false
        }
        val intent = Intent(MEDIA_SERVICE_ACTION)
            .setPackage(packageName)
            .putExtra(AUTH_TOKEN_EXTRA, token)
        val bound = runCatching {
            context.bindService(intent, sc, Context.BIND_AUTO_CREATE)
        }.getOrElse {
            Timber.w(it, "manual bindService threw for %s", packageName)
            false
        }
        Timber.i("manual bindService(%s) -> %s", packageName, bound)
        return bound
    }

    private fun findServiceConnection(link: CXRLink): ServiceConnection? {
        var type: Class<*>? = link.javaClass
        while (type != null) {
            val field = type.declaredFields.firstOrNull {
                ServiceConnection::class.java.isAssignableFrom(it.type)
            }
            if (field != null) {
                field.isAccessible = true
                return field.get(link) as? ServiceConnection
            }
            type = type.superclass
        }
        return null
    }

    private fun isPackageInstalled(packageName: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrElse { false }

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
        private const val MEDIA_SERVICE_ACTION = "com.rokid.sprite.aiapp.externalapp.MEDIA_STREAM_SERVICE"
        private const val AUTH_TOKEN_EXTRA = "auth_token"
    }
}
