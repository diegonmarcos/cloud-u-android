package app.sterna.core.data.mail

import app.sterna.core.data.account.StoredIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * The bytes of an RFC 8098 read receipt, and the outbox row that carries it.
 */
class ReadReceiptMailTest {

    // ---- 1. the bytes ------------------------------------------------------------------------

    /**
     * The whole entity, pinned as one literal.
     */
    @Test fun `the entity is a multipart report with a human part and a machine part`() {
        val entity = readReceiptEntity(
            boundary = "----sterna_mdn_deadbeef",
            humanText = "Displayed.",
            finalRecipient = "ann@example.org",
            originalMessageId = "abc123@sender.example",
        )

        assertEquals(
            "Content-Type: multipart/report; report-type=disposition-notification;\r\n" +
                " boundary=\"----sterna_mdn_deadbeef\"\r\n" +
                "\r\n" +
                "------sterna_mdn_deadbeef\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Content-Transfer-Encoding: base64\r\n" +
                "\r\n" +
                "RGlzcGxheWVkLg==\r\n" +
                "------sterna_mdn_deadbeef\r\n" +
                "Content-Type: message/disposition-notification\r\n" +
                "\r\n" +
                "Reporting-UA: Sterna Mail\r\n" +
                "Final-Recipient: rfc822; ann@example.org\r\n" +
                "Original-Message-ID: <abc123@sender.example>\r\n" +
                "Disposition: manual-action/MDN-sent-manually; displayed\r\n" +
                "------sterna_mdn_deadbeef--\r\n",
            entity,
        )
    }

    /**
     * `Reporting-UA` names the product and NOTHING else. The classic form of the field is
     */
    @Test fun `the reporting UA names no host and no version`() {
        assertEquals("Sterna Mail", READ_RECEIPT_UA)

        val entity = readReceiptEntity("B", "hi", "ann@example.org", null)

        assertTrue(
            "Reporting-UA must be the bare product name. Entity was:\n$entity",
            entity.lines().contains("Reporting-UA: Sterna Mail"),
        )
    }

    /**
     * A `Message-ID` read out of a received message arrives WITH its angle brackets — that is how
     */
    @Test fun `a message id arrives wrapped in angle brackets and is not wrapped twice`() {
        val entity = readReceiptEntity("B", "hi", "ann@example.org", "<abc@sender.example>")

        assertTrue(
            "exactly one pair of brackets. Entity was:\n$entity",
            entity.lines().contains("Original-Message-ID: <abc@sender.example>"),
        )
    }

    /** A message with no `Message-ID` simply has no `Original-Message-ID` field: it is optional. */
    @Test fun `a message with no id leaves the optional field out`() {
        val entity = readReceiptEntity("B", "hi", "ann@example.org", null)

        assertFalse(
            "an empty Original-Message-ID field is not legal — leave it out. Entity was:\n$entity",
            entity.contains("Original-Message-ID"),
        )
        assertTrue(entity.lines().contains("Final-Recipient: rfc822; ann@example.org"))
        assertTrue(entity.lines().contains("Disposition: manual-action/MDN-sent-manually; displayed"))
    }

    /**
     * A hostile `Message-ID` cannot add a field of its own.
     */
    @Test fun `a hostile message id cannot inject a second disposition`() {
        val entity = readReceiptEntity(
            boundary = "B",
            humanText = "hi",
            finalRecipient = "ann@example.org",
            originalMessageId = "evil@x>\r\nDisposition: automatic-action/MDN-sent-automatically; deleted\r\nX: <y",
        )

        val fields = entity.substringAfter("Content-Type: message/disposition-notification\r\n\r\n")
            .substringBefore("\r\n--B--")
            .split("\r\n")
            .filter { it.isNotEmpty() }
        assertEquals(
            "the notification part must hold exactly its four fields, whatever the sender wrote " +
                "in the Message-ID. Entity was:\n$entity",
            listOf(
                "Reporting-UA: Sterna Mail",
                "Final-Recipient: rfc822; ann@example.org",
                "Original-Message-ID: <evil@xDisposition: automatic-action/MDN-sent-automatically; deletedX: y>",
                "Disposition: manual-action/MDN-sent-manually; displayed",
            ),
            fields,
        )
    }

