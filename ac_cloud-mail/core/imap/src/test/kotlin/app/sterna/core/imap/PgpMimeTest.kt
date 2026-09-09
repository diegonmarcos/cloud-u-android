package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PgpMimeTest {

    private fun message(subject: String = "s") = OutgoingMessage(
        from = "a@b.c",
        to = listOf("d@e.f"),
        subject = subject,
        body = "hello body",
        html = null,
        messageId = "mid123@b.c",
        dateMillis = 1_700_000_000_000,
    )

    @Test
    fun signedRoundTrip_extractedBytesMatchSignedPayload() {
        // Sender side: build the inner entity, canonicalize for signing, wrap.
        val inner = OutgoingMime.buildBodyEntity(message())
        val payload = PgpMime.signablePayload(inner)
        val sig = "-----BEGIN PGP SIGNATURE-----\r\n\r\nsig\r\n-----END PGP SIGNATURE-----"
        val entity = PgpMime.wrapSigned(payload, sig.toByteArray(), "pgp-sha256", "BND_SIG")

        // Receiver side: prepend message headers, run detection.
        val raw = "From: a@b.c\r\nMIME-Version: 1.0\r\n$entity"
        val env = MimeParser.detectCrypto(raw)
        assertNotNull(env)
        assertEquals(CryptoKind.PGP_SIGNED, env!!.kind)
        // THE invariant: what the verifier extracts is byte-identical to what was signed.
        assertEquals(payload, env.signedEntityRaw)
        assertTrue(env.signatureArmor!!.contains("BEGIN PGP SIGNATURE"))
    }

    @Test
    fun signedPayloadOfAFoldedBodyCarriesNoBareCr() {
        // The reported symptom, at the layer where it costs something: these are the bytes the
        // signature is computed over. A bare CR here is signed, so a conforming relay that
        // straightens the line changes the message and the recipient reads "invalid signature".
        //
        // The other tests of this file sign a 10-byte body — under the 57-byte wrap threshold,
        // so the encoder emits no separator and there is nothing to get wrong. That is exactly
        // how the defect stayed invisible here.
        val long = "The quick brown fox jumps over the lazy dog, and keeps on jumping until this " +
            "sentence is comfortably longer than fifty-seven bytes of plaintext."
        assertTrue("the body must be past the wrap threshold", long.toByteArray().size > 57)

        val payload = PgpMime.signablePayload(OutgoingMime.buildBodyEntity(message().copy(body = long)))

        assertFalse("a bare CR has no business in signed bytes. Payload was:\n$payload", "\r\r" in payload)
        val lines = payload.substringAfter("Content-Transfer-Encoding: base64\r\n\r\n").split("\r\n")
        assertTrue("the payload is wrapped, so there IS a separator to get wrong", lines.size > 1)
        assertEquals("the signed base64 must be folded at 76 columns", 76, lines[0].length)
    }

    @Test
    fun signedRoundTrip_withAttachmentEntity() {
        val m = message().copy(
            attachments = listOf(
                OutgoingAttachment(name = "doc.pdf", type = "application/pdf", bytes = "PDF".toByteArray()),
            ),
        )
        val payload = PgpMime.signablePayload(OutgoingMime.buildBodyEntity(m))
        val sig = "-----BEGIN PGP SIGNATURE-----\r\n\r\nx\r\n-----END PGP SIGNATURE-----"
        val entity = PgpMime.wrapSigned(payload, sig.toByteArray(), "pgp-sha256", "BND_SIG2")
        val env = MimeParser.detectCrypto("From: a@b.c\r\n$entity")
        assertNotNull(env)
        assertEquals(payload, env!!.signedEntityRaw)
        // The inner entity (a multipart/mixed) still parses: body + attachment inside.
        val innerBody = MimeParser.parseBody(env.signedEntityRaw!!)
        assertEquals("hello body", innerBody.text?.trim())
        assertEquals(1, innerBody.attachments.count { it.name == "doc.pdf" })
    }

    @Test
    fun encryptedRoundTrip_armorSurvives() {
        val armor = "-----BEGIN PGP MESSAGE-----\r\n\r\ncipher\r\n-----END PGP MESSAGE-----"
        val entity = PgpMime.wrapEncrypted(armor.toByteArray(), "BND_ENC")
        val env = MimeParser.detectCrypto("From: a@b.c\r\nMIME-Version: 1.0\r\n$entity")
        assertNotNull(env)
        assertEquals(CryptoKind.PGP_ENCRYPTED, env!!.kind)
        assertEquals(armor, env.encryptedArmor!!.trimEnd())
    }

    @Test
    fun prebuiltEntityReplacesBodyInFullMessage() {
        val armor = "-----BEGIN PGP MESSAGE-----\r\n\r\nzz\r\n-----END PGP MESSAGE-----"
        val entity = PgpMime.wrapEncrypted(armor.toByteArray(), "BND3")
        val full = OutgoingMime.build(message().copy(prebuiltEntity = entity))
        // Headers present, normal body absent, entity spliced in.
        assertTrue(full.startsWith("From: a@b.c\r\n"))
        assertTrue(full.contains("Subject: s\r\n"))
        assertTrue(full.contains("multipart/encrypted"))
        assertTrue(!full.contains("text/plain; charset=utf-8"))
        // And the whole message still detects as encrypted.
        assertEquals(CryptoKind.PGP_ENCRYPTED, MimeParser.detectCrypto(full)?.kind)
    }

    @Test
    fun buildBodyEntityMatchesLegacySingleBodyBuild() {
        // build() with no prebuiltEntity must equal headers + buildBodyEntity (refactor guard).
        val m = message()
        val full = OutgoingMime.build(m)
        assertTrue(full.endsWith(OutgoingMime.buildBodyEntity(m)))
    }
}
