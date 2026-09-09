package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `OutgoingMime.buildBodyEntity` with and without a protected subject (RFC 9788 § 3).
 */
class ProtectedHeadersEntityTest {

    // --- fixtures --------------------------------------------------------------------------------

    private val plain = OutgoingMessage(
        from = "alice@example.com",
        to = listOf("bob@example.com"),
        subject = "envelope subject, not this one",
        body = "hello",
        messageId = "id-1@example.com",
        dateMillis = 0L,
    )

    private fun html(attachments: List<OutgoingAttachment>) = plain.copy(
        html = "<p>hi <img src=\"cid:logo@x\"></p>",
        attachments = attachments,
    )

    private val image = OutgoingAttachment(
        name = "logo.png", type = "image/png", bytes = byteArrayOf(1, 2, 3),
        cid = "logo@x", inline = true,
    )
    private val file = OutgoingAttachment(
        name = "doc.pdf", type = "application/pdf", bytes = byteArrayOf(4, 5, 6),
    )

    /** The two `--boundary` delimiters this fixture's Message-ID produces, written out. */
    private val mixed = "------sterna_mixed_id1examplecom"
    private val related = "------sterna_related_id1examplecom"

    private val htmlPart =
        "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Transfer-Encoding: base64\r\n\r\n" +
            "PHA+aGkgPGltZyBzcmM9ImNpZDpsb2dvQHgiPjwvcD4=\r\n"
    private val pdfPart =
        "Content-Type: application/pdf; name=\"doc.pdf\"\r\n" +
            "Content-Transfer-Encoding: base64\r\n" +
            "Content-Disposition: attachment; filename=\"doc.pdf\"\r\n\r\n" +
            "BAUG\r\n"
    private val logoPart =
        "Content-Type: image/png; name=\"logo.png\"\r\n" +
            "Content-Transfer-Encoding: base64\r\n" +
            "Content-ID: <logo@x>\r\n" +
            "Content-Disposition: inline; filename=\"logo.png\"\r\n\r\n" +
            "AQID\r\n"

    /** The inside of a multipart/related: body part, inline image, closing delimiter. */
    private val relatedParts = "$related\r\n" + htmlPart + "$related\r\n" + logoPart + "$related--\r\n"

    /** The inside of the outer multipart/mixed of the fourth branch (related + the file). */
    private val nestedRelated =
        "$mixed\r\n" +
            "Content-Type: multipart/related; type=\"text/html\"; boundary=\"----sterna_related_id1examplecom\"\r\n\r\n" +
            relatedParts +
            "$mixed\r\n" + pdfPart + "$mixed--\r\n"

    private val topic = "Quarterly report"

    // --- no protected subject: the bytes of before, in all four branches --------------------------

    @Test fun singlePartWithoutAProtectedSubjectIsWhatItAlwaysWas() {
        assertEquals(
            "Content-Type: text/plain; charset=utf-8\r\n" +
                "Content-Transfer-Encoding: base64\r\n" +
                "\r\n" +
                "aGVsbG8=",
            OutgoingMime.buildBodyEntity(plain),
        )
    }

    @Test fun mixedWithoutAProtectedSubjectIsWhatItAlwaysWas() {
        assertEquals(
            "Content-Type: multipart/mixed; boundary=\"----sterna_mixed_id1examplecom\"\r\n\r\n" +
                "$mixed\r\n" + htmlPart + "$mixed\r\n" + pdfPart + "$mixed--\r\n",
            OutgoingMime.buildBodyEntity(html(listOf(file))),
        )
    }

    @Test fun relatedWithoutAProtectedSubjectIsWhatItAlwaysWas() {
        assertEquals(
            "Content-Type: multipart/related; type=\"text/html\"; boundary=\"----sterna_related_id1examplecom\"\r\n\r\n" +
                relatedParts,
            OutgoingMime.buildBodyEntity(html(listOf(image))),
        )
    }

    @Test fun mixedRelatedWithoutAProtectedSubjectIsWhatItAlwaysWas() {
        assertEquals(
            "Content-Type: multipart/mixed; boundary=\"----sterna_mixed_id1examplecom\"\r\n\r\n" + nestedRelated,
            OutgoingMime.buildBodyEntity(html(listOf(image, file))),
        )
    }

    @Test fun theNullPathWritesNeitherASubjectNorTheParameter() {
        // The absence half, over all four branches at once: a header line reaching an ordinary
        // send is the failure this whole default argument exists to make impossible.
        for (m in listOf(plain, html(listOf(file)), html(listOf(image)), html(listOf(image, file)))) {
            val entity = OutgoingMime.buildBodyEntity(m)
            assertFalse("no Subject: field in <$entity>", entity.contains("Subject:"))
            assertFalse("no protected-headers in <$entity>", entity.contains("protected-headers"))
        }
    }

    // --- with a protected subject -----------------------------------------------------------------

    @Test fun singlePartCarriesTheSubjectAboveAMarkedContentType() {
        assertEquals(
            "Subject: Quarterly report\r\n" +
                "Content-Type: text/plain; charset=utf-8; protected-headers=\"v1\"\r\n" +
                "Content-Transfer-Encoding: base64\r\n" +
                "\r\n" +
                "aGVsbG8=",
            OutgoingMime.buildBodyEntity(plain, topic),
        )
    }

    @Test fun mixedCarriesTheSubjectAboveAMarkedContentType() {
        assertEquals(
            "Subject: Quarterly report\r\n" +
                "Content-Type: multipart/mixed; boundary=\"----sterna_mixed_id1examplecom\"; " +
                "protected-headers=\"v1\"\r\n\r\n" +
                "$mixed\r\n" + htmlPart + "$mixed\r\n" + pdfPart + "$mixed--\r\n",
            OutgoingMime.buildBodyEntity(html(listOf(file)), topic),
        )
    }