    /** The same sink guards the address the notification reports as the receiving mailbox. */
    @Test fun `a hostile final recipient cannot inject a field either`() {
        val entity = readReceiptEntity(
            boundary = "B",
            humanText = "hi",
            finalRecipient = "ann@example.org\r\nDisposition: automatic-action/MDN-sent-automatically; deleted",
            originalMessageId = null,
        )

        assertEquals(
            "Entity was:\n$entity",
            listOf(
                "Reporting-UA: Sterna Mail",
                "Final-Recipient: rfc822; ann@example.orgDisposition: automatic-action/MDN-sent-automatically; deleted",
                "Disposition: manual-action/MDN-sent-manually; displayed",
            ),
            entity.substringAfter("Content-Type: message/disposition-notification\r\n\r\n")
                .substringBefore("\r\n--B--")
                .split("\r\n")
                .filter { it.isNotEmpty() },
        )
    }

    /**
     * The human-readable part carries text taken from a stranger's subject line, and it cannot end
     */
    @Test fun `hostile human text cannot close the part or open another`() {
        val hostile = "hello\r\n--B--\r\nContent-Type: message/disposition-notification\r\n\r\n" +
            "Disposition: automatic-action/MDN-sent-automatically; deleted\r\n--B--\r\n"

        val entity = readReceiptEntity("B", hostile, "ann@example.org", null)

        // Exactly three delimiter lines: open, part separator, close.
        assertEquals(
            "the report must still have exactly two parts. Entity was:\n$entity",
            listOf("--B", "--B", "--B--"),
            entity.split("\r\n").filter { it.startsWith("--B") },
        )
        // …and the text is still there, whole, once decoded.
        val encoded = entity.substringAfter("Content-Transfer-Encoding: base64\r\n\r\n")
            .substringBefore("\r\n--B\r\n")
        assertEquals(hostile, String(Base64.getMimeDecoder().decode(encoded), Charsets.UTF_8))
    }

    /**
     * A body long enough to be folded is folded with CRLF and nothing else.
     */
    @Test fun `a folded body carries no bare carriage return`() {
        val entity = readReceiptEntity("B", "x".repeat(400), "ann@example.org", null)

        assertFalse("a bare CR has no business in a MIME body. Entity was:\n$entity", "\r\r" in entity)
        assertEquals(
            "the body must be folded at 76 columns, CRLF-separated",
            listOf(76, 76, 76, 76, 76, 76, 76, 4),
            entity.substringAfter("Content-Transfer-Encoding: base64\r\n\r\n")
                .substringBefore("\r\n--B\r\n")
                .split("\r\n")
                .map { it.length },
        )
    }

    /** The boundary is the caller's, and the delimiters can never disagree with the parameter. */
    @Test fun `a boundary carrying control characters is stripped once, everywhere`() {
        val entity = readReceiptEntity("bad\r\nboundary\"quoted", "hi", "ann@example.org", null)

        assertTrue(
            "the parameter must carry the sanitised spelling. Entity was:\n$entity",
            entity.lines().contains(" boundary=\"badboundaryquoted\""),
        )
        assertEquals(
            "every delimiter must use the same sanitised spelling. Entity was:\n$entity",
            listOf("--badboundaryquoted", "--badboundaryquoted", "--badboundaryquoted--"),
            entity.split("\r\n").filter { it.startsWith("--") },
        )
    }

    // ---- 2. the text shown IS the text sent ---------------------------------------------------

