package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `Autocrypt:` header as [OutgoingMime.build] writes it — the ONE insertion point, and the one
 */
class OutgoingMimeAutocryptTest {

    private val header = "addr=alice@example.com; keydata=${AutocryptKeys.ED25519}"

    private fun msg(autocrypt: String? = null) = OutgoingMessage(
        from = "alice@example.com",
        to = listOf("bob@example.com"),
        subject = "Hi",
        body = "hello",
        messageId = "id-1@example.com",
        dateMillis = 0L,
        autocryptHeader = autocrypt,
    )

    private fun entity() =
        "Content-Type: multipart/signed; micalg=\"pgp-sha256\";\r\n" +
            " protocol=\"application/pgp-signature\"; boundary=\"b1\"\r\n" +
            "\r\n--b1\r\nContent-Type: text/plain\r\n\r\nhello\r\n--b1--\r\n"

    @Test fun `a message with a key to announce carries the header, verbatim`() {
        val mime = OutgoingMime.build(msg(header))
        assertTrue(mime.contains("\r\nAutocrypt: $header\r\n"))
    }

    @Test fun `a message with nothing to announce carries no such header`() {
        assertFalse(OutgoingMime.build(msg()).contains("Autocrypt"))
    }

    @Test fun `the header names no encryption preference`() {
        // Autocrypt Level 1 §2.1.1: absent means "nopreference". `prefer-encrypt=mutual` would tell
        // the correspondent's client to start encrypting on its own, which is not ours to say.
        assertFalse(OutgoingMime.build(msg(header)).contains("prefer-encrypt"))
    }

    @Test fun `the header sits above the body, next to the receipt`() {
        val mime = OutgoingMime.build(msg(header).copy(requestReceipt = true))
        val receipt = mime.indexOf("Disposition-Notification-To:")
        val autocrypt = mime.indexOf("Autocrypt:")
        val mimeVersion = mime.indexOf("MIME-Version:")
        assertTrue(receipt in 0 until autocrypt)
        assertTrue(autocrypt in 0 until mimeVersion)
    }

    @Test fun `an RSA key reaches the wire folded, so no line breaks RFC 5322`() {
        val big = "addr=alice@example.com; keydata=${AutocryptKeys.RSA_4096}"
        val mime = OutgoingMime.build(msg(big))
        mime.split("\r\n").forEach {
            assertTrue("line of ${it.toByteArray().size} octets", it.toByteArray().size <= 998)
        }
        // …and the key can be put back together by whoever reads it.
        val field = mime.substringAfter("\r\nAutocrypt: ").substringBefore("\r\nMIME-Version:")
        assertEquals(big, field.replace("\r\n ", ""))
    }

    @Test fun `a CRLF in the value cannot smuggle a header`() {
        // The sink is OutgoingMime.headerSafe, applied BEFORE folding — never after, or the folded
        // value would come out flat again.
        val mime = OutgoingMime.build(msg("addr=alice@example.com\r\nBcc: victim@evil.com; keydata=x"))
        assertFalse(mime.split("\r\n").any { it.startsWith("Bcc:") })
    }

    // --- the non-regression that matters ----------------------------------------------------------

    @Test fun `the signed payload is byte-identical with and without the header`() {
        val signed = PgpMime.signablePayload(OutgoingMime.buildBodyEntity(msg()))
        val announced = PgpMime.signablePayload(OutgoingMime.buildBodyEntity(msg(header)))
        assertEquals(
            "an Autocrypt header must not change the octets a signature covers",
            signed,
            announced,
        )
    }

    @Test fun `a pre-built entity goes out untouched, the header above it`() {
        val entity = entity()
        val mime = OutgoingMime.build(msg(header).copy(prebuiltEntity = entity))
        assertTrue("the entity must survive verbatim", mime.contains(entity))
        assertTrue(
            "the header belongs above the entity, or it changes what was signed",
            mime.indexOf("Autocrypt:") in 0 until mime.indexOf(entity),
        )
        // And the entity's own bytes are the same ones a message with no header carries.
        val without = OutgoingMime.build(msg().copy(prebuiltEntity = entity))
        assertEquals(
            mime.substringAfter("MIME-Version: 1.0\r\n"),
            without.substringAfter("MIME-Version: 1.0\r\n"),
        )
    }
}
