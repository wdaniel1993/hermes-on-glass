package dev.wallner.hermesonglass.phone.data.rokid

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES/CBC/PKCS5Padding for the Rokid SN envelope expected by
 * `CxrApi.connectBluetooth(...)` on reconnect (see design D7).
 *
 * Contract documented by the Rokid SDK:
 *   key = bytes of clientSecret with dashes stripped
 *   iv  = first 16 bytes of the same byte array
 *   in/out = AES/CBC/PKCS5Padding
 *
 * Threat model: this CBC layer exists ONLY because the SDK requires that
 * exact envelope on the wire — not as our at-rest secrecy mechanism. The
 * resulting blob is wrapped a second time by [EncryptedSnStore] (AES-GCM
 * via EncryptedSharedPreferences) before it lands on disk, so the static-
 * IV-from-key property of this layer (same plaintext+key → identical
 * ciphertext) is harmless in our storage path. Do not rely on this class
 * alone for confidentiality.
 *
 * Pure JVM — testable without an Android emulator.
 */
object RokidSnCipher {

    private const val TRANSFORM = "AES/CBC/PKCS5Padding"

    fun encryptSn(sn: String, clientSecret: String): ByteArray {
        val keyBytes = deriveKeyBytes(clientSecret)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(deriveIv(keyBytes)))
        return cipher.doFinal(sn.toByteArray(Charsets.UTF_8))
    }

    fun decryptSn(snEncrypted: ByteArray, clientSecret: String): String {
        val keyBytes = deriveKeyBytes(clientSecret)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(deriveIv(keyBytes)))
        return String(cipher.doFinal(snEncrypted), Charsets.UTF_8)
    }

    private fun deriveKeyBytes(clientSecret: String): ByteArray {
        val raw = clientSecret.replace("-", "").toByteArray(Charsets.UTF_8)
        require(raw.size in setOf(16, 24, 32)) {
            "AES key must be 128/192/256 bits (got ${raw.size * 8}); check rokid.clientSecret format"
        }
        return raw
    }

    private fun deriveIv(keyBytes: ByteArray): ByteArray {
        require(keyBytes.size >= 16) { "key bytes must be >= 16 to derive IV" }
        return keyBytes.copyOf(16)
    }
}
