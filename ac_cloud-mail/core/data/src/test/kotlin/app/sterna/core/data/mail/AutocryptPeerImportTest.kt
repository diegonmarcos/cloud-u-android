package app.sterna.core.data.mail

import android.content.Intent
import app.sterna.core.data.pgp.PgpDecrypted
import app.sterna.core.data.pgp.PgpEngine
import app.sterna.core.data.pgp.PgpResult
import app.sterna.core.data.pgp.PgpSignature
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The receiving half of Autocrypt, EXECUTED: [autocryptPeerImportOf] decides, [importAutocryptPeer]
 */
class AutocryptPeerImportTest {

    private val received = "2026-09-02T10:15:00Z"
    private val receivedMillis = 1_788_344_100_000L

    // A FIXED literal, never `System.currentTimeMillis()`: the clock this file runs on decides
    // nothing, so a case cannot drift into or out of the tolerance with the day it is replayed.
    // 2026-09-03T01:46:40Z — some fifteen hours after [received], so the ordinary cases below are
    // all in the past and the tolerance has no say in them.
    private val now = 1_788_400_000_000L
    private val keyBytes: ByteArray = Base64.getDecoder().decode(AutocryptTestKey.RSA_4096)

    private fun header(value: String) = listOf("From" to "Alice <alice@example.test>", "Autocrypt" to value)

    private fun decide(headers: List<Pair<String, String>>, from: String? = "alice@example.test") =
        autocryptPeerImportOf(headers, from, received, now)

    @Test fun `a well-formed header yields the peer, its key bytes and the message's date`() {
        val decided = decide(header("addr=alice@example.test; keydata=${AutocryptTestKey.RSA_4096}"))
            ?: error("the header was refused")
        assertEquals("alice@example.test", decided.peerId)
        assertArrayEquals(keyBytes, decided.keyData)
        assertEquals(receivedMillis, decided.effectiveDateMillis)
    }

    @Test fun `a keydata folded the way IMAP unfolds it gives the same bytes`() {
        // What `MimeParser.rawHeaders` hands over: every continuation line joined with ONE space,
        // so the base64 comes back with spaces INSIDE it.
        val folded = AutocryptTestKey.RSA_4096.chunked(76).joinToString(" ")
        assertTrue("the fixture must actually be broken up", folded.contains(' '))
        val decided = decide(header("addr=alice@example.test; keydata=$folded")) ?: error("refused")
        assertArrayEquals(keyBytes, decided.keyData)
    }

    @Test fun `a keydata folded the way JMAP returns it gives the same bytes`() {
        // RFC 8621's `headers` property answers the raw field value: the CRLF + WSP of the sender's
        // folding are still in it.
        val folded = AutocryptTestKey.RSA_4096.chunked(76).joinToString("\r\n ")
        val decided = decide(header("addr=alice@example.test;\r\n keydata=$folded")) ?: error("refused")
        assertArrayEquals(keyBytes, decided.keyData)
        assertEquals("alice@example.test", decided.peerId)
    }

    @Test fun `two Autocrypt headers make the message worth nothing`() {
        // THE guard. A second header slipped in by anyone on the path would otherwise file THEIR
        // key under the correspondent's address, and everything written to that address afterwards
        // is encrypted to them.
        val two = listOf(
            "From" to "Alice <alice@example.test>",
            "Autocrypt" to "addr=alice@example.test; keydata=${AutocryptTestKey.RSA_4096}",
            "Autocrypt" to "addr=alice@example.test; keydata=${AutocryptTestKey.RSA_4096}",
        )
        assertNull(autocryptPeerImportOf(two, "alice@example.test", received, now))
    }

    @Test fun `an addr that is not the From is refused, and a different case is not`() {
        assertNull(decide(header("addr=mallory@example.test; keydata=${AutocryptTestKey.RSA_4096}")))
        val cased = autocryptPeerImportOf(
            header("addr=Alice@Example.Test; keydata=${AutocryptTestKey.RSA_4096}"),
            " ALICE@example.test ",
            received,
            now,
        ) ?: error("a case difference must not lose the key")
        assertEquals("alice@example.test", cased.peerId)
    }