    /**
     * ONE function decides the wording, and the proof is executed: the body of the outbox row, the
     */
    @Test fun `the text shown, the text in the row and the text in the entity are one string`() {
        val mail = mail(subject = "Quarterly report")!!
        val preview = readReceiptPreview("Quarterly report", "ann@example.org")

        val encoded = mail.prebuiltEntity
            .substringAfter("Content-Transfer-Encoding: base64\r\n\r\n")
            .substringBefore("\r\n--B\r\n")
        val inTheEntity = String(Base64.getMimeDecoder().decode(encoded), Charsets.UTF_8)

        assertEquals(preview.body, mail.body)
        assertEquals(preview.body, inTheEntity)
        assertEquals(preview.subject, mail.subject)
    }

    /** The wording itself, pinned — fixed English, and it promises only what an MDN can keep. */
    @Test fun `the wording is fixed english and claims only that the message was displayed`() {
        val preview = readReceiptPreview("Quarterly report", "ann@example.org")

        assertEquals("Read receipt: Quarterly report", preview.subject)
        assertEquals(
            "This is a read receipt.\n" +
                "\n" +
                "The message you sent to ann@example.org with the subject \"Quarterly report\" " +
                "was displayed.\n" +
                "\n" +
                "It says the message was shown on the recipient's device. It does not say it was read.",
            preview.body,
        )
    }

    /** A message with no subject still gets a subject line, and a sentence that reads. */
    @Test fun `an untitled message falls back to the bare subject`() {
        val preview = readReceiptPreview("   ", "ann@example.org")

        assertEquals("Read receipt", READ_RECEIPT_SUBJECT)
        assertEquals(READ_RECEIPT_SUBJECT, preview.subject)
        assertEquals(
            "This is a read receipt.\n" +
                "\n" +
                "The message you sent to ann@example.org was displayed.\n" +
                "\n" +
                "It says the message was shown on the recipient's device. It does not say it was read.",
            preview.body,
        )
    }

    /**
     * NONE of this is translated, and none of it may become so.
     */
    @Test fun `none of the receipt wording is a translatable resource`() {
        val strings = locate("app/src/main/res/values/strings.xml").readText()
        val preview = readReceiptPreview("Quarterly report", "ann@example.org")

        listOf(
            READ_RECEIPT_SUBJECT,
            "This is a read receipt.",
            "It says the message was shown on the recipient's device. It does not say it was read.",
        ).forEach { fixed ->
            assertFalse(
                "'$fixed' must NOT be in strings.xml: what leaves the device is fixed English, " +
                    "what the reader sees is what is translated",
                fixed in strings,
            )
        }
        // READ_RECEIPT_UA is deliberately NOT on that list: "Sterna Mail" is also the app's own
        // label, `app_name`, and it is legitimately translated THERE. What matters is that the
        // receipt does not read it from there — hence the source check below, and the fact that
        // this module cannot see `R` at all.
        val source = locate("core/data/src/main/kotlin/app/sterna/core/data/mail/ReadReceiptMail.kt").readText()
        listOf("R.string", "getString", "Locale", "stringResource").forEach { forbidden ->
            assertFalse(
                "the receipt must not reach for '$forbidden': a correspondent's client does not " +
                    "speak the reader's language, and the sentence it parses must not change with it",
                forbidden in source,
            )
        }
        assertTrue(
            "the receipt must be plain ASCII for an ASCII message: no localised wording slipped in",
            (preview.subject + preview.body).all { it.code in 10..126 },
        )
    }

    // ---- 3. the outbox row --------------------------------------------------------------------

    /** The receipt goes to the addresses the sender named, in the order they were named. */
    @Test fun `the receipt goes where the header asked`() {
        val mail = mail(receiptTo = listOf(" ann@example.org ", "notify@example.org"))!!

        assertEquals(listOf("ann@example.org", "notify@example.org"), mail.to)
    }

