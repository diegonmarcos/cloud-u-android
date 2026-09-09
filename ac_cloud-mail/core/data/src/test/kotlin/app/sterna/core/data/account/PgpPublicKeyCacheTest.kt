package app.sterna.core.data.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * EXECUTES the three decisions the public-key cache is made of. Expectations are written by hand —
 */
class PgpPublicKeyCacheTest {

    private fun account(
        id: String,
        keyId: Long = 0x1122334455667788L,
        publicKey: String = "",
    ) = StoredAccount(
        id = id,
        server = "mail.example.test",
        username = "$id@example.test",
        pgpEnabled = true,
        pgpSignKeyId = keyId,
        pgpPublicKey = publicKey,
    )

    // --- what gets stored ---------------------------------------------------------------------

    @Test fun `the provider's bytes are stored as unwrapped base64`() {
        // Hand-written: "hello" is aGVsbG8= in RFC 4648 base64.
        assertEquals("aGVsbG8=", pgpPublicKeyCacheValue("hello".toByteArray()))
    }

    @Test fun `a long key carries no line break, whatever its length`() {
        // The mutation this catches is `getMimeEncoder()`, which folds at 76 columns with CRLF.
        // It looks identical for a short key and breaks every real one — a real minimized key runs
        // to a few hundred bytes — and a folded value cannot go into a message header.
        val encoded = pgpPublicKeyCacheValue(ByteArray(600) { it.toByte() })
        assertEquals("the encoded key was wrapped: $encoded", -1, encoded.indexOf('\n'))
        assertEquals(-1, encoded.indexOf('\r'))
        assertEquals(800, encoded.length)
    }

    @Test fun `nothing from the provider is stored as nothing, not as an empty answer`() {
        assertEquals("", pgpPublicKeyCacheValue(null))
        assertEquals("", pgpPublicKeyCacheValue(ByteArray(0)))
    }

    // --- who gets caught up -------------------------------------------------------------------

    @Test fun `an account with a key and no cache is the one to fetch`() {
        assertEquals("a", pgpPublicKeyBackfill(account("a"), emptySet()))
    }

    @Test fun `an account that already has a cache is left alone`() {
        // Otherwise every composer opening is a round-trip to another app for something we hold.
        assertNull(pgpPublicKeyBackfill(account("a", publicKey = "aGVsbG8="), emptySet()))
    }

    @Test fun `an account with no signing key has nothing to ask for`() {
        assertNull(pgpPublicKeyBackfill(account("a", keyId = 0L), emptySet()))
    }

    @Test fun `no account at all is not a fetch`() {
        assertNull(pgpPublicKeyBackfill(null, emptySet()))
    }

    @Test fun `an account already tried in this composer is not tried again`() {
        // The composer re-arbitrates PGP on opening, on restore and on every From change. With no
        // provider installed the read fails every time, and without this it fails every time
        // AGAIN, behind a screen someone is typing on.
        assertNull(pgpPublicKeyBackfill(account("a"), setOf("a")))
        assertEquals("another account is still fetched", "b", pgpPublicKeyBackfill(account("b"), setOf("a")))
    }

    // --- where it is written ------------------------------------------------------------------

    @Test fun `the key lands on the named account only`() {
        val after = withCachedPgpPublicKey(
            listOf(account("a"), account("b")),
            id = "a",
            signKeyId = 0x1122334455667788L,
            publicKey = "aGVsbG8=",
        )
        assertEquals(listOf("aGVsbG8=", ""), after.map { it.pgpPublicKey })
    }

    @Test fun `a key id that moved under the read refuses the write`() {
        // The provider was thinking while the user chose another key (or switched OpenPGP app,
        // which empties every cache). Writing here files the OLD key's bytes under the NEW id, and
        // the account then announces a key it cannot sign with.
        val after = withCachedPgpPublicKey(
            listOf(account("a", keyId = 0x99L)),
            id = "a",
            signKeyId = 0x1122334455667788L,
            publicKey = "aGVsbG8=",
        )
        assertEquals("", after.single().pgpPublicKey)
    }

    @Test fun `an account with no key never receives a cache`() {
        val after = withCachedPgpPublicKey(
            listOf(account("a", keyId = 0L)),
            id = "a",
            signKeyId = 0L,
            publicKey = "aGVsbG8=",
        )
        assertEquals("", after.single().pgpPublicKey)
    }

    @Test fun `an unknown id changes nothing`() {
        val before = listOf(account("a"), account("b"))
        assertEquals(before, withCachedPgpPublicKey(before, "zz", 0x1122334455667788L, "aGVsbG8="))
    }

    @Test fun `nothing else about the account is rewritten`() {
        val before = account("a")
        assertEquals(
            listOf(before.copy(pgpPublicKey = "aGVsbG8=")),
            withCachedPgpPublicKey(listOf(before), "a", 0x1122334455667788L, "aGVsbG8="),
        )
    }
}
