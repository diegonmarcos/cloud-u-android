package app.sterna.core.data.mail

import app.sterna.core.data.db.EmailRecipients
import app.sterna.core.jmap.model.EmailAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Saving a draft from the device must not blank the people in copy.
 */
class SavedDraftRowCopiesTest {

    private val alex = EmailAddress(name = "Alex", email = "alex@example.org")
    private val bob = EmailAddress(email = "bob@example.org")
    private val carol = EmailAddress(email = "carol@example.org")
    private val dave = EmailAddress(email = "dave@example.org")
    private val erin = EmailAddress(name = "Erin", email = "erin@example.org")
    private val frank = EmailAddress(email = "frank@example.org")
    private val gina = EmailAddress(email = "gina@example.org")

    private fun row(
        to: List<EmailAddress> = listOf(bob),
        cc: List<EmailAddress> = listOf(carol),
        bcc: List<EmailAddress> = listOf(dave),
    ) = savedDraftRow(
        accountId = "accA",
        emailId = "draft-1",
        draftMailboxId = "drafts",
        from = alex,
        to = to,
        cc = cc,
        bcc = bcc,
        subject = "Six o'clock",
        body = "See you there.",
        inReplyTo = emptyList(),
        references = emptyList(),
        hasAttachment = false,
    )

    // --- the mapping, executed ----------------------------------------------------------------------

    @Test fun theCachedDraftRowCarriesTheCopiesItWasSavedWith() {
        val entity = row()

        assertEquals("carol@example.org", EmailRecipients.decode(entity.ccJson).single().email)
        assertEquals("dave@example.org", EmailRecipients.decode(entity.bccJson).single().email)
        // …and the To is still the To: the three columns hold three different lists.
        assertEquals("bob@example.org", EmailRecipients.decode(entity.recipientsJson).single().email)
    }

    @Test fun theRowReadsBackAsTheDraftTheComposerWillReopen() {
        val reopened = row().toEmail()

        assertEquals(listOf("bob@example.org"), reopened.to.map { it.email })
        assertEquals(listOf("carol@example.org"), reopened.cc.map { it.email })
        assertEquals(listOf("dave@example.org"), reopened.bcc.map { it.email })
    }

    @Test fun aDraftWithNobodyInCopyStoresNoCopies() {
        val entity = row(cc = emptyList(), bcc = emptyList())

        assertNull(entity.ccJson)
        assertNull(entity.bccJson)
        assertEquals(listOf("bob@example.org"), entity.toEmail().to.map { it.email })
    }

    /**
     * MORE THAN ONE PERSON IN COPY, and all of them stored, in the order they were typed.
     */
    @Test fun aDraftKeepsEveryCopiedParty() {
        val entity = row(cc = listOf(carol, erin, frank), bcc = listOf(dave, gina))

        assertEquals(
            listOf("carol@example.org", "erin@example.org", "frank@example.org"),
            EmailRecipients.decode(entity.ccJson).map { it.email },
        )
        assertEquals(listOf(null, "Erin", null), EmailRecipients.decode(entity.ccJson).map { it.name })
        assertEquals(
            listOf("dave@example.org", "gina@example.org"),
            EmailRecipients.decode(entity.bccJson).map { it.email },
        )
        // …and read back, which is what the composer reopens.
        val reopened = entity.toEmail()
        assertEquals(
            listOf("carol@example.org", "erin@example.org", "frank@example.org"),
            reopened.cc.map { it.email },
        )
        assertEquals(listOf("dave@example.org", "gina@example.org"), reopened.bcc.map { it.email })
    }

    @Test fun aDraftBlindCopiedToOneAddressKeepsItOutOfTheVisibleCc() {
        val entity = row(cc = emptyList(), bcc = listOf(dave))

        assertNull("nobody was in visible copy; the Cc column must stay NULL", entity.ccJson)
        assertEquals(listOf("dave@example.org"), entity.toEmail().bcc.map { it.email })
        assertEquals(emptyList<String>(), entity.toEmail().cc.map { it.email })
    }