    /**
     * The account's own identity travels with the receipt (issue #31): a delegated sub-account is
     */
    @Test fun `the account identity is carried, and is what the notification reports`() {
        val mail = readReceiptMail(
            receiptTo = listOf("ann@example.org"),
            originalSubject = "Quarterly report",
            originalMessageId = "abc@sender.example",
            identityName = "Team Sales",
            identityEmail = "sales@masto.top",
            deliveredTo = "sales@masto.top",
            accountAddress = "login@masto.top",
            boundary = "B",
        )!!

        assertEquals("Team Sales", mail.fromName)
        assertEquals("sales@masto.top", mail.fromEmail)
        assertTrue(
            "the delegated address, not the login, is the mailbox that received. Entity was:\n" +
                mail.prebuiltEntity,
            mail.prebuiltEntity.lines().contains("Final-Recipient: rfc822; sales@masto.top"),
        )
    }

    /**
     * THE ADDRESS THAT RECEIVED wins — it is a parameter, not a guess.
     */
    @Test fun `the address the message arrived at is what the receipt reports`() {
        // The three addresses are deliberately DIFFERENT. An alias that received without having
        // an identity of its own is the only shape that can tell the three apart: with
        // deliveredTo == identityEmail, dropping deliveredTo from the rule changes nothing and the
        // test proves nothing about it.
        val mail = readReceiptMail(
            receiptTo = listOf("sender@example.org"),
            originalSubject = "Quarterly report",
            originalMessageId = null,
            identityName = "Ann Lee",
            identityEmail = "ann@masto.top",
            deliveredTo = "ann+work@masto.top",
            accountAddress = "login@masto.top",
            boundary = "B",
        )!!

        assertTrue(
            "the mailbox that RECEIVED, not the identity we send under nor the login. Entity was:\n" +
                mail.prebuiltEntity,
            mail.prebuiltEntity.lines().contains("Final-Recipient: rfc822; ann+work@masto.top"),
        )
        assertEquals(
            "…and the sentence the correspondent reads names the same one",
            "The message you sent to ann+work@masto.top with the subject \"Quarterly report\" was displayed.",
            mail.body.lines()[2],
        )
        assertEquals(
            "the From stays an address this account can actually send as",
            "ann@masto.top",
            mail.fromEmail,
        )
    }

    /**
     * When the caller does not know which address received, the identity's own answers, then the
     */
    @Test fun `an unknown delivery address falls back to the identity, then to the login`() {
        val viaIdentity = readReceiptMail(
            receiptTo = listOf("sender@example.org"), originalSubject = null, originalMessageId = null,
            identityName = null, identityEmail = "ann@masto.top", deliveredTo = null,
            accountAddress = "login@masto.top", boundary = "B",
        )!!
        val viaLogin = readReceiptMail(
            receiptTo = listOf("sender@example.org"), originalSubject = null, originalMessageId = null,
            identityName = null, identityEmail = null, deliveredTo = null,
            accountAddress = "login@masto.top", boundary = "B",
        )!!

        assertTrue(
            "Entity was:\n" + viaIdentity.prebuiltEntity,
            viaIdentity.prebuiltEntity.lines().contains("Final-Recipient: rfc822; ann@masto.top"),
        )
        assertNull(viaLogin.fromEmail)
        assertTrue(
            "Entity was:\n" + viaLogin.prebuiltEntity,
            viaLogin.prebuiltEntity.lines().contains("Final-Recipient: rfc822; login@masto.top"),
        )
    }

    /**
     * The identity is CHOSEN by the address that received, not taken as the first one stored.
     */
    @Test fun `the identity is the one whose address received the message`() {
        val identities = listOf(
            StoredIdentity(id = "1", name = "Ann Lee", email = "ann@masto.top"),
            StoredIdentity(id = "2", name = "Ann at Work", email = "ann+work@masto.top"),
        )

        assertEquals("2", receiptIdentity(identities, "ann+work@masto.top")?.id)
        assertEquals(
            "an address is case-insensitive, and a sender who typed it in capitals wrote to the " +
                "same mailbox",
            "2",
            receiptIdentity(identities, "Ann+Work@Masto.TOP")?.id,
        )
        assertEquals("unknown to us: the default answers", "1", receiptIdentity(identities, "someone@else.org")?.id)
        assertEquals("not known at all: the default answers", "1", receiptIdentity(identities, null)?.id)
        assertNull("an account with no identity invents none", receiptIdentity(emptyList(), "ann@masto.top"))
    }