    @Test fun `an unknown critical attribute drops the message, an underscored one does not`() {
        assertNull(decide(header("addr=alice@example.test; foo=bar; keydata=${AutocryptTestKey.RSA_4096}")))
        val tolerated = decide(header("addr=alice@example.test; _foo=bar; keydata=${AutocryptTestKey.RSA_4096}"))
            ?: error("a non-critical attribute must be ignored, not fatal")
        assertArrayEquals(keyBytes, tolerated.keyData)
    }

    @Test fun `prefer-encrypt is read and dropped`() {
        val mutual = decide(header("addr=alice@example.test; prefer-encrypt=mutual; keydata=${AutocryptTestKey.RSA_4096}"))
            ?: error("prefer-encrypt is a known attribute; it must not drop the message")
        assertArrayEquals(keyBytes, mutual.keyData)
    }

    @Test fun `keydata that cannot be decoded is refused, without throwing`() {
        // 1 549 characters: one short of a whole base64 group, which is what a cut header looks like.
        assertNull(decide(header("addr=alice@example.test; keydata=${AutocryptTestKey.RSA_4096.dropLast(3)}")))
        assertNull(decide(header("addr=alice@example.test; keydata=%%%%")))
        assertNull(decide(header("addr=alice@example.test; keydata=")))
        assertNull(decide(header("addr=alice@example.test")))
    }

    @Test fun `a message with no Autocrypt header decides nothing`() {
        assertNull(
            autocryptPeerImportOf(listOf("From" to "Alice <alice@example.test>"), "alice@example.test", received, now),
        )
    }

    @Test fun `the string the IMAP path builds from INTERNALDATE is read back to the same millis`() {
        // The seam between the two halves of the IMAP fix, EXECUTED: `MailRepository`
        // (`autocryptInternalDate`) turns the server's INTERNALDATE into a String with
        val stamp = java.time.Instant.ofEpochMilli(1_780_308_000_000L).toString()
        assertEquals("2026-06-01T10:00:00Z", stamp)
        val decided = autocryptPeerImportOf(
            header("addr=alice@example.test; keydata=${AutocryptTestKey.RSA_4096}"),
            "alice@example.test",
            stamp,
            now,
        ) ?: error("a date built the way the IMAP path builds it was refused")
        assertEquals(1_780_308_000_000L, decided.effectiveDateMillis)
    }

    @Test fun `a date that cannot be read is refused`() {
        // Arbitration (rule 1, the irreversible first): the provider files a peer's key AGAINST a
        // date, and a made-up "now" on an old message can make a stale key win over the current one
        // inside OpenKeychain — where this app cannot undo it. No date, no import; the message opens
        // exactly as before.
        val h = header("addr=alice@example.test; keydata=${AutocryptTestKey.RSA_4096}")
        assertNull(autocryptPeerImportOf(h, "alice@example.test", null, now))
        assertNull(autocryptPeerImportOf(h, "alice@example.test", "last tuesday", now))
    }

    @Test fun `a date beyond the tolerance is refused, and the same header dated sanely is not`() {
        // The PAIR: one header, one key, one address, and the only difference between the two
        // calls is the date. INTERNALDATE cannot be forged, but nothing bounds it, and on JMAP the
        // server's `receivedAt` is not bounded either. A key filed at 2100 wins over every
        // authentic key for the next 74 years INSIDE OpenKeychain, where this app cannot undo it.
        val h = header("addr=alice@example.test; keydata=${AutocryptTestKey.RSA_4096}")
        assertNull(autocryptPeerImportOf(h, "alice@example.test", "2100-01-01T00:00:00Z", now))
        val sane = autocryptPeerImportOf(h, "alice@example.test", received, now)
            ?: error("only the date differs from the case above; this one must be taken")
        assertEquals("alice@example.test", sane.peerId)
        assertArrayEquals(keyBytes, sane.keyData)
        assertEquals(receivedMillis, sane.effectiveDateMillis)
    }

