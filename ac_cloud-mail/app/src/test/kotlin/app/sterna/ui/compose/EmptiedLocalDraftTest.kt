package app.sterna.ui.compose

import app.sterna.mail.MessageDestroyWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Emptying a draft the phone kept for a server draft it could not replace yet (#95 × #69), as the
 */
class EmptiedLocalDraftTest {

    // -- what the plan carries -------------------------------------------------------------------

    @Test fun `an IMAP order carries the server draft, its folder and the frozen numbering`() {
        assertEquals(
            "⛔ all three, or the destroy is a no-op nobody sees: without the folder JMAP spares " +
                "every id (#122), without the numbering IMAP has nothing to oppose and destroys " +
                "nothing (#99), and either way the screen closed as a success. Order was:",
            MessageDestroyWorker.FolderDestroy("INBOX.Drafts", listOf("imap:acc-1:INBOX.Drafts:441"), 9_001L),
            planForEmptiedLocalDraft(
                replacedServerDraftId = "imap:acc-1:INBOX.Drafts:441",
                replacedServerDraftUidValidity = 9_001L,
                mailboxId = "INBOX.Drafts",
                bodyIsLossy = false,
                addressingIsProvenEmpty = true,
            ).order,
        )
    }

    @Test fun `a JMAP order carries the folder and no numbering at all`() {
        assertEquals(
            "on JMAP the row freezes no numbering (an id there survives anything a folder can go " +
                "through) and the destroy is checked against the FOLDER instead. Substituting a " +
                "number here would be inventing one. Order was:",
            MessageDestroyWorker.FolderDestroy("mbx-drafts", listOf("Mb42"), null),
            planForEmptiedLocalDraft(
                replacedServerDraftId = "Mb42",
                replacedServerDraftUidValidity = null,
                mailboxId = "mbx-drafts",
                bodyIsLossy = false,
                addressingIsProvenEmpty = true,
            ).order,
        )
    }

    @Test fun `the numbering is passed on exactly as the row froze it`() {
        assertEquals(
            "the numbering travels off the row and is never looked up on the way out (#99): the " +
                "value read here must be the value handed in, whatever it is",
            4_242L,
            planForEmptiedLocalDraft("Mb42", 4_242L, "mbx-drafts", bodyIsLossy = false, addressingIsProvenEmpty = true)
                .order?.uidValidity,
        )
    }

    @Test fun `a draft that stands in for nothing destroys nothing, and says nothing`() {
        val plan = planForEmptiedLocalDraft(
            replacedServerDraftId = null,
            replacedServerDraftUidValidity = 9_001L,
            mailboxId = "INBOX.Drafts",
            bodyIsLossy = false,
            addressingIsProvenEmpty = true,
        )
        assertNull(
            "the ordinary case — a draft written from scratch with no network replaces no server " +
                "message, so there is nothing to destroy and nothing to evict. An order minted " +
                "here would name a null id",
            plan.order,
        )
        assertFalse("there is no server copy to tell her about", plan.tellServerCopyKept)
    }

    @Test fun `a lossy draft that stands in for nothing still says nothing`() {
        assertFalse(
            "the notice belongs to a server copy that was KEPT. With nothing replaced there is no " +
                "such copy, and saying \"the copy on the server was kept\" would name a message " +
                "that does not exist",
            planForEmptiedLocalDraft(null, null, "INBOX.Drafts", bodyIsLossy = true, addressingIsProvenEmpty = true)
                .tellServerCopyKept,
        )
    }

    @Test fun `no folder means no order, rather than an order that destroys nothing`() {
        assertNull(
            "no cached row and no Drafts role: an order without a folder is enqueued, runs, " +
                "destroys nothing and the eviction that follows it takes the draft off the phone " +
                "anyway. Answering null leaves it where the user can still see it",
            planForEmptiedLocalDraft("Mb42", null, null, bodyIsLossy = false, addressingIsProvenEmpty = true).order,
        )
        assertNull(
            "an empty folder id is no folder",
            planForEmptiedLocalDraft("Mb42", null, "", bodyIsLossy = false, addressingIsProvenEmpty = true).order,
        )
    }

    // -- the copy this phone never read whole is KEPT ---------------------------------------------

    @Test fun `a draft this phone could not read whole leaves the server copy alone`() {
        val plan = planForEmptiedLocalDraft(
            replacedServerDraftId = "imap:acc-1:INBOX.Drafts:441",
            replacedServerDraftUidValidity = 9_001L,
            mailboxId = "INBOX.Drafts",
            bodyIsLossy = true,
            addressingIsProvenEmpty = true,
        )
        assertNull(
            "⛔ IRREVERSIBLE, and it beats the rule of #69. `bodyIsLossy` says the row holds what " +
                "this phone MANAGED to read of the draft: attachments never downloaded, HTML " +
                "flattened. The user emptied what she was SHOWN — destroying the server copy on " +
                "the strength of that destroys, for ever, what she was never shown. Order was:",
            plan.order,
        )
        assertTrue(
            "…and the screen has to say it, or the app silently does the opposite of what was " +
                "asked: the draft still sits in Drafts at the next sync and looks like a bug",
            plan.tellServerCopyKept,
        )
    }

    @Test fun `the lossy verdict outranks a folder that would have made a destroy possible`() {
        assertNull(
            "the guard is not \"lossy AND unresolvable\": a perfectly resolvable folder, a frozen " +
                "numbering and a valid id still destroy nothing when the phone had not read it all",
            planForEmptiedLocalDraft("Mb42", 4_242L, "mbx-drafts", bodyIsLossy = true, addressingIsProvenEmpty = true).order,
        )
    }

    // -- the addressing of the copy behind the row -------------------------------------------------

    @Test fun `an addressing this phone could not prove empty leaves the server copy alone`() {
        val plan = planForEmptiedLocalDraft(
            replacedServerDraftId = "imap:acc-1:INBOX.Drafts:441",
            replacedServerDraftUidValidity = 9_001L,
            mailboxId = "INBOX.Drafts",
            bodyIsLossy = false,
            addressingIsProvenEmpty = false,
        )
        assertNull(
            "⛔ THE DEFECT, and the plan is where it shows: a body read WHOLE says nothing about " +
                "the recipients of the copy this order would expunge. The phone cannot tell a " +
                "draft with no Cc from a Cc it never read — a cache row older than the v21 " +
                "columns and never back-filled, a read that failed, an address the envelope gave " +
                "back unreadable — so an order minted here destroys, for ever, the only copy that " +
                "still held them. The ordinary save has demanded this proof since #63; this " +
                "gesture demanded nothing. Order was:",
            plan.order,
        )
        assertTrue(
            "…and the copy being kept is owed the word, exactly as the lossy case is: kept in " +
                "silence, the draft is back at the next sync and reads as a bug",
            plan.tellServerCopyKept,
        )
    }

    @Test fun `the eight cases are eight, and only one of them destroys`() {
        // Every combination EXECUTED against a literal table — the two keeping terms crossed with
        // "this row stands in for nothing". A constant, or a term dropped, fails whole rows here.
        val ids = listOf(null, "Mb42")
        assertEquals(
            "⛔ the whole decision, as a table. The copy is kept when there IS one and either " +
                "term says something may be lost; with no server draft behind the row nothing is " +
                "kept and nothing is said, whatever the other two answer, because the sentence " +
                "would name a message that does not exist. The single destroying row is a body " +
                "read whole over an addressing the server proved empty — the rule of #69",
            mapOf(
                Triple(null, false, false) to false,
                Triple(null, false, true) to false,
                Triple(null, true, false) to false,
                Triple(null, true, true) to false,
                Triple("Mb42", false, false) to true,
                Triple("Mb42", false, true) to false,
                Triple("Mb42", true, false) to true,
                Triple("Mb42", true, true) to true,
            ),
            ids.flatMap { id ->
                listOf(false, true).flatMap { lossy ->
                    listOf(false, true).map { proven ->
                        Triple(id, lossy, proven) to emptiedLocalDraftKeepsServerCopy(id, lossy, proven)
                    }
                }
            }.toMap(),
        )
    }

    @Test fun `the plan and the dialog stay one decision on the addressing term too`() {
        assertEquals(
            "the notice posted after an empty save and the sentence shown before a delete are " +
                "still the same fact once the addressing weighs in: two implementations drift, " +
                "and the one that drifts warns about a copy the other destroys",
            planForEmptiedLocalDraft("Mb42", null, "mbx-drafts", bodyIsLossy = false, addressingIsProvenEmpty = false)
                .tellServerCopyKept,
            emptiedLocalDraftKeepsServerCopy("Mb42", bodyIsLossy = false, addressingIsProvenEmpty = false),
        )
    }

    // -- the order of the acts -------------------------------------------------------------------

    @Test fun `the destroy is durable before the local row is consumed`() {
        val done = mutableListOf<String>()
        val order = MessageDestroyWorker.FolderDestroy("INBOX.Drafts", listOf("imap:acc-1:INBOX.Drafts:441"), 9_001L)
        runBlocking {
            destroyThenConsumeEmptiedLocalDraft(
                localDraftId = "local-draft:d1",
                plan = EmptiedLocalDraftPlan(order, tellServerCopyKept = false),
                destroy = { done += "destroy:${it.emailIds}@${it.mailboxId}#${it.uidValidity}" },
                evict = { done += "evict:$it" },
                consume = { done += "consume:$it" },
                tell = { done += "tell" },
            )
        }
        assertEquals(
            "⛔ THE ORDER. The local row is the only thing that still names the server draft: " +
                "consumed first, a process death in the interval leaves that draft alive, " +
                "unmasked, and nothing anywhere will destroy it ever again. The eviction sits " +
                "between the two so the draft leaves Drafts at the instant of the gesture, with " +
                "the order already durable. Acts were:",
            listOf(
                "destroy:[imap:acc-1:INBOX.Drafts:441]@INBOX.Drafts#9001",
                "evict:[imap:acc-1:INBOX.Drafts:441]",
                "consume:local-draft:d1",
            ),
            done,
        )
    }

    @Test fun `a destroy that could not be made durable consumes nothing`() {
        val done = mutableListOf<String>()
        val thrown = runCatching {
            runBlocking {
                destroyThenConsumeEmptiedLocalDraft(
                    localDraftId = "local-draft:d1",
                    plan = EmptiedLocalDraftPlan(
                        MessageDestroyWorker.FolderDestroy("INBOX.Drafts", listOf("imap:acc-1:INBOX.Drafts:441"), 9_001L),
                        tellServerCopyKept = false,
                    ),
                    destroy = { done += "destroy"; error("WorkManager refused the request") },
                    evict = { done += "evict:$it" },
                    consume = { done += "consume:$it" },
                    tell = { done += "tell" },
                )
            }
        }.exceptionOrNull()
        assertEquals("WorkManager refused the request", thrown?.message)
        assertEquals(
            "nothing was ordered, so nothing may be taken away: the row survives, the server " +
                "draft stays masked behind it, and the composer says the save failed",
            listOf("destroy"),
            done,
        )
    }

    @Test fun `a kept server copy is said BEFORE the row goes, and destroys nothing`() {
        val done = mutableListOf<String>()
        runBlocking {
            destroyThenConsumeEmptiedLocalDraft(
                localDraftId = "local-draft:d1",
                plan = EmptiedLocalDraftPlan(null, tellServerCopyKept = true),
                destroy = { done += "destroy" },
                evict = { done += "evict:$it" },
                consume = { done += "consume:$it" },
                tell = { done += "tell" },
            )
        }
        assertEquals(
            "the copy is kept, the row is consumed as usual, and the word comes BEFORE the " +
                "consume — the composer reaches Done right behind it and a notice emitted after " +
                "the screen has gone is a notice nobody reads. Acts were:",
            listOf("tell", "consume:local-draft:d1"),
            done,
        )
    }

    // -- the gesture survives the screen going away ------------------------------------------------

    @Test fun `the back gesture during the destroy does not leave the row behind`() {
        // The twin's shelter, for the twin's reason (`commitThenConsumeLocalDraft`), and this
        // volet is what makes the hole reachable by ONE tap: the delete runs in viewModelScope, and
        val done = mutableListOf<String>()
        runBlocking {
            val screen = Job()
            val job = launch(screen) {
                destroyThenConsumeEmptiedLocalDraft(
                    localDraftId = "local-draft:d1",
                    plan = EmptiedLocalDraftPlan(
                        MessageDestroyWorker.FolderDestroy("INBOX.Drafts", listOf("imap:acc-1:INBOX.Drafts:441"), 9_001L),
                        tellServerCopyKept = false,
                    ),
                    destroy = {
                        done += "destroy"
                        // The back gesture, exactly here: the order is durable, the row is not gone.
                        screen.cancel()
                        yield()
                    },
                    evict = { yield(); done += "evict:$it" },
                    consume = { yield(); done += "consume:$it" },
                    tell = { done += "tell" },
                )
            }
            job.join()
        }
        assertEquals(
            "⛔ everything below the destroy must still run. Anything short of this whole list is " +
                "a draft destroyed on the server and re-uploaded from the phone at the next launch",
            listOf("destroy", "evict:[imap:acc-1:INBOX.Drafts:441]", "consume:local-draft:d1"),
            done,
        )
    }

    @Test fun `a screen closed BEFORE anything is destroyed destroys nothing`() {
        // The boundary, and it is deliberate: the shelter covers the acts, not the wait in front
        // of them. Nothing has been destroyed yet, so the draft is precisely what must survive —
        // the row stays where the user can still see it and the lease is given back by the caller.
        val done = mutableListOf<String>()
        runBlocking {
            val screen = Job()
            val job = launch(screen) {
                screen.cancel()
                try {
                    destroyThenConsumeEmptiedLocalDraft(
                        localDraftId = "local-draft:d2",
                        plan = EmptiedLocalDraftPlan(
                            MessageDestroyWorker.FolderDestroy("INBOX.Drafts", listOf("Mb42"), null),
                            tellServerCopyKept = false,
                        ),
                        destroy = { done += "destroy" },
                        evict = { done += "evict:$it" },
                        consume = { done += "consume:$it" },
                        tell = { done += "tell" },
                    )
                } catch (cancelled: CancellationException) {
                    done += "cancelled before anything was destroyed"
                }
            }
            job.join()
        }
        assertEquals(listOf("cancelled before anything was destroyed"), done)
    }

    // -- what the dialog owes the reader, BEFORE the finger ----------------------------------------

    @Test fun `a copy this phone never read whole is announced as kept`() {
        assertTrue(
            "⛔ the delete confirmation has to say it BEFORE the tap: the row masks a server copy " +
                "that this gesture KEEPS, so \"this draft hasn't reached the server, and this " +
                "can't be undone\" is the opposite of what happens — the pre-edit draft comes back " +
                "at the top of Drafts the moment the row goes",
            emptiedLocalDraftKeepsServerCopy("imap:acc-1:INBOX.Drafts:441", bodyIsLossy = true, addressingIsProvenEmpty = true),
        )
    }

    @Test fun `a draft that replaced nothing announces no server copy`() {
        assertFalse(
            "with nothing behind the row there is no copy to keep, and the sentence would name a " +
                "message that does not exist",
            emptiedLocalDraftKeepsServerCopy(null, bodyIsLossy = true, addressingIsProvenEmpty = true),
        )
    }

    @Test fun `a draft this phone read whole announces nothing either`() {
        assertFalse(
            "this phone read it all, so the copy behind the row is destroyed by this very gesture " +
                "— announcing that it stays is the same lie the other way round",
            emptiedLocalDraftKeepsServerCopy("imap:acc-1:INBOX.Drafts:441", bodyIsLossy = false, addressingIsProvenEmpty = true),
        )
    }

    @Test fun `the plan and the dialog answer with one decision`() {
        assertEquals(
            "the notice posted after an empty save and the sentence shown before a delete are the " +
                "same fact: two implementations drift, and the one that drifts warns about a copy " +
                "the other destroys",
            planForEmptiedLocalDraft("Mb42", null, "mbx-drafts", bodyIsLossy = true, addressingIsProvenEmpty = true)
                .tellServerCopyKept,
            emptiedLocalDraftKeepsServerCopy("Mb42", bodyIsLossy = true, addressingIsProvenEmpty = true),
        )
    }

    // -- who gets the row back when the deletion is over -------------------------------------------

    @Test fun `a deletion that did not happen gives the row back to the upload worker`() {
        assertEquals(
            "⛔ the failure is ATOMIC — everything that throws sits in front of any destruction — " +
                "so the row is exactly what it was and must go back to PENDING. Left EDITING with " +
                "nobody to release it (which is what blanking the lease before the submit did), no " +
                "upload worker touches it again before the next cold start",
            "local-draft:d1",
            localDraftLeaseAfterDelete("local-draft:d1", destroyed = false),
        )
    }

    @Test fun `a deletion that happened gives the row back to nobody`() {
        assertNull(
            "⛔ the row and its files are gone, consumed inside the gesture. A release here re-arms " +
                "the upload of a draft that has just been destroyed: the pre-edit text races the " +
                "destroy up to the server and lands in Drafts behind it",
            localDraftLeaseAfterDelete("local-draft:d1", destroyed = true),
        )
    }

    @Test fun `a composer that no longer holds the row releases nothing`() {
        assertNull(
            "the close button is live while the destroy runs (#127): it hands the row back itself " +
                "and drops the lease, and this gesture must not release a row it no longer holds",
            localDraftLeaseAfterDelete(null, destroyed = false),
        )
    }

    @Test fun `with nothing to destroy the row is still consumed, and nothing else happens`() {
        val done = mutableListOf<String>()
        runBlocking {
            destroyThenConsumeEmptiedLocalDraft(
                localDraftId = "local-draft:d1",
                plan = EmptiedLocalDraftPlan(null, tellServerCopyKept = false),
                destroy = { done += "destroy" },
                evict = { done += "evict:$it" },
                consume = { done += "consume:$it" },
                tell = { done += "tell" },
            )
        }
        assertEquals(
            "a draft that replaced nothing must keep behaving exactly as it did: its row and its " +
                "staged files go, no destroy is fired at a message that does not exist, and " +
                "nothing is said about a server copy there never was",
            listOf("consume:local-draft:d1"),
            done,
        )
    }

    // -- whose account the destroy runs under ----------------------------------------------------

    /**
     * The "From" picker covers every account and the local row belongs to exactly one. These four
     */
    @Test fun `the destroy runs under the account the row was leased under, not the one written as`() {
        val asked = mutableListOf<String>()
        val credentials = credentialsDestroyingReplacedServerDraft(
            leasedUnderAccountId = "acc-A",
            composingAsAccountId = "acc-B",
            lookup = { id -> asked += id; "creds-of-$id" },
        )
        assertEquals(
            "⛔ the server draft's id and the numbering it was frozen under were read off account " +
                "A's draft: destroyed under B they name a message B's server never issued. The " +
                "row is consumed under A regardless, so the mask lifts and the pre-edit text is " +
                "back at the top of A's Drafts for good. Credentials returned were:",
            "creds-of-acc-A",
            credentials,
        )
        assertEquals(
            "…and the store is asked about that account and no other: an extra look-up here is a " +
                "fallback waiting to be written. Accounts asked about were:",
            listOf("acc-A"),
            asked,
        )
    }

    @Test fun `an account removed during the edit destroys nothing, and never falls back on the writer`() {
        val asked = mutableListOf<String>()
        val credentials = credentialsDestroyingReplacedServerDraft(
            leasedUnderAccountId = "acc-A",
            composingAsAccountId = "acc-B",
            lookup = { id -> asked += id; if (id == "acc-A") null else "creds-of-$id" },
        )
        assertNull(
            "⛔ the account that held the draft is gone, and B's credentials are RIGHT THERE — " +
                "that is exactly the fallback that destroys under the wrong account. Refusing " +
                "destroys nothing: the caller draws its failure banner and the draft is still a " +
                "draft. Credentials returned were:",
            credentials,
        )
        assertEquals(
            "…and B was never even looked up. Accounts asked about were:",
            listOf("acc-A"),
            asked,
        )
    }

    @Test fun `no row held is nothing to destroy, whoever the composer is writing as`() {
        val asked = mutableListOf<String>()
        assertNull(
            "no local row is held, so there is no replaced server draft this gesture may touch. " +
                "Credentials returned were:",
            credentialsDestroyingReplacedServerDraft(
                leasedUnderAccountId = null,
                composingAsAccountId = "acc-B",
                lookup = { id -> asked += id; "creds-of-$id" },
            ),
        )
        assertEquals("…and nothing was looked up at all. Accounts asked about were:", emptyList<String>(), asked)
    }

    @Test fun `writing as the account that holds the row is the ordinary case, and still destroys`() {
        assertEquals(
            "the rule must not refuse the case that is 99% of the traffic — one account, its own " +
                "draft. Credentials returned were:",
            "creds-of-acc-A",
            credentialsDestroyingReplacedServerDraft(
                leasedUnderAccountId = "acc-A",
                composingAsAccountId = "acc-A",
                lookup = { "creds-of-$it" },
            ),
        )
    }
}