    /** No identity known: nothing is invented, and the account's own address is reported. */
    @Test fun `with no identity the account address is what is reported`() {
        val mail = readReceiptMail(
            receiptTo = listOf("ann@example.org"),
            originalSubject = null,
            originalMessageId = null,
            identityName = null,
            identityEmail = null,
            deliveredTo = null,
            accountAddress = "login@masto.top",
            boundary = "B",
        )!!

        assertNull(mail.fromName)
        assertNull(mail.fromEmail)
        assertTrue(
            "Entity was:\n" + mail.prebuiltEntity,
            mail.prebuiltEntity.lines().contains("Final-Recipient: rfc822; login@masto.top"),
        )
    }

    /**
     * What `sendReadReceipt` hands back to the banner is what it queued, read off the row.
     */
    @Test fun `the row hands its own strings back for the banner`() {
        val mail = mail()!!

        assertEquals(ReadReceiptPreview(subject = mail.subject, body = mail.body), mail.preview())
        assertEquals(readReceiptPreview("Quarterly report", "ann@example.org"), mail.preview())
    }

    /**
     * The sender is NOT learned into the contact suggestions.
     */
    @Test fun `a receipt teaches nothing to the contact suggestions`() {
        assertEquals(false, mail()!!.learnRecipients)
        assertEquals(emptyList<String>(), recipientsToLearn(listOf("ann@example.org"), learn = false))
        // …and the witness: every other path still learns.
        assertEquals(listOf("bob@example.org"), recipientsToLearn(listOf("bob@example.org"), learn = true))
    }

    // ---- 4. nothing at all to answer ----------------------------------------------------------

    /**
     * An empty address list must produce NOTHING, and must not throw.
     */
    @Test fun `nothing to answer builds nothing and throws nothing`() {
        assertNull(mail(receiptTo = emptyList()))
        assertNull(mail(receiptTo = listOf("  ", "")))
        assertNull(mail(receiptTo = ReadReceiptHeader.recipients(null)))
        assertNull(mail(receiptTo = ReadReceiptHeader.recipients("not an address")))
        assertNull(mail(receiptTo = ReadReceiptHeader.recipients("<>")))
    }

    // ---- 5. the hand-over to the outbox, read out of the shipped source: a LAST RESORT ---------

    /**
     * SOURCE LINT, NOT A BEHAVIOUR TEST. It reads `MailRepository.kt` as text and proves nothing
     */
    @Test fun `the repository hands each field to the argument of the same name`() {
        val lines = sendReadReceiptBody().lines().map { it.trim() }

        assertTrue(
            "the receipt must be built by readReceiptMail from the header's addresses and the " +
                "account's own identity, not assembled at the call site. Body was:\n" +
                sendReadReceiptBody(),
            "val mail = readReceiptMail(" in lines,
        )
        // …and WHAT it is called with, line by line. Checking only that the call exists leaves
        // three edits that compile, keep everything green, and ship a broken receipt every time:
        listOf(
            "receiptTo = receiptTo,",
            "originalSubject = originalSubject,",
            "originalMessageId = originalMessageId,",
            "identityName = identity?.name,",
            "identityEmail = identity?.email,",
            "deliveredTo = deliveredTo,",
            "accountAddress = credentials.username,",
        ).forEach { argument ->
            assertTrue(
                "readReceiptMail must be given the whole line '$argument'. Two String? arguments " +
                    "swapped compiles and stays green. Body was:\n" + sendReadReceiptBody(),
                argument in lines,
            )
        }
        listOf(
            "to = mail.to,",
            "subject = mail.subject,",
            "body = mail.body,",
            "fromName = mail.fromName,",
            "fromEmail = mail.fromEmail,",
            "prebuiltEntity = mail.prebuiltEntity,",
            "learnRecipients = mail.learnRecipients,",
        ).forEach { argument ->
            assertTrue(
                "enqueueSend must be given the whole line '$argument'. Two of these swapped, or " +
                    "one of them missing, is a message nobody can call back. Body was:\n" +
                    sendReadReceiptBody(),
                argument in lines,
            )
        }
    }