    @Test fun relatedCarriesTheSubjectAboveAMarkedContentType() {
        assertEquals(
            "Subject: Quarterly report\r\n" +
                "Content-Type: multipart/related; type=\"text/html\"; " +
                "boundary=\"----sterna_related_id1examplecom\"; protected-headers=\"v1\"\r\n\r\n" +
                relatedParts,
            OutgoingMime.buildBodyEntity(html(listOf(image)), topic),
        )
    }

    @Test fun mixedRelatedMarksTheOuterContentTypeAndLeavesTheNestedOneAlone() {
        // The nested multipart/related is a SUB-PART. Marked, it would claim a second protected
        // header block inside the first, and the entity would describe two.
        assertEquals(
            "Subject: Quarterly report\r\n" +
                "Content-Type: multipart/mixed; boundary=\"----sterna_mixed_id1examplecom\"; " +
                "protected-headers=\"v1\"\r\n\r\n" + nestedRelated,
            OutgoingMime.buildBodyEntity(html(listOf(image, file)), topic),
        )
    }

    @Test fun exactlyOneContentTypeIsMarkedInEveryBranch() {
        // Counts, so it catches the parameter appended by a shared helper to every part — which
        // the whole-entity expectations above catch too, but only for these fixtures' part lists.
        for (m in listOf(plain, html(listOf(file)), html(listOf(image)), html(listOf(image, file)))) {
            val entity = OutgoingMime.buildBodyEntity(m, topic)
            assertEquals(
                "exactly one protected-headers parameter in <$entity>",
                1,
                Regex("protected-headers").findAll(entity).count(),
            )
            assertEquals(
                "exactly one Subject: field in <$entity>",
                1,
                Regex("Subject:").findAll(entity).count(),
            )
            assertTrue(
                "the Subject: field opens the entity, above the Content-Type of head",
                entity.startsWith("Subject: Quarterly report\r\nContent-Type: "),
            )
        }
    }

    // --- the subject is header-encoded, and cannot be a header block of its own --------------------

    @Test fun aNonAsciiSubjectIsAnEncodedWordAndNotRawBytes() {
        val entity = OutgoingMime.buildBodyEntity(plain, "Betreff mit Ümläut — тема")
        assertEquals(
            "Subject: =?utf-8?B?QmV0cmVmZiBtaXQgw5xtbMOkdXQg4oCUINGC0LXQvNCw?=",
            entity.lineSequence().first(),
        )
        assertFalse("no raw non-ASCII on the wire", entity.contains("Ümläut"))
    }

    @Test fun aSubjectCarryingCrlfCannotSplitTheHeaderBlock() {
        // The subject reaching here is the composer's own, but it is the same sink as the
        // envelope's and it answers the same way: encoded whole, so the injected field never
        // begins a line — and the header block still ends at the ONE blank line below.
        val entity = OutgoingMime.buildBodyEntity(plain, "Real subject\r\nX-Injected: victim@evil.com")
        assertFalse("the smuggled field must not exist at all", entity.contains("X-Injected"))
        assertEquals(
            "the header block is still Subject + Content-Type + Content-Transfer-Encoding",
            3,
            entity.substringBefore("\r\n\r\n").split("\r\n").size,
        )
    }

    // --- end to end: our own reader gets the subject back -----------------------------------------

    @Test fun ourOwnParserReadsTheProtectedSubjectBackDecoded() {
        // The entity a recipient decrypts is exactly this string. `MimeParser.decodedHeaderOf` is
        // the door the reader uses on a decrypted entity, and it stops at the first blank line —
        // so this fails outright if the Subject: field is written anywhere below the Content-Type.
        val entity = OutgoingMime.buildBodyEntity(html(listOf(image, file)), "Betreff mit Ümläut — тема")
        assertEquals(
            "Betreff mit Ümläut — тема",
            MimeParser.decodedHeaderOf(entity, "Subject"),
        )
    }

    @Test fun theEnvelopeSubjectIsUntouchedWhileTheEntityCarriesItsOwn() {
        // The feature is INTEGRITY, not masking. `build` writes the real subject in the clear
        // exactly as before; nothing here is allowed to remove or rewrite it.
        val mime = OutgoingMime.build(plain)
        assertEquals(
            "Subject: envelope subject, not this one",
            mime.lineSequence().first { it.startsWith("Subject:") },
        )
        assertFalse("an ordinary send carries no protected-headers", mime.contains("protected-headers"))
    }

    // --- the base64 separator guard still holds with a header block above it ----------------------

    @Test fun aLongBodyUnderAProtectedSubjectStillHasNoBareCr() {
        // 2 kB, far past the 57-byte input where the MIME encoder starts wrapping — a `\r\r\n`
        // separator is invisible below that, and on an ENCRYPTED-and-signed message those bytes
        // are the signed bytes.
        val long = "The quick brown fox jumps over the lazy dog. ".repeat(50)
        assertTrue("the body really is past the wrap threshold", long.toByteArray(Charsets.UTF_8).size >= 2048)
        val entity = OutgoingMime.buildBodyEntity(plain.copy(body = long), topic)
        assertFalse("no bare CR anywhere in the entity", entity.contains("\r\r"))
        assertEquals(
            "the body still round-trips",
            long,
            String(
                java.util.Base64.getMimeDecoder()
                    .decode(entity.substringAfter("Content-Transfer-Encoding: base64\r\n\r\n")),
                Charsets.UTF_8,
            ),
        )
    }
}
