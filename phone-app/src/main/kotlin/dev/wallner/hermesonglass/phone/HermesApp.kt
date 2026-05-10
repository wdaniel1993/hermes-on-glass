package dev.wallner.hermesonglass.phone

import android.app.Application
import com.rokid.cxr.link.CXRLink
import com.rokid.cxr.link.utils.CxrDefs
import dev.wallner.hermesonglass.phone.data.cxr.CapsLink
import dev.wallner.hermesonglass.phone.data.cxr.CxrCapsLink
import dev.wallner.hermesonglass.phone.data.prefs.HermesPrefs
import dev.wallner.hermesonglass.phone.data.rokid.ApkSideloader
import dev.wallner.hermesonglass.phone.data.rokid.CxrApkSideloader
import dev.wallner.hermesonglass.phone.data.ws.HermesWsClient
import dev.wallner.hermesonglass.phone.debug.createEmulatorCapsLinkOrNull
import dev.wallner.hermesonglass.phone.domain.ChatRepository
import dev.wallner.hermesonglass.phone.domain.PhoneToGlassesBridge
import dev.wallner.hermesonglass.phone.domain.WakeSignalManager
import timber.log.Timber
import java.util.UUID

/**
 * Application root. Lazily constructs the prefs store, the CXR-L session, the
 * chat repository, the apk sideloader, and the WS↔Caps bridge.
 *
 * The CXR-L session ([CXRLink]) is App-scoped per the sample's
 * `CXRLSampleApplication.sharedCxrLink` pattern so its callbacks survive
 * Activity destruction.
 *
 * Reconfiguring (e.g. user edits the URL or shared secret) tears the WS
 * client down and constructs a new one via [rebuildRepository]. The CXR-L
 * link is independent of the WS — it survives WS rebuilds — so we don't tear
 * it down here.
 */
class HermesApp : Application() {

    val prefs: HermesPrefs by lazy { HermesPrefs(this) }

    private val cxrLock = Any()
    @Volatile private var _cxrLink: CXRLink? = null
    @Volatile private var _capsLink: CapsLink? = null
    @Volatile private var _apkSideloader: ApkSideloader? = null
    @Volatile private var _emulatorCapsLink: CapsLink? = null

    /**
     * App-scoped CXR-L session. `null` until the user has completed Hi Rokid
     * authorization (we need the token before constructing the link). Once
     * built, it survives Activity destruction so callbacks keep firing while
     * the phone screen is off.
     */
    val cxrLink: CXRLink?
        get() {
            if (!prefs.hasRokidAuthToken()) return null
            return _cxrLink ?: synchronized(cxrLock) {
                _cxrLink ?: CXRLink(this).apply {
                    configCXRSession(
                        CxrDefs.CXRSession(
                            CxrDefs.CXRSessionType.CUSTOMAPP,
                            CxrApkSideloader.GLASSES_PACKAGE_NAME,
                        ),
                    )
                }.also { _cxrLink = it }
            }
        }

    /**
     * Phone↔glasses Caps wire. Falls back to a debug WebSocket bridge on
     * emulators (no Bluetooth radio); becomes [NullCapsLink] only while no
     * token is persisted.
     *
     * Re-evaluated on every access until a real impl is cached, so authorizing
     * Hi Rokid mid-session promotes [NullCapsLink] to [CxrCapsLink] without
     * needing to restart the process.
     */
    val capsLink: CapsLink
        get() {
            val emulator = _emulatorCapsLink
                ?: createEmulatorCapsLinkOrNull()?.also { _emulatorCapsLink = it }
            if (emulator != null) return emulator
            val cached = _capsLink
            if (cached != null && cached !== NullCapsLink) return cached
            return synchronized(cxrLock) {
                val again = _capsLink
                if (again != null && again !== NullCapsLink) return@synchronized again
                val link = cxrLink?.let { CxrCapsLink(this, it, prefs.rokidAuthToken) } ?: NullCapsLink
                _capsLink = link
                link
            }
        }

    /**
     * Bundled glasses-APK sideloader. `null` until Hi Rokid auth completes.
     */
    val apkSideloader: ApkSideloader?
        get() {
            val cached = _apkSideloader
            if (cached != null) return cached
            return synchronized(cxrLock) {
                _apkSideloader ?: cxrLink?.let { CxrApkSideloader(this, it) }
                    ?.also { _apkSideloader = it }
            }
        }

    @Volatile private var _repository: ChatRepository? = null
    @Volatile private var _bridge: PhoneToGlassesBridge? = null
    @Volatile private var _wakeManager: WakeSignalManager? = null

    val repository: ChatRepository
        get() = _repository ?: synchronized(this) { _repository ?: rebuildRepository() }

    val phoneToGlassesBridge: PhoneToGlassesBridge
        get() = _bridge ?: synchronized(this) { _bridge ?: rebuildBridge() }

    val wakeSignalManager: WakeSignalManager
        get() = _wakeManager ?: synchronized(this) { _wakeManager ?: rebuildBridge().let { _wakeManager!! } }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        if (prefs.deviceId.isEmpty()) {
            prefs.deviceId = "phone-" + UUID.randomUUID().toString().take(8)
        }
        if (prefs.hasSharedSecret() && prefs.hermesWsUrl.isNotBlank()) {
            repository
        }
    }

    /**
     * Construct (or reconstruct) the chat repository against the *current*
     * prefs values. Call after the user changes the URL or shared secret in
     * Settings; the old WS connection is dropped and a new one starts.
     * Also rebuilds the WS↔Caps bridge so it points at the new client.
     */
    @Synchronized
    fun rebuildRepository(): ChatRepository {
        _repository?.shutdown()
        _bridge?.shutdown()
        _wakeManager?.shutdown()
        val client = HermesWsClient(
            url = prefs.hermesWsUrl,
            sharedSecret = prefs.sharedSecret,
            deviceId = prefs.deviceId,
        )
        val repo = ChatRepository(client)
        _repository = repo
        _bridge = PhoneToGlassesBridge(client, capsLink)
        _wakeManager = WakeSignalManager(client, capsLink)
        if (prefs.hasSharedSecret() && prefs.hermesWsUrl.isNotBlank()) {
            repo.start()
            _bridge!!.start()
            _wakeManager!!.start()
        }
        return repo
    }

    @Synchronized
    private fun rebuildBridge(): PhoneToGlassesBridge {
        // Delegates to rebuildRepository which constructs both atomically.
        rebuildRepository()
        return _bridge!!
    }
}

/**
 * Stand-in [CapsLink] used when no auth token is persisted yet — the
 * connection bits exist on screen but cannot dial out. Once the user
 * completes Hi Rokid authorization, [HermesApp.capsLink] gets re-resolved.
 */
private object NullCapsLink : CapsLink {
    override val inbound = kotlinx.coroutines.flow.MutableSharedFlow<dev.wallner.hermesonglass.shared.CapsEnvelope>()
    override val connected = kotlinx.coroutines.flow.MutableStateFlow(false)
    override fun start() = Unit
    override fun stop() = Unit
    override fun send(envelope: dev.wallner.hermesonglass.shared.CapsEnvelope): Boolean = false
}
