package app.sterna.core.data.mail

import app.sterna.core.data.db.LocalDraftEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * **The `Message-ID` names the VERSION, not the draft** (#95), executed end to end over the
 */
class LocalDraftVersionIsNamedTest {

    /** The phone's `local_drafts`, by row id. */
    private val phone = linkedMapOf<String, LocalDraftEntity>()

    /** The server's Drafts folder: `Message-ID` → the text that copy carries. */
    private val drafts = linkedMapOf<String, String>()

    /** What the deferred upload asked the server about, in order. */
    private val asked = mutableListOf<String>()

    private var minted = 0

    @Test fun `a second version reaches the server instead of being taken for the first`() {
        // 1. v1 goes out and the answer is lost: the copy is there, the phone does not know.
        save(body = V1, deliver = { row -> drafts[row.messageId] = row.textBody; throw IOException("Connection reset by peer") })
        // 2. the user re-opens the SAME server draft, edits, saves with no network at all.
        save(body = V2, deliver = { throw IOException("Network is unreachable") })

        val result = worker()

        assertEquals("the deferred attempt ran to the end", LocalDraftUploadStep.UPLOADED, result.step)
        assertTrue(
            "⛔ THE loss: the text the user last typed must exist somewhere. The server holds " +
                "${drafts.values} and the phone holds ${phone.values.map { it.textBody }}",
            V2 in drafts.values,
        )
        assertEquals(
            "the row is consumed, and the residual damage is the accepted one: a stale copy left " +
                "in Drafts next to the new one, never a version that exists nowhere",
            listOf(V1, V2), drafts.values.toList(),
        )
        assertEquals("nothing is left on the phone", emptyList<String>(), phone.keys.toList())
    }

    @Test fun `each save by the user writes a new name, and keeps the row it lands on`() {
        save(body = V1, deliver = { throw IOException("Network is unreachable") })
        val first = phone.values.single()

        save(body = V2, deliver = { throw IOException("Network is unreachable") })
        val second = phone.values.single()

        assertNotEquals(
            "⛔ a name that does not tell v1 from v2 cannot decide not to send v2",
            first.messageId, second.messageId,
        )
        assertEquals("one draft, one row — the id is NOT re-minted", first.id, second.id)
        assertEquals("nor is the creation time", first.createdAtMillis, second.createdAtMillis)
        assertEquals("and the row carries what was last typed", V2, second.textBody)
    }

    @Test fun `the worker's own retries keep asking under the name the save wrote`() {
        // The other half of the rule, and the half that makes the search worth running at all: the
        // worker never rebuilds the row, so every attempt at ONE save asks about the same name. A
        // name re-minted here would search for something that has never been on a wire, find
        // nothing, and append a copy per retry — the duplicate this volet exists to stop.
        save(body = V1, deliver = { throw IOException("Network is unreachable") })
        val name = phone.values.single().messageId

        // Each attempt at its own instant: the backoff the failure before it wrote is real, and two
        // attempts at the same clock would simply be refused as not due.
        repeat(2) { round -> workerFailingToAppend(LATER + round * LATER) }

        assertEquals("two attempts, one name, asked twice", listOf(name, name), asked)
        assertEquals("and the row still carries it", name, phone.values.single().messageId)
    }

    // ---- the harness: the shipped functions, a map for the phone and a map for the folder ----------

    /** One save gesture by the user, on the SAME server draft every time. */
    private fun save(body: String, deliver: (LocalDraftEntity) -> Unit) = runBlocking {
        val target = localDraftTarget(
            SERVER_DRAFT,
            byId = { id -> phone[id] },
            forServerDraft = { id -> phone.values.firstOrNull { it.replacesEmailId == id } },
        )
        val existing = target.existing
        val row = localDraftRow(
            accountId = ACCOUNT,
            id = target.id,
            messageId = localDraftMessageId(existing) { newMessageId("alex@example.org", "u${++minted}") },
            to = listOf("bob@example.org"),
            cc = emptyList(),
            bcc = emptyList(),
            subject = "Half written",
            body = body,
            replacesEmailId = serverDraftReplacedBy(SERVER_DRAFT),
            existing = existing,
            composerBodyWasLost = false,
            nowMillis = NOW,
        )
        saveDraftLocalFirst(
            row = row,
            existing = existing,
            nowMillis = NOW,
            upsert = { phone[it.id] = it },
            stage = { emptyList() },
            discard = { phone.remove(it.id) },
            upload = { saved -> deliver(saved); DraftSaveOutcome.SAVED },
            record = { saved, attempt -> phone[saved.id] = saved.withAttempt(attempt) },
            schedule = {},
        )
    }

    /** One deferred attempt, with the shipped search-then-append in it. */
    private fun worker(): LocalDraftUploadResult = runBlocking {
        attempt(LATER) { row ->
            appendDraftUnlessAlreadyThere(
                row = row,
                alreadyThere = { messageId -> asked += messageId; drafts.containsKey(messageId) },
                append = { drafts[row.messageId] = row.textBody },
            )
        }
    }

    /** The same, against a server that cannot be reached at all — so the row survives it. */
    private fun workerFailingToAppend(at: Long): LocalDraftUploadResult = runBlocking {
        attempt(at) { row ->
            appendDraftUnlessAlreadyThere(
                row = row,
                alreadyThere = { messageId -> asked += messageId; drafts.containsKey(messageId) },
                append = { throw IOException("Network is unreachable") },
            )
        }
    }

    private suspend fun attempt(
        nowMillis: Long,
        upload: suspend (LocalDraftEntity) -> Unit,
    ): LocalDraftUploadResult {
        val id = phone.keys.single()
        return uploadLocalDraftOnce(
            row = phone[id],
            nowMillis = nowMillis,
            markUploading = {},
            upload = { row -> upload(row); DraftSaveOutcome.SAVED },
            discard = { phone.remove(it.id) },
            record = { failed -> phone[id] = phone.getValue(id).withAttempt(failed) },
        )
    }

    private fun LocalDraftEntity.withAttempt(attempt: LocalDraftAttempt) = copy(
        attemptCount = attempt.attemptCount,
        lastError = attempt.error,
        lastAttemptMillis = attempt.atMillis,
        notBeforeMillis = attempt.notBeforeMillis,
    )

    private companion object {
        const val ACCOUNT = "acc"
        const val SERVER_DRAFT = "imap:acc:Drafts:12"
        const val V1 = "I will be there at six"
        const val V2 = "I will be there at seven, do not wait for me at the station"
        const val NOW = 1_760_000_000_000L
        const val LATER = NOW + 3_600_000L
    }
}
