package dev.wallner.hermesonglass.phone.data.rokid

/**
 * Persistence for the AES-encrypted SN blob and last-used device identity.
 * Production impl in [PrefsSnStore] uses EncryptedSharedPreferences; tests
 * use a tiny in-memory implementation.
 */
interface SnStore {
    fun putSnEncrypted(deviceAddress: String, sn: ByteArray, deviceName: String?)
    fun snEncrypted(deviceAddress: String): ByteArray?
    fun lastDeviceAddress(): String?
    fun lastDeviceName(): String?
    fun clear(deviceAddress: String)
}

class InMemorySnStore : SnStore {
    private data class Entry(val sn: ByteArray, val name: String?)

    private val byAddress = mutableMapOf<String, Entry>()
    private var last: String? = null

    override fun putSnEncrypted(deviceAddress: String, sn: ByteArray, deviceName: String?) {
        byAddress[deviceAddress] = Entry(sn, deviceName)
        last = deviceAddress
    }

    override fun snEncrypted(deviceAddress: String): ByteArray? = byAddress[deviceAddress]?.sn

    override fun lastDeviceAddress(): String? = last

    override fun lastDeviceName(): String? = last?.let { byAddress[it]?.name }

    override fun clear(deviceAddress: String) {
        byAddress.remove(deviceAddress)
        if (last == deviceAddress) last = null
    }
}
