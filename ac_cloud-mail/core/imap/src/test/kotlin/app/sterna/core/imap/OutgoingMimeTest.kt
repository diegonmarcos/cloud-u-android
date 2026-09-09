package app.sterna.core.imap

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutgoingMimeTest {
    private fun msg(
        to: List<String> = listOf("bob@example.com"),
        references: String? = null,
        subject: String = "Hi",
    ) = OutgoingMessage(
        from = "alice@example.com",
        to = to,
        subject = subject,
        body = "hello",
        messageId = "id-1@example.com",
        dateMillis = 0L,
        references = references,
    )

    @Test
    fun recipientCrlfCannotInjectExtraHeaders() {
        // A crafted recipient tries to smuggle a header of its own. The smuggled name is one
        // `build` never writes: `Bcc:` became a legitimate header here (for the stored draft
        // copy), so its absence would no longer say anything about the CRLF filter.
        val mime = OutgoingMime.build(msg(to = listOf("bob@example.com\r\nX-Injected: victim@evil.com")))
        // The CRLF is stripped, so "X-Injected:" can never begin its own header line.
        assertFalse("injected header must not appear", mime.contains("\nX-Injected:"))
        // The recipient stays on a single To line (the smuggled text is now inert).
        val toLine = mime.lineSequence().first { it.startsWith("To:") }
        assertTrue(toLine.contains("bob@example.com"))
    }

    @Test
    fun referencesCrlfIsStripped() {
        val mime = OutgoingMime.build(msg(references = "<x@a>\r\nX-Injected: victim@evil.com"))
        assertFalse(mime.contains("\nX-Injected:"))
    }

    @Test
    fun controlCharsInSubjectAreEncodedNotRaw() {
        // CR/LF in the subject must not break out into a new header line.
        val mime = OutgoingMime.build(msg(subject = "Hello\r\nX-Injected: victim@evil.com"))
        assertFalse(mime.contains("\nX-Injected:"))
        val subjectLine = mime.lineSequence().first { it.startsWith("Subject:") }
        assertTrue("non-ASCII/control subject is RFC 2047 encoded", subjectLine.contains("=?utf-8?B?"))
    }

    @Test
    fun cleanMessageStillBuilds() {
        val mime = OutgoingMime.build(msg())
        assertEquals("From: alice@example.com", mime.lineSequence().first { it.startsWith("From:") })
        assertTrue(mime.contains("To: bob@example.com"))
    }

    @Test
    fun emptyRecipientsOmitsToHeader() {
        // A draft may have no recipient yet (#69): the To header must be omitted entirely, not
        // emitted empty ("To: "), which is malformed and made recipient-less drafts unsaveable.
        val mime = OutgoingMime.build(msg(to = emptyList()))
        assertFalse("no To header when there is no recipient", mime.contains("To:"))
        // The rest of the message still builds normally.
        assertTrue(mime.contains("From: alice@example.com"))
        assertTrue(mime.contains("Subject: Hi"))
    }

    // --- Bcc: written for a stored copy, never for a submission ---

    private fun withCopies(bcc: List<String>) =
        msg().copy(cc = listOf("carol@example.com"), bcc = bcc)

    @Test
    fun bccIsNotWrittenByDefault() {
        // The default is what SmtpClient.buildMime uses, and its result goes into DATA unfiltered:
        // a Bcc header here is the hidden recipients handed to everyone the message names.
        val mime = OutgoingMime.build(withCopies(listOf("hidden@example.com")))

        assertEquals(
            "build() with the OMITTED default must write no Bcc header at all — these bytes are " +
                "what a submission puts on the wire:\n" + mime.substringBefore("MIME-Version:"),
            emptyList<String>(),
            mime.lineSequence().filter { it.startsWith("Bcc:") }.toList(),
        )
        // And the blind recipient is nowhere else in the message either.
        assertFalse("no trace of the blind recipient anywhere", mime.contains("hidden@example.com"))
    }

    @Test
    fun blindCopiesWrittenPutsTheHeaderRightAfterCc() {
        // The draft APPEND: the Cci the composer typed has nowhere else to survive, because the
        // draft is reopened from the message the server kept.
        val mime = OutgoingMime.build(withCopies(listOf("hidden@example.com")), BlindCopies.WRITTEN)

        val lines = mime.lineSequence().toList()
        assertEquals(
            "BlindCopies.WRITTEN must write the blind recipients as a Bcc header:\n" +
                mime.substringBefore("MIME-Version:"),
            listOf("Bcc: hidden@example.com"),
            lines.filter { it.startsWith("Bcc:") },
        )
        // Among the address headers, immediately below the Cc — the position a reader (and every
        // envelope parser) expects, not somewhere down in the body.
        val cc = lines.indexOfFirst { it.startsWith("Cc:") }
        val bcc = lines.indexOfFirst { it.startsWith("Bcc:") }
        assertTrue("there must be a Cc line to sit under", cc >= 0)
        assertEquals("the Bcc line comes right after the Cc line", cc + 1, bcc)
    }

    @Test
    fun blindCopiesWrittenWithNoBlindRecipientOmitsTheHeaderEntirely() {
        // The #69 shape, one field down. Most drafts have no Cci, and "Bcc:" with nothing after it
        // is a malformed header — an empty "To:" is what made recipient-less drafts unsaveable,
        // because the APPEND itself is refused. WRITTEN says "you MAY write it", not "write it".
        val mime = OutgoingMime.build(withCopies(emptyList()), BlindCopies.WRITTEN)

        assertEquals(
            "BlindCopies.WRITTEN with no blind recipient must write no Bcc header at all — an " +
                "empty one is malformed, and the draft save is an APPEND a server may refuse " +
                "outright:\n" +
                mime.substringBefore("MIME-Version:"),
            emptyList<String>(),
            mime.lineSequence().filter { it.startsWith("Bcc") }.toList(),
        )
        // And the header above it is untouched: this omits one line, it does not skip the block.
        assertTrue(mime.contains("Cc: carol@example.com"))
    }

    @Test
    fun aCrlfInTheBccCannotSplitTheHeaderItIsNowAllowedToWrite() {
        // headerSafe covers the Bcc exactly as it covers the To and the Cc: a saved draft can be
        // reopened, edited and re-saved, so this value is as attacker-influenced as any other.
        val mime = OutgoingMime.build(
            withCopies(listOf("hidden@example.com\r\nX-Injected: victim@evil.com")),
            BlindCopies.WRITTEN,
        )

        assertFalse("the smuggled header must not begin a line", mime.contains("\nX-Injected:"))
        assertEquals(
            "the blind recipient must stay on ONE header line, CRLF eaten by headerSafe:\n" +
                mime.substringBefore("MIME-Version:"),
            listOf("Bcc: hidden@example.comX-Injected: victim@evil.com"),
            mime.lineSequence().filter { it.startsWith("Bcc:") }.toList(),
        )
    }

    private fun htmlMsg(attachments: List<OutgoingAttachment>) = OutgoingMessage(
        from = "alice@example.com",
        to = listOf("bob@example.com"),
        subject = "Hi",
        body = "hello",
        html = "<p>hi <img src=\"cid:logo@x\"></p>",
        messageId = "id-1@example.com",
        dateMillis = 0L,
        attachments = attachments,
    )

    private val image = OutgoingAttachment(
        name = "logo.png", type = "image/png", bytes = byteArrayOf(1, 2, 3),
        cid = "logo@x", inline = true,
    )
    private val file = OutgoingAttachment(
        name = "doc.pdf", type = "application/pdf", bytes = byteArrayOf(4, 5, 6),
    )

    @Test
    fun noAttachmentsStaysSinglePart() {
        val mime = OutgoingMime.build(htmlMsg(emptyList()))
        assertFalse("no multipart", mime.contains("multipart/"))
        assertTrue("html content type", mime.contains("Content-Type: text/html; charset=utf-8"))
    }

    @Test
    fun fileOnlyStaysMultipartMixedWithoutRelatedOrContentId() {
        val mime = OutgoingMime.build(htmlMsg(listOf(file)))
        assertTrue("mixed", mime.contains("Content-Type: multipart/mixed;"))
        assertFalse("no related", mime.contains("multipart/related"))
        assertFalse("no Content-ID", mime.contains("Content-ID:"))
        assertTrue("file attachment", mime.contains("Content-Disposition: attachment; filename=\"doc.pdf\""))
    }

    @Test
    fun inlineOnlyEmitsMultipartRelatedWithContentIdAndInlineDisposition() {
        val mime = OutgoingMime.build(htmlMsg(listOf(image)))
        assertTrue("related is the top-level body", mime.contains("Content-Type: multipart/related;"))
        assertFalse("no mixed wrapper", mime.contains("multipart/mixed"))
        assertTrue("html part present", mime.contains("Content-Type: text/html; charset=utf-8"))
        assertTrue("Content-ID with angle brackets", mime.contains("Content-ID: <logo@x>"))
        assertTrue("inline disposition", mime.contains("Content-Disposition: inline; filename=\"logo.png\""))
        assertTrue("image content type", mime.contains("Content-Type: image/png; name=\"logo.png\""))
    }

    @Test
    fun inlineAndFileWrapRelatedInsideMixed() {
        val mime = OutgoingMime.build(htmlMsg(listOf(image, file)))
        assertTrue("mixed wrapper", mime.contains("Content-Type: multipart/mixed;"))
        assertTrue("related nested", mime.contains("Content-Type: multipart/related;"))
        assertTrue("inline image kept", mime.contains("Content-ID: <logo@x>"))
        assertTrue("inline disposition", mime.contains("Content-Disposition: inline; filename=\"logo.png\""))
        assertTrue("file attachment kept", mime.contains("Content-Disposition: attachment; filename=\"doc.pdf\""))
        // The related entity opens before the file attachment part (nesting order).
        assertTrue(
            "related precedes file",
            mime.indexOf("multipart/related") < mime.indexOf("filename=\"doc.pdf\""),
        )
    }

    @Test
    fun inlineImageCannotInjectHeadersViaCid() {
        val evil = image.copy(cid = "logo@x\r\nX-Injected: victim@evil.com")
        val mime = OutgoingMime.build(htmlMsg(listOf(evil)))
        assertFalse("CRLF in cid stripped", mime.contains("\nX-Injected:"))
    }

    // --- From/address display-name encoding (#77) ---

    @Test
    fun addressNoNameIsBareAddr() {
        assertEquals("alice@example.com", OutgoingMime.formatAddress(null, "alice@example.com"))
        assertEquals("alice@example.com", OutgoingMime.formatAddress("   ", "alice@example.com"))
    }

    @Test
    fun addressPlainAsciiNameStaysUnquotedAtom() {
        assertEquals(
            "Alice Smith <alice@example.com>",
            OutgoingMime.formatAddress("Alice Smith", "alice@example.com"),
        )
    }

    @Test
    fun addressNameWithSpecialsIsQuoted() {
        // A display name set to the email address holds '@' and '.', both RFC 5322 specials.
        assertEquals(
            "\"alice@example.com\" <alice@example.com>",
            OutgoingMime.formatAddress("alice@example.com", "alice@example.com"),
        )
    }

    @Test
    fun addressNameWithQuoteAndBackslashIsEscaped() {
        // Backslash escaped first, then the double quote.
        assertEquals(
            "\"a\\\\b\\\"c\" <alice@example.com>",
            OutgoingMime.formatAddress("a\\b\"c", "alice@example.com"),
        )
    }

    @Test
    fun addressNonAsciiNameIsRfc2047EncodedNotQuoted() {
        val out = OutgoingMime.formatAddress("Éloïse", "eloise@example.com")
        assertTrue("RFC 2047 encoded-word", out.startsWith("=?utf-8?B?"))
        assertTrue("addr appended", out.endsWith("?= <eloise@example.com>"))
        assertFalse("encoded-word is not also quoted", out.contains("\""))
    }

    @Test
    fun envelopeAddressStripsCrlfSoNoExtraSmtpCommandIsSmuggled() {
        // An address with no angle brackets used to reach "MAIL FROM:<…>"/"RCPT TO:<…>" verbatim,
        // so a CRLF in it appended a second envelope command (here a hidden recipient).
        val hostile = "bob@example.com\r\nRCPT TO:victim@evil.com"

        val envelope = OutgoingMime.envelopeAddress(hostile)

        assertFalse("no CR survives", envelope.contains('\r'))
        assertFalse("no LF survives", envelope.contains('\n'))
        assertEquals("bob@example.comRCPT TO:victim@evil.com", envelope)
        // The whole thing stays one SMTP command line, so nothing new is injected.
        assertFalse("no extra command line", "MAIL FROM:<$envelope>".contains("\r\n"))
    }

    @Test
    fun envelopeAddressKeepsTheBracketedAddrSpec() {
        assertEquals("bob@example.com", OutgoingMime.envelopeAddress("Bob <bob@example.com>"))
        assertEquals("bob@example.com", OutgoingMime.envelopeAddress("  bob@example.com  "))
        // A CRLF inside the brackets is filtered too, not just the bare-address form.
        assertEquals(
            "bob@example.comRCPT TO:victim@evil.com",
            OutgoingMime.envelopeAddress("Bob <bob@example.com\r\nRCPT TO:victim@evil.com>"),
        )
    }

    // --- the read receipt (RFC 8098) --------------------------------------------------------------

    @Test
    fun receiptRequestedWritesDispositionNotificationToTheFromAddress() {
        val mime = OutgoingMime.build(msg().copy(requestReceipt = true))

        // The address, not just the header: a receipt sent anywhere but the visible From is
        // either a leak or a receipt nobody receives.
        assertEquals(
            "Disposition-Notification-To: alice@example.com",
            mime.lineSequence().first { it.startsWith("Disposition-Notification-To:") },
        )
        // Exactly one, and the pre-RFC spellings are deliberately not written at all.
        assertEquals(1, mime.lineSequence().count { it.startsWith("Disposition-Notification-To:") })
        assertFalse("no Return-Receipt-To", mime.contains("Return-Receipt-To"))
        assertFalse("no X-Confirm-Reading-To", mime.contains("X-Confirm-Reading-To"))
    }

    @Test
    fun noReceiptAskedMeansNoSuchHeaderAtAll() {
        // The negative witness carries as much weight as the positive one: this header on a message
        // nobody asked it for tells every recipient when the mail was opened.
        val mime = OutgoingMime.build(msg())
        assertFalse("no receipt header when none was asked for", mime.contains("Disposition-Notification-To"))
    }

    @Test
    fun theReceiptFollowsTheFromItIsBuiltWith() {
        // The "on behalf" case: a delegated send is submitted by one identity and shows another in
        // From. The receipt must go where the correspondent can see it — the From — so it is that
        // address, not the login, that appears here.
        val mime = OutgoingMime.build(
            msg().copy(from = "team@example.org", requestReceipt = true),
        )
        assertEquals(
            "Disposition-Notification-To: team@example.org",
            mime.lineSequence().first { it.startsWith("Disposition-Notification-To:") },
        )
    }

    @Test
    fun aDisplayNameWithSpecialsSurvivesAsAQuotedMailbox() {
        // A comma in a display name is what turns one mailbox into two: unquoted, "Ruíz, Ana"
        // reads as two addresses and the receipt is copied to a second, non-existent one.
        val from = OutgoingMime.formatAddress("Ruíz, Ana", "ana@example.org")
        val mime = OutgoingMime.build(msg().copy(from = from, requestReceipt = true))

        val line = mime.lineSequence().first { it.startsWith("Disposition-Notification-To:") }
        assertEquals("Disposition-Notification-To: $from", line)
        // Same value as the From header, character for character: two headers naming the same
        // mailbox differently is exactly the drift a separate address field would allow.
        assertEquals(
            mime.lineSequence().first { it.startsWith("From:") }.removePrefix("From:").trim(),
            line.removePrefix("Disposition-Notification-To:").trim(),
        )
    }

    @Test
    fun receiptAddressCrlfCannotInjectExtraHeaders() {
        val mime = OutgoingMime.build(
            msg().copy(
                from = "alice@example.com\r\nX-Injected: victim@evil.com",
                requestReceipt = true,
            ),
        )

        assertFalse("injected header must not appear", mime.contains("\nX-Injected:"))
        // And the header that follows is still the real next header, not a fragment of the
        // smuggled text: a split here would silently drop the MIME structure.
        val lines = mime.lineSequence().toList()
        val at = lines.indexOfFirst { it.startsWith("Disposition-Notification-To:") }
        assertTrue("the receipt header must be there", at >= 0)
        assertEquals("MIME-Version: 1.0", lines[at + 1])
    }

    @Test
    fun theReceiptSitsAmongTheTopLevelHeadersBeforeMimeVersion() {
        val mime = OutgoingMime.build(msg(references = "<x@a>").copy(requestReceipt = true))
        val lines = mime.lineSequence().toList()
        val references = lines.indexOfFirst { it.startsWith("References:") }
        val receipt = lines.indexOfFirst { it.startsWith("Disposition-Notification-To:") }
        val mimeVersion = lines.indexOfFirst { it.startsWith("MIME-Version:") }
        assertTrue("References must come first", references in 0 until receipt)
        assertTrue("MIME-Version must come after", receipt < mimeVersion)
    }

    @Test
    fun aPgpMimeMessageStillCarriesTheReceiptHeader() {
        // The path a partial fix leaves mute. A signed or encrypted JMAP send does NOT go through
        // Email/set: MailRepository builds these very bytes and imports them. The pre-built entity
        // replaces the BODY, so the receipt has to be written above it — if it were emitted inside
        // the body branch, every signed and every encrypted message would go out asking nothing.
        val entity = "Content-Type: multipart/signed; protocol=\"application/pgp-signature\"\r\n\r\nbody\r\n"
        val mime = OutgoingMime.build(msg().copy(prebuiltEntity = entity, requestReceipt = true))

        assertEquals(
            "Disposition-Notification-To: alice@example.com",
            mime.lineSequence().first { it.startsWith("Disposition-Notification-To:") },
        )
        // The signed bytes themselves are untouched, byte for byte.
        assertTrue(
            // Verbatim apart from line endings: OutgoingMime.build normalises those to CRLF
            // and preserves the bare CR. This entity is already pure CRLF, so that is a no-op on
            // it and the check stays the strict one.
            "the entity must reach the wire verbatim, apart from its line endings",
            mime.endsWith(entity),
        )
    }

    /**
     * The seam is not a PGP seam. An entity carrying no PGP anywhere — RFC 8098's
     */
    @Test
    fun aPrebuiltEntityWithNoPgpAtAllReachesTheWireVerbatim() {
        val entity = "Content-Type: multipart/report; report-type=disposition-notification;\r\n" +
            " boundary=\"----sterna_mdn_1\"\r\n" +
            "\r\n" +
            "------sterna_mdn_1\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n" +
            "Content-Transfer-Encoding: base64\r\n" +
            "\r\n" +
            "RGlzcGxheWVkLg==\r\n" +
            "------sterna_mdn_1\r\n" +
            "Content-Type: message/disposition-notification\r\n" +
            "\r\n" +
            "Disposition: manual-action/MDN-sent-manually; displayed\r\n" +
            "------sterna_mdn_1--\r\n"

        val mime = OutgoingMime.build(msg().copy(prebuiltEntity = entity, requestReceipt = false))

        assertTrue(
            // Verbatim apart from line endings: OutgoingMime.build normalises those to CRLF
            // and preserves the bare CR. This entity is already pure CRLF, so that is a no-op on
            // it and the check stays the strict one.
            "the entity must reach the wire verbatim, apart from its line endings",
            mime.endsWith(entity),
        )
        assertEquals(
            "the message headers must stop at MIME-Version and the entity follow immediately: it " +
                "brings its own Content-Type, and a header written after it would land inside the " +
                "report rather than on the message",
            entity,
            mime.substringAfter("MIME-Version: 1.0\r\n"),
        )
        assertFalse(
            "the normal body construction must not run at all",
            mime.contains("Content-Type: text/plain; charset=utf-8\r\nContent-Transfer-Encoding: base64\r\n\r\naGVsbG8="),
        )
    }

    @Test
    fun fromWithEmailAsNameQuotedInBuiltMime() {
        val mime = OutgoingMime.build(
            msg().copy(from = OutgoingMime.formatAddress("alice@example.com", "alice@example.com")),
        )
        val fromLine = mime.lineSequence().first { it.startsWith("From:") }
        assertEquals("From: \"alice@example.com\" <alice@example.com>", fromLine)
    }

    // --- base64 line separators: CRLF, never a bare CR (RFC 5321 § 2.3.8) ---
    //
    // `Base64.getMimeEncoder()` already separates its 76-column lines with CRLF
    // (`getMimeEncoder()` == `getMimeEncoder(76, "\r\n")`). A `.replace("\n", "\r\n")` laid over
    //
    // The witness reads what `buildBodyEntity` PRODUCES. Never `FakeSmtpServer.delivered`: that
    // server re-reads DATA with `readLine()` and rejoins on "\r\n"
    // (FakeSmtpServer-179), which silently heals the defect — an assertion there is green
    // on the broken code.

    /** 193 bytes of plain ASCII: well past the 57-byte input that fills one 76-column line. */
    private val longBody =
        "The quick brown fox jumps over the lazy dog, and keeps on jumping until this " +
            "sentence is comfortably longer than fifty-seven bytes of plaintext, which is " +
            "where the MIME encoder starts wrapping."

    /**
     * The base64 payload of a part, cut out of the entity and split on CRLF. Splitting on CRLF
     */
    private fun payloadLines(entity: String, after: String): List<String> =
        entity.substringAfter(after).substringBefore("\r\n--").split("\r\n")

    /**
     * What every MIME base64 payload must look like, whatever part it came from.
     */
    private fun assertWellFormedMimeBase64(lines: List<String>, original: ByteArray) {
        assertTrue("the payload is wrapped onto several lines, so there IS a separator to get wrong", lines.size > 1)
        for ((i, line) in lines.withIndex()) {
            assertFalse("line $i carries a stray CR or LF: <$line>", line.contains("\r") || line.contains("\n"))
            assertTrue("line $i is empty", line.isNotEmpty())
            val expected = if (i == lines.lastIndex) "1..76" else "exactly 76"
            assertTrue(
                "line $i must be $expected columns, it is ${line.length}",
                if (i == lines.lastIndex) line.length in 1..76 else line.length == 76,
            )
        }
        // Round-trip: the lines really are this content, not something that merely looks tidy.
        assertArrayEquals(original, java.util.Base64.getMimeDecoder().decode(lines.joinToString("")))
    }

    @Test
    fun aWrappedTextBodyUsesCrlfWithNoBareCr() {
        // Guard the premise: this body really is past the 57-byte wrap threshold, so the
        // assertions below are looking at separators that exist.
        assertEquals("body is over the 57-byte wrap threshold", 193, longBody.toByteArray(Charsets.UTF_8).size)
        val entity = OutgoingMime.buildBodyEntity(msg().copy(body = longBody))
        assertFalse("no bare CR anywhere in the entity", entity.contains("\r\r"))
        assertWellFormedMimeBase64(
            payloadLines(entity, "Content-Transfer-Encoding: base64\r\n\r\n"),
            longBody.toByteArray(Charsets.UTF_8),
        )
    }

    @Test
    fun aWrappedAttachmentUsesCrlfWithNoBareCr() {
        // A few kB of fabricated bytes — many wrapped lines, and it exercises the attachment path
        // (appendAttachmentPart) rather than the body one.
        val blob = ByteArray(4096) { (it % 251).toByte() }
        val entity = OutgoingMime.buildBodyEntity(
            htmlMsg(listOf(OutgoingAttachment(name = "blob.bin", type = "application/octet-stream", bytes = blob))),
        )
        assertFalse("no bare CR anywhere in the entity", entity.contains("\r\r"))
        assertWellFormedMimeBase64(
            payloadLines(entity, "Content-Disposition: attachment; filename=\"blob.bin\"\r\n\r\n"),
            blob,
        )
    }

    @Test
    fun aWrappedInlineImageUsesCrlfWithNoBareCr() {
        // The fourth call site, appendRelatedParts: an inline image inside a multipart/related.
        // The `image` fixture above is 3 bytes — `AQID`, never wrapped — so it cannot see this
        // defect at all. Any message with a logo in it carries kilobytes here.
        val pixels = ByteArray(3000) { (it % 251).toByte() }
        val entity = OutgoingMime.buildBodyEntity(
            htmlMsg(
                listOf(
                    OutgoingAttachment(
                        name = "logo.png", type = "image/png", bytes = pixels,
                        cid = "logo@x", inline = true,
                    ),
                ),
            ),
        )
        assertFalse("no bare CR anywhere in the entity", entity.contains("\r\r"))
        assertWellFormedMimeBase64(
            payloadLines(entity, "Content-Disposition: inline; filename=\"logo.png\"\r\n\r\n"),
            pixels,
        )
    }

    @Test
    fun aBodyBelowTheWrapThresholdIsUnchanged() {
        // 31 bytes: under the 57-byte threshold, so the encoder emits a single unwrapped line and
        // there is no separator to mangle. Green on both sides of the fix — this is the boundary,
        // it proves nothing about the fix and everything about not regressing short messages.
        val shortBody = "short enough to fit on one line"
        assertEquals(31, shortBody.toByteArray(Charsets.UTF_8).size)
        val entity = OutgoingMime.buildBodyEntity(msg().copy(body = shortBody))
        assertFalse("no bare CR", entity.contains("\r\r"))
        assertEquals(
            "a body under the threshold is one unwrapped base64 line",
            java.util.Base64.getEncoder().encodeToString(shortBody.toByteArray(Charsets.UTF_8)),
            entity.substringAfter("Content-Transfer-Encoding: base64\r\n\r\n"),
        )
    }
}
