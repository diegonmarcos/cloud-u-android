package app.sterna.core.data.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [autocryptHeaderValue], executed argument by argument — the whole decision of the volet.
 */
class AutocryptHeaderValueTest {

    private val key = "mDMEZmFrZQABCgB0aGlzIGlzIG5vdCBhIHJlYWwga2V5"

    private fun account(
        username: String = "alex@masto.top",
        pgpEnabled: Boolean = true,
        signKeyId: Long = 0x5944688C043ED86BL,
        publicKey: String = key,
    ) = StoredAccount(
        id = "acc1",
        server = "mail.example.test",
        username = username,
        pgpEnabled = pgpEnabled,
        pgpSignKeyId = signKeyId,
        pgpPublicKey = publicKey,
    )

    // --- the nominal case -------------------------------------------------------------------------

    @Test fun `an account with a key announces it for its own address`() {
        assertEquals(
            "addr=alex@masto.top; keydata=$key",
            autocryptHeaderValue(account(), "alex@masto.top"),
        )
    }

    @Test fun `the value states no encryption preference`() {
        // Autocrypt Level 1 §2.1.1: an absent `prefer-encrypt` is `nopreference`.
        assertFalse(autocryptHeaderValue(account(), "alex@masto.top")!!.contains("prefer-encrypt"))
    }

    // --- the three conditions, one at a time --------------------------------------------------------

    @Test fun `OpenPGP switched off announces nothing`() {
        // Switching OpenPGP off is consent withdrawn. It has to take effect on the very next send,
        // key or no key: the cached key is still there, and it stays unpublished.
        assertNull(autocryptHeaderValue(account(pgpEnabled = false), "alex@masto.top"))
    }

    @Test fun `no signing key means nothing to announce`() {
        assertNull(autocryptHeaderValue(account(signKeyId = 0L), "alex@masto.top"))
    }

    @Test fun `an empty cache means nothing to announce`() {
        assertNull(autocryptHeaderValue(account(publicKey = ""), "alex@masto.top"))
    }

    @Test fun `no account at all announces nothing`() {
        assertNull(autocryptHeaderValue(null, "alex@masto.top"))
    }

    // --- the guard the volet is built around ---------------------------------------------------------

    @Test fun `a delegated send announces nothing`() {
        // "On behalf": the message leaves with the shared mailbox's address in From while this
        // account's key covers only its own. Announcing it there would have the correspondent
        // encrypt their reply to a key the person reading that mailbox does not hold.
        assertNull(autocryptHeaderValue(account(), "shared@masto.top"))
    }

    @Test fun `an identity of the same user but another address announces nothing`() {
        assertNull(autocryptHeaderValue(account(), "alex.pro@masto.top"))
    }

    @Test fun `a blank From announces nothing`() {
        assertNull(autocryptHeaderValue(account(), ""))
        assertNull(autocryptHeaderValue(account(), null))
        assertNull(autocryptHeaderValue(account(), "   "))
    }

    @Test fun `an account with no address of its own announces nothing`() {
        assertNull(autocryptHeaderValue(account(username = ""), ""))
    }

    @Test fun `case and surrounding spaces do not make it a different mailbox`() {
        assertEquals(
            "addr=Alex@Masto.Top; keydata=$key",
            autocryptHeaderValue(account(), "  Alex@Masto.Top  "),
        )
    }

    // --- the sink -------------------------------------------------------------------------------------

    @Test fun `a CRLF in the address cannot split the header`() {
        val value = autocryptHeaderValue(account(username = "a@b\r\nBcc: victim@evil.com"), "a@b\r\nBcc: victim@evil.com")
        assertFalse(value!!.contains("\r"))
        assertFalse(value.contains("\n"))
    }

    @Test fun `the value is flat — folding is the wire format's business, not this rule's`() {
        val long = autocryptHeaderValue(account(publicKey = "k".repeat(4000)), "alex@masto.top")!!
        assertFalse("JMAP takes the value flat; SMTP folds it downstream", long.contains("\r\n"))
    }
}
