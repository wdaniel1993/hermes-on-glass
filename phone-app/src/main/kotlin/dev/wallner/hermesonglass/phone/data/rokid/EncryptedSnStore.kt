package dev.wallner.hermesonglass.phone.data.rokid

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Persists the AES-encrypted SN blob to disk via EncryptedSharedPreferences.
 *
 * One row per device address; the SN blob (already AES-CBC-encrypted with
 * `rokid.clientSecret`) is base64'd before storage so SharedPreferences'
 * String value type is happy. The "last device" row records which address
 * the user paired with most recently — used by [GlassesConnectionManager]'s
 * reconnect path on cold start.
 */
class EncryptedSnStore(context: Context) : SnStore {

    private val prefs = EncryptedSharedPreferences.create(
        context.applicationContext,
        FILE,
        MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override fun putSnEncrypted(deviceAddress: String, sn: ByteArray, deviceName: String?) {
        prefs.edit()
            .putString(snKey(deviceAddress), Base64.encodeToString(sn, Base64.NO_WRAP))
            .apply {
                if (deviceName != null) putString(nameKey(deviceAddress), deviceName)
            }
            .putString(LAST_KEY, deviceAddress)
            .apply()
    }

    override fun snEncrypted(deviceAddress: String): ByteArray? =
        prefs.getString(snKey(deviceAddress), null)
            ?.let { Base64.decode(it, Base64.NO_WRAP) }

    override fun lastDeviceAddress(): String? = prefs.getString(LAST_KEY, null)

    override fun lastDeviceName(): String? =
        lastDeviceAddress()?.let { prefs.getString(nameKey(it), null) }

    override fun clear(deviceAddress: String) {
        prefs.edit()
            .remove(snKey(deviceAddress))
            .remove(nameKey(deviceAddress))
            .apply {
                if (lastDeviceAddress() == deviceAddress) remove(LAST_KEY)
            }
            .apply()
    }

    companion object {
        private const val FILE = "rokid_sn"
        private const val LAST_KEY = "last_device"
        private fun snKey(addr: String) = "sn:$addr"
        private fun nameKey(addr: String) = "name:$addr"
    }
}
