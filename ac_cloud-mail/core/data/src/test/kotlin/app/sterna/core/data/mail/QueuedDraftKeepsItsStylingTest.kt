package app.sterna.core.data.mail

import app.sterna.core.data.db.OutboxEntity
import app.sterna.core.data.db.OutboxState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The two hops the styling of a stored message takes through this layer (#131), and neither is
 */
class QueuedDraftKeepsItsStylingTest {

    private fun queued(htmlBody: String?) = OutboxEntity(
        id = 7,
        accountId = "accA",
        recipients = "bob@example.org",
        subject = "Six o'clock",
        textBody = "see you there",
        htmlBody = htmlBody,
        createdAtMillis = 1,
        notBeforeMillis = 2,
        state = OutboxState.QUEUED,
        pgpMode = null,
        requestReceipt = false,
    )

    @Test fun `a queued row hands its html to the draft a reopen is built from`() {
        assertEquals(
            "the row is the only place a waiting message's styling lives: dropped in this " +
                "mapping, re-editing the message enqueues a new row with the styling REMOVED",
            "<b>see</b> you there",
            outboxDraftOf(queued("<b>see</b> you there"), emptyList()).htmlBody,
        )
    }

    @Test fun `a queued row with no html hands over none`() {
        assertNull(outboxDraftOf(queued(null), emptyList()).htmlBody)
    }

    @Test fun `the JMAP draft create is handed the row's own html`() {
        val upload = DaoQuerySource.mailFunctionBody("MailRepository", "uploadDraft")

        assertEquals(
            "`uploadDraft` must hand `client.saveDraft` the ROW's html, whole and exactly once. " +
                "⛔ `htmlBody = null` (or the argument left out, which is the same thing — the " +
                "parameter defaults to null) uploads a styled draft as text: the server copy comes " +
                "back plain at the next sync, and the styling is gone from the only place the user " +
                "could still reopen it. ⛔ Whole line, because a mutation that matters LENGTHENS " +
                "it (`row.htmlBody.takeIf { … }`).",
            listOf("htmlBody = row.htmlBody,"),
            codeLines(upload).filter { it.startsWith("htmlBody") },
        )
    }

    /** [source] as trimmed, non-blank lines with its comments cut: prose must not answer for code. */
    private fun codeLines(source: String): List<String> {
        val out = StringBuilder()
        var i = 0
        var inString = false
        while (i < source.length) {
            val c = source[i]
            when {
                inString && c == '\\' -> { out.append(c).append(source.getOrElse(i + 1) { ' ' }); i += 2; continue }
                c == '"' -> { inString = !inString; out.append(c) }
                inString -> out.append(c)
                c == '/' && source.getOrNull(i + 1) == '/' -> {
                    while (i < source.length && source[i] != '\n') i++
                    out.append('\n')
                    continue
                }
                c == '/' && source.getOrNull(i + 1) == '*' -> {
                    val end = source.indexOf("*/", i + 2)
                    i = if (end < 0) source.length else end + 2
                    continue
                }
                else -> out.append(c)
            }
            i++
        }
        return out.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
    }
}
