package app.sterna.core.data.mail

import app.sterna.core.data.db.LocalDraftEntity
import app.sterna.core.data.db.LocalDraftState
import app.sterna.core.imap.ImapException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * **One text, one copy on the server** (#95), executed: the shipped
 */
class LocalDraftDuplicateAppendTest {

    /** What the harness was asked, in order — so "exactly once", "never" and "with what" are facts. */
    private val asked = mutableListOf<String>()
    private val appended = mutableListOf<String>()

    // ---- 1. whether the question is worth asking at all ------------------------------------------

    @Test fun `a draft that has never failed cannot be on the server yet`() {
        assertFalse(localDraftMayAlreadyBeOnServer(row(attemptCount = 0)))
    }

    @Test fun `a draft that has failed before may have got there all the same`() {
        // The whole point: the failure the client SAW says nothing about what the server did with
        // the bytes it had already received.
        assertTrue(localDraftMayAlreadyBeOnServer(row(attemptCount = 1)))
        assertTrue(localDraftMayAlreadyBeOnServer(row(attemptCount = 7)))
    }

    @Test fun `a draft with no name at all is not something the server can be asked about`() {
        // The abstention is only ever safe because a NAME is being compared. A row with none has
        // nothing that could match, and asking anyway would spend a round trip on a folder read
        assertFalse(localDraftMayAlreadyBeOnServer(row(attemptCount = 3, messageId = "")))
        assertFalse(localDraftMayAlreadyBeOnServer(row(attemptCount = 3, messageId = "   ")))
    }

    // ---- 1b. THE decision: is what the server reported our draft? --------------------------------

    @Test fun `the id the server reports in brackets names the same draft as our naked one`() {
        // The two values are written differently by construction: ours is the naked id the save
        // minted, and the ENVELOPE hands the header back as stored — `<id>`, brackets included.
        assertTrue(localDraftMessageIdMatches("abc-123@masto.top", "<abc-123@masto.top>"))
        assertTrue(localDraftMessageIdMatches("abc-123@masto.top", "abc-123@masto.top"))
        assertTrue(localDraftMessageIdMatches("<abc-123@masto.top>", "<abc-123@masto.top>"))
        assertTrue(
            "whitespace on either side is not a difference",
            localDraftMessageIdMatches("  abc-123@masto.top ", " <abc-123@masto.top>  "),
        )
    }

    @Test fun `an id that merely CONTAINS ours is a different draft`() {
        // THE mutation this exists for. A substring test would call all four of these a match,
        // and a false "already there" is not a cosmetic error: the append is skipped, the row is
        // consumed by `uploadLocalDraftOnce`, and the text exists nowhere afterwards. A duplicate
        // in Drafts is visible and deletable; this is not.
        assertFalse(localDraftMessageIdMatches("abc-123@masto.top", "<x-abc-123@masto.top>"))
        assertFalse(localDraftMessageIdMatches("abc-123@masto.top", "<abc-123@masto.top.evil.example>"))
        assertFalse(localDraftMessageIdMatches("abc-123@masto.top", "<abc-1234@masto.top>"))
        assertFalse(
            "nor the other way round",
            localDraftMessageIdMatches("abc-123@masto.top", "<123@masto.top>"),
        )
    }

    @Test fun `case is not folded, because every doubt goes to the duplicate`() {
        assertFalse(localDraftMessageIdMatches("abc-123@masto.top", "<ABC-123@masto.top>"))
    }

    @Test fun `a message the server reports with no id at all never matches`() {
        // The ENVELOPE slot is NIL — common enough on mail another client wrote. Two nameless
        // things are not the same thing.
        assertFalse(localDraftMessageIdMatches("abc-123@masto.top", null))
        assertFalse(localDraftMessageIdMatches("abc-123@masto.top", ""))
        assertFalse(localDraftMessageIdMatches("abc-123@masto.top", "   "))
        assertFalse(localDraftMessageIdMatches("abc-123@masto.top", "<>"))
        assertFalse(
            "and a nameless row matches nothing either",
            localDraftMessageIdMatches("", null),
        )
        assertFalse(localDraftMessageIdMatches("", "<abc-123@masto.top>"))
        assertFalse(localDraftMessageIdMatches("   ", "<>"))
    }

    @Test fun `a nameless draft is appended rather than searched for`() {
        val wentOut = runBlocking {
            appendDraftUnlessAlreadyThere(row(attemptCount = 3, messageId = ""), ::alreadyThere, ::append)
        }
        assertEquals("there is nothing to ask the server about", emptyList<String>(), asked)
        assertEquals(listOf("appended"), appended)
        assertTrue(wentOut)
    }

    // ---- 2. what one attempt does with the answer ------------------------------------------------

    @Test fun `a first attempt appends without asking the server anything`() {
        val wentOut = runBlocking {
            appendDraftUnlessAlreadyThere(row(attemptCount = 0), ::alreadyThere, ::append)
        }
        assertEquals("a first save must not pay a round trip for an answer known in advance", emptyList<String>(), asked)
        assertEquals(listOf("appended"), appended)
        assertTrue(wentOut)
    }

    @Test fun `a retry asks the server for the row's own message-id, naked`() {
        found = false
        val wentOut = runBlocking {
            appendDraftUnlessAlreadyThere(row(attemptCount = 1, messageId = "abc-123@masto.top"), ::alreadyThere, ::append)
        }
        // The ARGUMENT, not merely that a call happened: the id has to be the row's frozen one
        // and it goes NAKED, as the row stores it. The brackets the header is written with are
        // dealt with where the two ids are compared (`localDraftMessageIdMatches`), not here.
        assertEquals(listOf("abc-123@masto.top"), asked)
        assertEquals("nothing was found, so the draft still has to go up", listOf("appended"), appended)
        assertTrue(wentOut)
    }

    @Test fun `a retry that finds the draft on the server does not append a second copy`() {
        found = true
        val wentOut = runBlocking {
            appendDraftUnlessAlreadyThere(row(attemptCount = 1, messageId = "abc-123@masto.top"), ::alreadyThere, ::append)
        }
        assertEquals(listOf("abc-123@masto.top"), asked)
        assertEquals("the server already holds this text", emptyList<String>(), appended)
        assertFalse(wentOut)
    }

    @Test fun `a search that fails appends rather than abstaining`() {
        // THE rule, and it is about loss, not about tidiness. The caller consumes the row the
        // moment the upload returns: reading "I could not look" as "it is there" deletes the text
        // and leaves it nowhere. A duplicate is visible and deletable; an absence is not.
        val wentOut = runBlocking {
            appendDraftUnlessAlreadyThere(
                row(attemptCount = 3, messageId = "abc-123@masto.top"),
                { throw IOException("Connection reset by peer") },
                ::append,
            )
        }
        assertEquals(listOf("appended"), appended)
        assertTrue(wentOut)
    }

    @Test fun `a lookup the server REFUSES appends, and that failure is an ImapException`() {
        // The type is the whole test. `ImapException` is what a refused `UID SEARCH` throws
        // (`core/imap/ImapClient.kt`: `class ImapException(…) : Exception(message)`) — it is NOT an
        val wentOut = runBlocking {
            appendDraftUnlessAlreadyThere(
                row(attemptCount = 3, messageId = "abc-123@masto.top"),
                { asked += it; throw ImapException("search unavailable", "SERVERBUG") },
                ::append,
            )
        }
        assertEquals("the server WAS asked, about the row's own id", listOf("abc-123@masto.top"), asked)
        assertEquals("and a refusal appends", listOf("appended"), appended)
        assertTrue(wentOut)
    }

    @Test fun `a lookup that throws anything at all appends just the same`() {
        // A bare `Exception` — a parse that trips, a stand-in that was not set up, whatever a
        // future session puts on this path. "Everything that is not a cancellation" is the rule,
        // and it is stated by exercising a type nobody enumerated.
        val wentOut = runBlocking {
            appendDraftUnlessAlreadyThere(
                row(attemptCount = 3, messageId = "abc-123@masto.top"),
                { asked += it; throw Exception("something nobody listed") },
                ::append,
            )
        }
        assertEquals(listOf("abc-123@masto.top"), asked)
        assertEquals(listOf("appended"), appended)
        assertTrue(wentOut)
    }

    @Test fun `a cancelled search is not a failure and appends nothing`() {
        // The worker is being stopped. The row keeps the state it had and the next attempt starts
        // over — an APPEND fired on the way out would be the duplicate this exists to prevent.
        assertThrows(CancellationException::class.java) {
            runBlocking {
                appendDraftUnlessAlreadyThere(
                    row(attemptCount = 1),
                    { throw CancellationException("worker stopped") },
                    ::append,
                )
            }
        }
        assertEquals(emptyList<String>(), appended)
    }

    // ---- 3. and the row is still consumed, so nothing loops forever ------------------------------

    @Test fun `a draft found on the server ends its retries instead of being appended for ever`() {
        found = true
        val row = row(attemptCount = 1, messageId = "abc-123@masto.top")
        val discarded = mutableListOf<String>()
        val result = runBlocking {
            uploadLocalDraftOnce(
                row = row,
                nowMillis = NOW,
                markUploading = {},
                upload = {
                    appendDraftUnlessAlreadyThere(it, ::alreadyThere, ::append)
                    DraftSaveOutcome.SAVED
                },
                discard = { discarded += it.id },
                record = { error("a skipped append is a success, not a failure") },
            )
        }
        assertEquals(emptyList<String>(), appended)
        assertEquals("the local row is consumed: the text IS on the server", listOf(row.id), discarded)
        assertEquals(LocalDraftUploadStep.UPLOADED, result.step)
    }

    // ---- the harness ------------------------------------------------------------------------------

    private var found = false

    private suspend fun alreadyThere(messageId: String): Boolean {
        asked += messageId
        return found
    }

    private suspend fun append() {
        appended += "appended"
    }

    private fun row(
        attemptCount: Int,
        messageId: String = "mid@example.org",
        id: String = "local-draft:1",
    ) = LocalDraftEntity(
        accountId = "acc", id = id, messageId = messageId,
        toAddresses = "bob@example.org", subject = "Half written", textBody = "text",
        createdAtMillis = NOW, updatedAtMillis = NOW, notBeforeMillis = NOW,
        attemptCount = attemptCount, state = LocalDraftState.PENDING,
    )

    private companion object {
        const val NOW = 1_760_000_000_000L
    }
}