    /**
     * SOURCE LINT, its own test — deliberately not folded into the one above. A failed assertion
     */
    @Test fun `the boundary is drawn fresh for each receipt`() {
        val lines = sendReadReceiptBody().lines().map { it.trim() }

        assertTrue(
            "Body was:\n" + sendReadReceiptBody(),
            "boundary = \"----sterna_mdn_\${java.util.UUID.randomUUID().toString().replace(\"-\", \"\")}\"," in lines,
        )
    }

    /** SOURCE LINT, its own test. The identity is chosen, not taken as the first one stored. */
    @Test fun `the identity is chosen from the address that received`() {
        val lines = sendReadReceiptBody().lines().map { it.trim() }

        assertTrue(
            "Body was:\n" + sendReadReceiptBody(),
            "val identity = receiptIdentity(accountStore.identities(credentials.id), deliveredTo)" in lines,
        )
    }

    /**
     * SOURCE LINT, its own test. What was queued is handed back, so the banner shows those strings
     * instead of deriving "the address that received" a second time and drifting from them.
     */
    @Test fun `what was queued is handed back for the banner`() {
        val lines = sendReadReceiptBody().lines().map { it.trim() }

        assertTrue("Body was:\n" + sendReadReceiptBody(), "return mail.preview()" in lines)
    }

    /**
     * SOURCE LINT, its own test. The empty-list guard is `?: return null` on the builder's result —
     */
    @Test fun `nothing to answer returns before the outbox is touched`() {
        val lines = sendReadReceiptBody().lines().map { it.trim() }

        assertTrue(
            "the builder's null must end the function, before enqueueSend. Body was:\n" +
                sendReadReceiptBody(),
            ") ?: return null" in lines,
        )
        assertTrue(
            "the guard must come BEFORE the send. Body was:\n" + sendReadReceiptBody(),
            lines.indexOf(") ?: return null") < lines.indexOf("enqueueSend("),
        )
    }

    // ---- helpers ------------------------------------------------------------------------------

    private fun mail(
        receiptTo: List<String> = listOf("ann@example.org"),
        subject: String? = "Quarterly report",
    ) = readReceiptMail(
        receiptTo = receiptTo,
        originalSubject = subject,
        originalMessageId = "abc@sender.example",
        identityName = null,
        identityEmail = "ann@example.org",
        deliveredTo = "ann@example.org",
        accountAddress = "ann@example.org",
        boundary = "B",
    )

    /** The body of `MailRepository.sendReadReceipt`, from its signature to the next block. */
    private fun sendReadReceiptBody(): String {
        val source = locate("core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt").readText()
        val start = source.indexOf("suspend fun sendReadReceipt(")
        check(start >= 0) { "MailRepository no longer declares sendReadReceipt(...)" }
        val end = listOf("\n    // ----", "\n    /**")
            .mapNotNull { source.indexOf(it, start).takeIf { at -> at > start } }
            .minOrNull()
        check(end != null) { "nothing follows sendReadReceipt — the slice would be the whole file" }
        return source.substring(start, end)
    }

    /** [relative] resolved from the test's working directory, walking up. */
    private fun locate(relative: String): File {
        val fromModule = relative.substringAfter("core/data/")
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            File(dir, relative).takeIf { it.isFile }?.let { return it }
            File(dir, fromModule).takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        error("Cannot find $relative from ${System.getProperty("user.dir")}")
    }
}