    @Test fun `the tolerance is 24 h to the millisecond`() {
        // Both dates are literals, and so is the millis they read back as: nothing here recomputes
        // the bound. `now` is 2026-09-03T01:46:40Z, so the edge is 2026-09-04T01:46:40Z.
        val h = header("addr=alice@example.test; keydata=${AutocryptTestKey.RSA_4096}")
        val edge = autocryptPeerImportOf(h, "alice@example.test", "2026-09-04T01:46:40Z", now)
            ?: error("exactly 24 h ahead is the drift of a real sending clock, and is taken")
        assertEquals(1_788_486_400_000L, edge.effectiveDateMillis)
        assertNull(autocryptPeerImportOf(h, "alice@example.test", "2026-09-04T01:46:40.001Z", now))
    }

    @Test fun `an old date is still taken - only the future is bounded`() {
        // Nothing closes on the past side here, and bounding it too would drop keys off
        // perfectly ordinary mail — an old date already loses to a newer key inside the provider.
        val h = header("addr=alice@example.test; keydata=${AutocryptTestKey.RSA_4096}")
        val old = autocryptPeerImportOf(h, "alice@example.test", "1971-03-04T05:06:07Z", now)
            ?: error("a date in the past must still import")
        assertEquals(36_911_167_000L, old.effectiveDateMillis)
    }

    @Test fun `a From nobody stated is refused`() {
        val h = header("addr=alice@example.test; keydata=${AutocryptTestKey.RSA_4096}")
        assertNull(autocryptPeerImportOf(h, null, received, now))
        assertNull(autocryptPeerImportOf(h, "  ", received, now))
    }

    @Test fun `the field name is matched without case, and a duplicate attribute is fatal`() {
        assertTrue(
            decide(listOf("From" to "a", "autocrypt" to "addr=alice@example.test; keydata=${AutocryptTestKey.RSA_4096}")) != null,
        )
        assertNull(
            decide(
                header(
                    "addr=alice@example.test; addr=alice@example.test; keydata=${AutocryptTestKey.RSA_4096}",
                ),
            ),
        )
    }

    // ── What the provider is actually told ──────────────────────────────────────────────────────

    @Test fun `the provider is given the peer id, the key bytes and the date, and nothing else`() {
        val engine = RecordingEngine()
        val done = runBlocking {
            importAutocryptPeer(
                engine,
                header("addr=alice@example.test; keydata=${AutocryptTestKey.RSA_4096}"),
                "Alice@example.test",
                received,
                now,
            )
        }
        assertTrue(done)
        assertEquals(1, engine.updates.size)
        assertEquals("alice@example.test", engine.updates[0].first)
        assertArrayEquals(keyBytes, engine.updates[0].second)
        assertEquals(receivedMillis, engine.updates[0].third)
    }

    @Test fun `a message with no header never reaches the provider`() {
        val engine = RecordingEngine()
        val done = runBlocking {
            importAutocryptPeer(
                engine,
                listOf("From" to "Alice <alice@example.test>"),
                "alice@example.test",
                received,
                now,
            )
        }
        assertTrue(!done)
        assertEquals(emptyList<Any>(), engine.updates)
    }

    @Test fun `two headers never reach the provider either`() {
        val engine = RecordingEngine()
        val two = listOf(
            "Autocrypt" to "addr=alice@example.test; keydata=${AutocryptTestKey.RSA_4096}",
            "Autocrypt" to "addr=alice@example.test; keydata=${AutocryptTestKey.RSA_4096}",
        )
        runBlocking { importAutocryptPeer(engine, two, "alice@example.test", received, now) }
        assertEquals(emptyList<Any>(), engine.updates)
    }

