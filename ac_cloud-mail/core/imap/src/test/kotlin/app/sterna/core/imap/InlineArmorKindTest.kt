package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * EXECUTES [inlineArmorKind] — the confidentiality guard that keeps the reader's cache warm-up
 */
class InlineArmorKindTest {

    @Test
    fun `an encrypted armour header alone is inline PGP`() {
        assertEquals(CryptoKind.PGP_INLINE, inlineArmorKind("-----BEGIN PGP MESSAGE-----"))
    }

    @Test
    fun `a clearsigned armour header alone is inline PGP`() {
        assertEquals(CryptoKind.PGP_INLINE, inlineArmorKind("-----BEGIN PGP SIGNED MESSAGE-----"))
    }

    /**
     * The shape a real message has: the armour sits in the MIDDLE of a body, with a greeting
     */
    @Test
    fun `an encrypted armour buried in a body is inline PGP`() {
        val body = "Hi,\r\n\r\n-----BEGIN PGP MESSAGE-----\r\n\r\nhQEMA0ci\r\n" +
            "-----END PGP MESSAGE-----\r\n\r\n-- \r\nA."
        assertEquals(CryptoKind.PGP_INLINE, inlineArmorKind(body))
    }

    @Test
    fun `a clearsigned armour buried in a body is inline PGP`() {
        val body = "Hi,\r\n\r\n-----BEGIN PGP SIGNED MESSAGE-----\r\nHash: SHA256\r\n\r\nhello\r\n" +
            "-----BEGIN PGP SIGNATURE-----\r\n\r\nabc\r\n-----END PGP SIGNATURE-----\r\n"
        assertEquals(CryptoKind.PGP_INLINE, inlineArmorKind(body))
    }

    @Test
    fun `ordinary mail is not crypto`() {
        assertNull(inlineArmorKind("Hi, see you at 6. -- A."))
    }

    @Test
    fun `an empty body is not crypto`() {
        assertNull(inlineArmorKind(""))
    }

    /**
     * A pasted public key is NOT a crypto message: there is nothing to decrypt and nothing to
     */
    @Test
    fun `a quoted public key block is not crypto`() {
        val body = "Here is my key:\r\n-----BEGIN PGP PUBLIC KEY BLOCK-----\r\n\r\nmQENBF\r\n" +
            "-----END PGP PUBLIC KEY BLOCK-----\r\n"
        assertNull(inlineArmorKind(body))
    }

    /** A detached signature part quoted in text is not an inline signed message either. */
    @Test
    fun `a bare signature block is not inline PGP`() {
        assertNull(inlineArmorKind("-----BEGIN PGP SIGNATURE-----\r\n\r\nabc\r\n-----END PGP SIGNATURE-----"))
    }
}