    // --- the two argument hops, pinned against the shipped source ------------------------------------

    /**
     * SOURCE LINT. The executed tests above prove the mapping; they cannot prove the caller hands
     */
    @Test fun `the upload hands the trimmed copies to the cache write`() {
        // Moved by #95: the two protocol branches live in `uploadDraft`, which `saveDraft` runs
        // only after the draft is durable on the phone. The copies now come off the row.
        val body = DaoQuerySource.mailFunctionBody("MailRepository", "uploadDraft")
            .lines().map { it.trim() }.filter { it.isNotEmpty() }
        val at = body.indexOfFirst { it.startsWith("cacheSavedDraft(") }

        assertTrue(
            "MailRepository.saveDraft no longer calls cacheSavedDraft( — either the optimistic " +
                "Drafts row is gone, or this lint must follow it",
            at >= 0,
        )
        assertEquals(
            "the arguments of the optimistic Drafts-row write changed. `ccTrimmed`/`bccTrimmed` " +
                "are the addresses that were just sent to the server; dropping either one puts " +
                "NULL into the column and empties a reopened draft's copies:",
            listOf(
                "cacheSavedDraft(",
                "credentials, savedId, draftsId, from, recipients,",
                "cc = ccTrimmed.map { EmailAddress(email = it) },",
                "bcc = bccTrimmed.map { EmailAddress(email = it) },",
                "row.subject, row.textBody,",
                "inReplyTo, references, hasAttachment = blobs.isNotEmpty(),",
                ")",
            ),
            body.drop(at).take(7),
        )
        // …and WHAT those two names hold, which the block above cannot say. `val ccTrimmed =
        // emptyList<String>()` leaves every argument line above untouched, and the Cc then never
        // even reaches the server: the call site is pinned, so pin the definitions too, whole
        // lines, selected by name so a rename fails here rather than silently matching nothing.
        assertEquals(
            "the Cc/Bcc the save is built from are no longer the composer's trimmed addresses:",
            listOf(
                "val ccTrimmed = localDraftAddresses(row.cc)",
                "val bccTrimmed = localDraftAddresses(row.bcc)",
            ),
            body.filter { it.startsWith("val ccTrimmed") || it.startsWith("val bccTrimmed") },
        )
    }

    /**
     * The second hop: what `cacheSavedDraft` forwards to the row builder it upserts.
     */
    @Test fun `the cache write forwards both copy fields to the row builder`() {
        val body = DaoQuerySource.mailFunctionBody("MailRepository", "cacheSavedDraft")
            .lines().map { it.trim() }.filter { it.isNotEmpty() }
        val at = body.indexOfFirst { it.startsWith("emailDao.upsertAll(") }

        assertTrue(
            "cacheSavedDraft no longer writes its row with emailDao.upsertAll( — the executed " +
                "tests above then cover a mapping the app does not run:\n" + body.joinToString("\n"),
            at >= 0,
        )
        assertEquals(
            "the row cacheSavedDraft upserts is no longer built, whole and unaltered, from the " +
                "addresses it was handed. Anything wrapped around savedDraftRow(…) writes NULL " +
                "columns over what the sync stored, and reopening the draft loses the copies:",
            listOf(
                "emailDao.upsertAll(",
                "listOf(",
                "savedDraftRow(",
                "accountId = credentials.id,",
                "emailId = emailId,",
                "draftMailboxId = draftMailboxId,",
                "from = from,",
                "to = to,",
                "cc = cc,",
                "bcc = bcc,",
                "subject = subject,",
                "body = body,",
                "inReplyTo = inReplyTo,",
                "references = references,",
                "hasAttachment = hasAttachment,",
                "),",
                "),",
                ")",
            ),
            body.drop(at).take(18),
        )
    }
}
