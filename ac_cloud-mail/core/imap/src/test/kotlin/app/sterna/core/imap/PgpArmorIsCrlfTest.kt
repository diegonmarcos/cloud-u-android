package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **A PGP armor arrives with bare LFs, and nothing downstream fixes them.**
 */
class PgpArmorIsCrlfTest {

    @Test
    fun `a signed wrapper made of provider bytes carries no bare LF`() {
        val entity = PgpMime.wrapSigned(
            "Content-Type: text/plain\r\n\r\nx",
            bareLfArmor("SIGNATURE"),
            "pgp-sha256",
            "BND",
        )

        assertNoBareLf("the wrapped signature", entity)
        assertArmorIsIntact("SIGNATURE", entity)
    }

    @Test
    fun `an encrypted wrapper made of provider bytes carries no bare LF`() {
        val entity = PgpMime.wrapEncrypted(bareLfArmor("MESSAGE"), "BND")

        assertNoBareLf("the wrapped ciphertext", entity)
        assertArmorIsIntact("MESSAGE", entity)
    }

    @Test
    fun `an entity already queued on disk is repaired at the delivery seam`() {
        // The case a fix in PgpMime alone does NOT cover: this entity was built by an older
        // version, written to files/outbox/<id>/prebuilt-entity.mime, and is read back verbatim.
        // Nothing will ever rebuild it — the seam is the only place left to make it conforming.
        val queued = "Content-Type: multipart/encrypted; boundary=\"B\"\r\n\r\n--B\n" +
            String(bareLfArmor("MESSAGE"), Charsets.US_ASCII) + "--B--\n"

        val mime = OutgoingMime.build(pgpMessage().copy(prebuiltEntity = queued))

        assertNoBareLf("the delivered message", mime)
        assertArmorIsIntact("MESSAGE", mime)
    }

    @Test
    fun `a signed entity crosses the delivery seam with its signed bytes intact`() {
        // The property the whole change puts at risk, and the only test that exercises it end to
        // end: canonicalising AT THE SEAM must not disturb the octets the signature covers.
        // A long body on purpose — the base64 is folded, so the signed payload is many lines and
        // there are separators to get wrong.
        val payload = PgpMime.signablePayload(OutgoingMime.buildBodyEntity(pgpMessage().copy(body = LONG_BODY)))
        val entity = PgpMime.wrapSigned(payload, bareLfArmor("SIGNATURE"), "pgp-sha256", "BND_SEAM")

        val mime = OutgoingMime.build(pgpMessage().copy(prebuiltEntity = entity))

        assertNoBareLf("the delivered signed message", mime)
        val env = MimeParser.detectCrypto(mime)
        assertNotNull("the delivered message no longer detects as PGP/MIME signed at all", env)
        assertEquals(
            "the bytes a verifier extracts from the delivered message are no longer the bytes that " +
                "were signed: every recipient reads \"invalid signature\", and the app says nothing",
            payload,
            env!!.signedEntityRaw,
        )
    }

    @Test
    fun `a bare CR inside signed bytes crosses the seam untouched`() {
        // The one thing canonicalisation must NOT tidy up. An entity signed before dee4a346
        // and still sitting in the outbox contains "\r\r\n" INSIDE the octets the signature was
        val queued = "Content-Type: text/plain\r\n\r\nsigned line one\r\r\nsigned line two\r\n"

        val mime = OutgoingMime.build(pgpMessage().copy(prebuiltEntity = queued))

        assertTrue(
            "the CR/CRLF of a pre-dee4a346 signed entity was straightened at the seam: the bytes " +
                "no longer match the signature and the recipient reads \"invalid signature\". " +
                "Delivered body was:\n" + mime.substringAfter("MIME-Version: 1.0\r\n"),
            "signed line one\r\r\nsigned line two" in mime,
        )
    }

    private fun pgpMessage() = OutgoingMessage(
        from = "a@b.c",
        to = listOf("d@e.f"),
        subject = "s",
        body = "unused when a pre-built entity replaces the body",
        messageId = "mid@b.c",
        dateMillis = 1_700_000_000_000L,
    )

    private companion object {
        /** Past the 57-byte input that fills one 76-column base64 line, so the payload is folded. */
        const val LONG_BODY =
            "The quick brown fox jumps over the lazy dog, and keeps on jumping until this " +
                "sentence is comfortably longer than fifty-seven bytes of plaintext."
    }
}

/**
 * The lines of a provider armor, as literals — the ONE description of the fixture, from which both
 */
internal fun armorLines(kind: String): List<String> = buildList {
    add("-----BEGIN PGP $kind-----")
    add("")
    repeat(20) { i -> add("iQIzBAABCgAdFiEEABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnop${"%06d".format(i)}") }
    add(".abcdef")
    add("=abcd")
    add("-----END PGP $kind-----")
}

/** The armor as the provider really hands it over: bare LF between every line. */
internal fun bareLfArmor(kind: String): ByteArray =
    armorLines(kind).joinToString(separator = "\n", postfix = "\n").toByteArray(Charsets.US_ASCII)

/** The same armor as it must appear once delivered: CRLF, every line, nothing added or dropped. */
internal fun expectedCrlfArmor(kind: String): String =
    armorLines(kind).joinToString(separator = "\r\n", postfix = "\r\n")

/** Every `\n` that is not preceded by `\r` — what a hardened relay counts and refuses. */
internal fun bareLfOffsets(s: String): List<Int> =
    s.indices.filter { s[it] == '\n' && (it == 0 || s[it - 1] != '\r') }

internal fun assertNoBareLf(what: String, s: String) {
    val offsets = bareLfOffsets(s)
    assertEquals(
        "$what still carries ${offsets.size} bare LF(s), at offsets ${offsets.take(5)}… — a relay " +
            "with smtpd_forbid_bare_newline refuses the DATA and the message never leaves the outbox",
        emptyList<Int>(),
        offsets,
    )
}

/**
 * The WHOLE armor, byte for byte, as one CRLF block — not a line-by-line search.
 */
internal fun assertArmorIsIntact(kind: String, produced: String) {
    val expected = expectedCrlfArmor(kind)
    assertTrue(
        "the armor is not present intact as a CRLF block — a line was dropped, reordered or " +
            "reflowed. Expected to find:\n$expected\ninside:\n$produced",
        expected in produced,
    )
}
