package dev.wallner.hermesonglass.phone

import android.app.Application
import dev.wallner.hermesonglass.phone.data.cxr.CapsLink
import dev.wallner.hermesonglass.phone.data.cxr.CxrCapsLink
import dev.wallner.hermesonglass.phone.debug.createEmulatorCapsLinkOrNull
import dev.wallner.hermesonglass.phone.data.prefs.HermesPrefs
import dev.wallner.hermesonglass.phone.data.rokid.EncryptedSnStore
import dev.wallner.hermesonglass.phone.data.rokid.GlassesConnectionManager
import dev.wallner.hermesonglass.phone.data.rokid.RokidSdkClient
import dev.wallner.hermesonglass.phone.data.rokid.RokidSdkManager
import dev.wallner.hermesonglass.phone.data.rokid.SnStore
import dev.wallner.hermesonglass.phone.data.ws.HermesWsClient
import dev.wallner.hermesonglass.phone.domain.ChatRepository
import dev.wallner.hermesonglass.phone.domain.PhoneToGlassesBridge
import dev.wallner.hermesonglass.phone.domain.WakeSignalManager
import timber.log.Timber
import java.util.UUID

/**
 * Application root. Lazily constructs the prefs store, the chat repository,
 * the Rokid CXR-M connection manager, and the WS<->Caps bridge.
 *
 * Reconfiguring (e.g. user edits the URL or shared secret) tears the WS
 * client down and constructs a new one via [rebuildRepository]. The Rokid
 * connection manager is independent of the WS — it survives WS rebuilds —
 * so we don't tear it down here.
 */
class HermesApp : Application() {

    val prefs: HermesPrefs by lazy { HermesPrefs(this) }
    val snStore: SnStore by lazy { EncryptedSnStore(this) }
    val rokidSdkClient: RokidSdkClient by lazy { RokidSdkManager(this) }
    val glassesConnection: GlassesConnectionManager by lazy {
        GlassesConnectionManager(
            sdk = rokidSdkClient,
            snStore = snStore,
            clientSecret = BuildConfig.ROKID_CLIENT_SECRET,
        )
    }
    val capsLink: CapsLink by lazy {
        // Debug + emulator variant: WebSocket bridge so emulator-only dev
        // loops work without a Bluetooth radio. Release builds always use
        // the real CXR-S link.
        createEmulatorCapsLinkOrNull() ?: CxrCapsLink()
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
        // If the user has already configured Hermes, kick the WS+bridge+wake
        // stack immediately so we're not waiting on the chat screen to be
        // touched. The getter triggers rebuildRepository() which starts each
        // component if prefs are present.
        if (prefs.hasSharedSecret() && prefs.hermesWsUrl.isNotBlank()) {
            repository
        }
    }

    /**
     * Construct (or reconstruct) the chat repository against the *current*
     * prefs values. Call after the user changes the URL or shared secret in
     * Settings; the old WS connection is dropped and a new one starts.
     * Also rebuilds the WS<->Caps bridge so it points at the new client.
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