    @Test fun `the edge of the tolerance is the edge at the provider too`() {
        // The two tests around this one drive `autocryptPeerImportOf` DIRECTLY, and the
        // provider-level one below sits 74 years past the edge — so nothing observed what
        val h = header("addr=alice@example.test; keydata=${AutocryptTestKey.RSA_4096}")
        val taken = RecordingEngine()
        val done = runBlocking {
            importAutocryptPeer(taken, h, "alice@example.test", "2026-09-04T01:46:40Z", now)
        }
        assertTrue(done)
        assertEquals(1, taken.updates.size)
        assertEquals(1_788_486_400_000L, taken.updates[0].third)

        val refused = RecordingEngine()
        val also = runBlocking {
            importAutocryptPeer(refused, h, "alice@example.test", "2026-09-04T01:46:40.001Z", now)
        }
        assertTrue(!also)
        assertEquals(emptyList<Any>(), refused.updates)
    }

    @Test fun `a date beyond the tolerance never reaches the provider`() {
        val engine = RecordingEngine()
        val done = runBlocking {
            importAutocryptPeer(
                engine,
                header("addr=alice@example.test; keydata=${AutocryptTestKey.RSA_4096}"),
                "alice@example.test",
                "2100-01-01T00:00:00Z",
                now,
            )
        }
        assertTrue(!done)
        assertEquals(emptyList<Any>(), engine.updates)
    }

    @Test fun `no engine at all is not a failure`() {
        val done = runBlocking {
            importAutocryptPeer(
                null,
                header("addr=alice@example.test; keydata=${AutocryptTestKey.RSA_4096}"),
                "alice@example.test",
                received,
                now,
            )
        }
        assertTrue(!done)
    }

    @Test fun `a provider that refuses is not a failure of the open either`() {
        for (answer in listOf(PgpResult.NotAvailable, PgpResult.Error("nope"))) {
            val engine = RecordingEngine(answer)
            val done = runBlocking {
                importAutocryptPeer(
                    engine,
                    header("addr=alice@example.test; keydata=${AutocryptTestKey.RSA_4096}"),
                    "alice@example.test",
                    received,
                    now,
                )
            }
            assertTrue(!done)
            assertEquals(1, engine.updates.size)
        }
    }

    /** A [PgpEngine] that records the one call this feature makes and answers [answer] to it. */
    private class RecordingEngine(private val answer: PgpResult<Unit> = PgpResult.Success(Unit)) : PgpEngine {
        val updates = mutableListOf<Triple<String, ByteArray, Long>>()

        override suspend fun updateAutocryptPeer(
            peerId: String,
            keyData: ByteArray,
            effectiveDateMillis: Long,
        ): PgpResult<Unit> {
            updates += Triple(peerId, keyData, effectiveDateMillis)
            return answer
        }

        override suspend fun isAvailable(): Boolean = true
        override suspend fun getSignKeyId(userIdHint: String?, interactionResult: Intent?): PgpResult<Long> =
            unused()

        override suspend fun getPublicKey(
            keyId: Long,
            ownAddress: String,
            interactionResult: Intent?,
        ): PgpResult<ByteArray> = unused()
        override suspend fun findKeys(emails: List<String>, interactionResult: Intent?): PgpResult<LongArray> =
            unused()

        override suspend fun findKeysEach(emails: List<String>): Map<String, Boolean> = unused()
        override suspend fun detachedSign(
            data: ByteArray,
            signKeyId: Long,
            interactionResult: Intent?,
        ): PgpResult<PgpSignature> = unused()

        override suspend fun signAndEncrypt(
            data: ByteArray,
            signKeyId: Long?,
            recipientKeyIds: LongArray,
            interactionResult: Intent?,
        ): PgpResult<ByteArray> = unused()

        override suspend fun decryptVerify(
            data: ByteArray,
            senderAddress: String?,
            detachedSignature: ByteArray?,
            interactionResult: Intent?,
        ): PgpResult<PgpDecrypted> = unused()

        private fun <T> unused(): T = error("this feature must call nothing but updateAutocryptPeer")
    }
}
