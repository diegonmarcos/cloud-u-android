package app.sterna.send

import app.sterna.core.data.mail.MailRepository
import app.sterna.core.data.text.Block
import app.sterna.core.data.text.BlockKind
import app.sterna.core.data.text.Inline
import app.sterna.core.data.text.Link
import app.sterna.core.data.text.Span
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The undo-send contract the "Annuler" affordance relies on: undoing must both run the drop action
 */
class SendOutboxTest {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Unconfined)
    private val outbox = SendOutbox(scope)

    @After fun tearDown() = job.cancel()

    private fun draft(subject: String = "hi") = SendOutbox.ComposeDraft(
        to = "a@b.c", cc = "", bcc = "", subject = subject, body = "body",
        fromAccountId = "acc", fromIdentityEmail = "me@b.c",
        attachments = emptyList(), inReplyTo = emptyList(), references = emptyList(),
        // This message was edited from a saved draft, and it carries the numbering that draft's id
        // was read under (#99). Stated rather than defaulted: the field has no default precisely so
        // that a construction site cannot leave the freeze behind without the compiler saying so.
        draftEmailId = "d1", draftUidValidity = 42L,
        draftBodyIsLossy = false,
    )

    @Test fun `hold surfaces a pending affordance and no restored draft yet`() {
        outbox.hold("Message sent", draft()) {}
        assertTrue(outbox.pending.value != null)
        assertNull(outbox.restored.value)
    }

    @Test fun `undo clears pending, runs the drop action, and hands the draft back`() {
        val held = draft("undo me")
        var dropped = false
        outbox.hold("Waiting to send when connected", held) { dropped = true }

        outbox.undo()

        assertNull("the affordance is gone once undone", outbox.pending.value)
        assertTrue("the queued row's drop action runs", dropped)
        assertSame("the exact draft is handed back to reopen compose", held, outbox.restored.value)
    }

    @Test fun `consumeRestored clears the handed-back draft`() {
        outbox.hold("Message sent", draft()) {}
        outbox.undo()
        assertTrue(outbox.restored.value != null)

        outbox.consumeRestored()

        assertNull(outbox.restored.value)
    }

    @Test fun `undo with nothing held is a no-op`() {
        outbox.undo()
        assertNull(outbox.pending.value)
        assertNull(outbox.restored.value)
    }

    @Test fun `reopen stages a draft directly for editing a queued item`() {
        val d = draft("edit queued")
        outbox.reopen(d)
        assertSame(d, outbox.restored.value)
    }

    // ---- the fidelity verdict of an edited draft travels WITH its id ---------------------------

    /**
     * The undo round trip must hand the verdict back exactly as it went in, both senses: a lossy
     */
    @Test fun `undoing a send brings the fidelity verdict back unchanged`() {
        for (lossy in listOf(true, false)) {
            val held = draft("verdict").copy(draftEmailId = "d1", draftBodyIsLossy = lossy)
            outbox.hold("Sending…", held) {}
            outbox.undo()
            assertEquals(lossy, outbox.restored.value!!.draftBodyIsLossy)
            outbox.consumeRestored()
        }
    }

    // --- the styling a queued message was written with (#131) -----------------------------------

    private fun queued(htmlBody: String?, body: String = "see you there") =
        MailRepository.OutboxDraft(
            to = "bob@example.org", cc = "", bcc = "",
            subject = "Six o'clock", body = body, htmlBody = htmlBody,
            fromAccountId = "accA", fromEmail = "alex@masto.top",
            attachments = emptyList(), inReplyTo = emptyList(), references = emptyList(),
            pgpMode = null, draftEmailId = null, draftUidValidity = null,
            outboxId = 42, requestReceipt = false,
        )

    @Test fun `a queued message this app styled reopens with its styling`() {
        // Re-editing a queued message enqueues a NEW row from the composer's state: styling
        // dropped in this hand-over is not merely absent from the screen, it is REMOVED from the
        // message that finally goes out.
        val reopened = composeDraftOf(queued(htmlBody = "<b>see</b> you there"))

        assertEquals("see you there", reopened.body)
        assertEquals(mapOf(Inline.BOLD to listOf(Span(0, 3))), reopened.bodyRanges)
    }

    @Test fun `a queued message hands over the body and the spans of ONE part`() {
        // The witness the test above cannot be: there the row's two columns say the same words,
        // so `body = draft.body` — the text column — passes it. Here they DIFFER, which a row is
        val reopened = composeDraftOf(queued(htmlBody = "<b>see</b> you there", body = "ok"))

        assertEquals(
            "the html is what was READ, so its text is what the composer opens on",
            "see you there",
            reopened.body,
        )
        assertEquals(mapOf(Inline.BOLD to listOf(Span(0, 3))), reopened.bodyRanges)
    }

    @Test fun `a queued message that held a list reopens with it`() {
        // Re-editing enqueues a NEW row from the composer's state: a list dropped in this
        // hand-over is not merely missing from the screen, it is REMOVED from what finally goes out.
        val reopened = composeDraftOf(
            queued(htmlBody = "<ul><li>see</li><li>you there</li></ul>", body = "- see\n- you there"),
        )

        assertEquals("see\nyou there", reopened.body)
        assertEquals(listOf(Block(BlockKind.BULLET, 0..1)), reopened.bodyBlocks)
    }

    /**
     * ROUTE 2 OF 4 — the QUEUED outbox row, reopened by Outbox → Edit.
     */
    @Test fun `a queued message this app linked reopens with its link`() {
        val reopened = composeDraftOf(queued(htmlBody = """see <a href="https://x">you</a> there"""))

        assertEquals("see you there", reopened.body)
        assertEquals(listOf(Link(Span(4, 7), "https://x")), reopened.bodyLinks)
    }

    /** ROUTE 2 OF 4, a list AND a link on one row: both halves survive the hand-over. */
    @Test fun `a queued message holding a list and a link reopens with both`() {
        val reopened = composeDraftOf(
            queued(
                htmlBody = """<ul><li>see</li><li>you <a href="https://x">there</a></li></ul>""",
                body = "- see\n- you there",
            ),
        )

        assertEquals("see\nyou there", reopened.body)
        assertEquals(listOf(Block(BlockKind.BULLET, 0..1)), reopened.bodyBlocks)
        assertEquals(listOf(Link(Span(8, 13), "https://x")), reopened.bodyLinks)
    }

    @Test fun `a queued message with no html reopens plain, exactly as before`() {
        val reopened = composeDraftOf(queued(htmlBody = null))

        assertEquals("see you there", reopened.body)
        assertEquals(emptyMap<Inline, List<Span>>(), reopened.bodyRanges)
        assertEquals(emptyList<Block>(), reopened.bodyBlocks)
        assertEquals(emptyList<Link>(), reopened.bodyLinks)
    }

    @Test fun `a queued forward reopens on its text, not on markup`() {
        // The html of a forward carries the original appended below, so it does not read back:
        // the row's plain text is what comes back — which is what happened before #131.
        val reopened = composeDraftOf(
            queued(htmlBody = "see you there<br><br><div>-------- Forwarded --------</div>"),
        )

        assertEquals("see you there", reopened.body)
        assertEquals(emptyMap<Inline, List<Span>>(), reopened.bodyRanges)
    }

    /**
     * A message reopened from a PERSISTED outbox row reopens as lossy: the row has no column
     */
    @Test fun `a message reopened from a persisted outbox row comes back with an unknown, lossy, verdict`() {
        val reopened = composeDraftOf(
            MailRepository.OutboxDraft(
                to = "bob@example.org", cc = "", bcc = "",
                subject = "Six o'clock", body = "see you there",
                fromAccountId = "accA", fromEmail = "alex@masto.top",
                attachments = emptyList(), inReplyTo = emptyList(), references = emptyList(),
                htmlBody = null,
                pgpMode = null, draftEmailId = "d1", draftUidValidity = null,
                outboxId = 42, requestReceipt = false,
            ),
        )
        assertTrue(
            "no persisted row carries the verdict, so a reopened one is unknown — and unknown is lossy",
            reopened.draftBodyIsLossy,
        )
        assertEquals("the id still travels; only the destroy is refused", "d1", reopened.draftEmailId)
    }
}
