package dev.wallner.hermesonglass.phone.data.rokid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RokidSnCipherTest {

    private val sn = "ROKID0123456789ABC"

    @Test fun `round trip with 16 byte secret`() {
        val secret = "1234-5678-90ab-cdef"  // 16 bytes after dash strip
        val encrypted = RokidSnCipher.encryptSn(sn, secret)
        assertNotEquals(sn, String(encrypted, Charsets.UTF_8))
        val decrypted = RokidSnCipher.decryptSn(encrypted, secret)
        assertEquals(sn, decrypted)
    }

    @Test fun `round trip with 32 byte secret`() {
        val secret = "1234-5678-90ab-cdef-1234-5678-90ab-cdef"  // 32 bytes after dash strip
        val encrypted = RokidSnCipher.encryptSn(sn, secret)
        val decrypted = RokidSnCipher.decryptSn(encrypted, secret)
        assertEquals(sn, decrypted)
    }

    @Test fun `same plaintext same key produces same ciphertext (deterministic IV from key)`() {
        // Per the contract: IV = first 16 bytes of (clientSecret minus dashes).
        // So encryption is deterministic for the same (sn, secret) pair —
        // useful for cache-key equality but reduces semantic security. The
        // test pins the contract so a future swap to random-IV mode breaks
        // here loudly rather than silently changing the cached bytes.
        val secret = "abcd-1234-EFGH-5678"
        val a = RokidSnCipher.encryptSn(sn, secret)
        val b = RokidSnCipher.encryptSn(sn, secret)
        assertEquals(a.toList(), b.toList())
    }

    @Test fun `wrong key fails to decrypt`() {
        val secret = "1234-5678-90ab-cdef"
        val wrong = "1234-5678-90ab-cd00"
        val encrypted = RokidSnCipher.encryptSn(sn, secret)
        assertThrows(javax.crypto.BadPaddingException::class.java) {
            RokidSnCipher.decryptSn(encrypted, wrong)
        }
    }

    @Test fun `non aes-sized secret rejected with descriptive error`() {
        // 7 bytes after dash strip — not a valid AES key length.
        val ex = assertThrows(IllegalArgumentException::class.java) {
            RokidSnCipher.encryptSn(sn, "abc-defg")
        }
        assert(ex.message!!.contains("rokid.clientSecret format")) {
            "expected helpful error message, got: ${ex.message}"
        }
    }
}
