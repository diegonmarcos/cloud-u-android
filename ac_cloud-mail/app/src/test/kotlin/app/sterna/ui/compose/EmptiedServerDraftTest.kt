package app.sterna.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Emptying a draft opened straight off the SERVER (#69 × #63), as the decision RUNS — not as a lint
 */
class EmptiedServerDraftTest {

    @Test fun `a draft this composer never read whole is KEPT on the server`() {
        assertTrue(
            "⛔ IRREVERSIBLE, and it beats the rule of #69. The verdict says the screen showed less " +
                "than the draft holds — HTML flattened, inline images and calendar parts never " +
                "carried, an attachment not brought down, or a read that failed outright. The user " +
                "emptied what she was SHOWN, and on this route that may have been an empty editor " +
                "for a draft that was never empty: expunging on the strength of it destroys, for " +
                "ever, what she was never shown (arbitration rule 1)",
            emptiedServerDraftIsKept(bodyIsLossy = true, addressingIsProvenEmpty = true),
        )
    }

    @Test fun `a draft this composer read whole and the user emptied by hand is still destroyed`() {
        assertFalse(
            "⛔ the guard may not become \"never destroy anything\": a body read WHOLE over an " +
                "addressing the SERVER proved empty loses nothing, and that is the rule of #69 — the " +
                "original is deleted so no empty shell lingers in Drafts, nor reappears on sync. " +
                "Answering \"kept\" here leaves the pre-edit draft at the top of Drafts after every " +
                "hand-emptied save, which is the symptom #69 was opened for",
            emptiedServerDraftIsKept(bodyIsLossy = false, addressingIsProvenEmpty = true),
        )
    }

    @Test fun `the verdict is the WHOLE decision, so the two answers cannot collapse into one`() {
        // Both cases at once: a fix that hard-codes either answer passes one test and fails this.
        assertTrue(
            "kept when the read was lossy, destroyed when it was faithful — the two, from the one " +
                "verdict. A constant here is a fix that either destroys everything (the defect) or " +
                "destroys nothing (#69, reopened)",
            emptiedServerDraftIsKept(bodyIsLossy = true, addressingIsProvenEmpty = true) &&
                !emptiedServerDraftIsKept(bodyIsLossy = false, addressingIsProvenEmpty = true),
        )
    }

    // -- the addressing of the copy about to be expunged -----------------------------------------

    @Test fun `an addressing this composer could not prove empty KEEPS the copy`() {
        assertFalse(
            "⛔ THE DEFECT. A faithful read of the body says nothing about the recipients of the " +
                "copy being expunged: the composer cannot tell a draft with no Cc from a Cc it " +
                "never managed to read — a cache row older than the v21 columns and never " +
                "back-filled, a read that failed, an address the envelope gave back unreadable. " +
                "Destroying on that reading takes the only copy that still held those addresses, " +
                "for ever, with nothing on screen. The ordinary save has demanded this proof " +
                "since #63 (draftReplacementIsFaithful, addressingIsCarried); this route, which " +
                "leaves not even the duplicate a save leaves, demanded nothing",
            !emptiedServerDraftIsKept(bodyIsLossy = false, addressingIsProvenEmpty = false),
        )
    }

    @Test fun `a lossy body keeps the copy whatever the addressing says`() {
        // This is what licenses the call site to SKIP the server read when the body is lossy:
        // the answer is the same for both values, so the read cannot change the outcome and the
        // `false` handed over in its place ("not proven", because nothing was asked) is safe.
        assertEquals(
            "the two terms are OR-ed, so the body verdict alone still keeps the copy — and " +
                "ComposeViewModel's `!editingDraftLossy &&` short-circuit only holds while this " +
                "is true. Make the addressing term outrank the body one and a draft made of one " +
                "inline image is expunged the moment the server says it was copied to nobody",
            listOf(true, true),
            listOf(
                emptiedServerDraftIsKept(bodyIsLossy = true, addressingIsProvenEmpty = true),
                emptiedServerDraftIsKept(bodyIsLossy = true, addressingIsProvenEmpty = false),
            ),
        )
    }

    @Test fun `the four cases are four, and only one of them destroys`() {
        // Every combination EXECUTED, against a literal table: no case can be collapsed into
        // another, and a constant fails three of the four rows at once.
        assertEquals(
            "⛔ the whole decision, as a table. The one destroying row is a body this composer " +
                "read WHOLE whose addressing the SERVER said was empty — that is the rule of #69 " +
                "(no empty shell left at the top of Drafts), and it is the only case in which " +
                "nothing can be lost. Every other row keeps the copy and owes the user the word " +
                "compose_emptied_draft_server_copy_kept",
            mapOf(
                (false to false) to true,
                (false to true) to false,
                (true to false) to true,
                (true to true) to true,
            ),
            listOf(false, true).flatMap { lossy ->
                listOf(false, true).map { proven ->
                    (lossy to proven) to emptiedServerDraftIsKept(lossy, proven)
                }
            }.toMap(),
        )
    }

    // -- whose account the expunge is addressed to -----------------------------------------------

    /**
     * The draft is emptied under whatever identity the "From" picker is showing, and that picker
     */
    @Test fun `the expunge is addressed to the account the draft was read from, not the one written as`() {
        val asked = mutableListOf<String>()
        val credentials = credentialsDestroyingEmptiedServerDraft(
            openedUnderAccountId = "acc-A",
            composingAsAccountId = "acc-B",
            lookup = { id -> asked += id; "creds-of-$id" },
        )
        assertEquals(
            "⛔ the draft's id and the numbering it was frozen under were read off account A. Sent " +
                "to B they name a message B's server never issued: JMAP spares it (the emptied " +
                "draft stays in A's Drafts and returns on the next sync) and IMAP compares two " +
                "NUMBERS, so an equal UIDVALIDITY expunges in B's Drafts instead. The screen closes " +
                "as a success either way. Credentials returned were:",
            "creds-of-acc-A",
            credentials,
        )
        assertEquals(
            "…and the store is asked about that account and no other: a second look-up here is a " +
                "fallback waiting to be written, and it is the fallback that destroys under the " +
                "wrong account. Accounts asked about were:",
            listOf("acc-A"),
            asked,
        )
    }

    @Test fun `an account removed during the edit expunges nothing, and never falls back on the writer`() {
        val asked = mutableListOf<String>()
        val credentials = credentialsDestroyingEmptiedServerDraft(
            openedUnderAccountId = "acc-A",
            composingAsAccountId = "acc-B",
            lookup = { id -> asked += id; if (id == "acc-A") null else "creds-of-$id" },
        )
        assertNull(
            "⛔ the account that held the draft is gone and B's credentials are RIGHT THERE — that " +
                "is exactly the fallback the fix removes. Refusing destroys nothing: the caller " +
                "throws, submit() draws its banner, and the draft is still a draft. Credentials " +
                "returned were:",
            credentials,
        )
        assertEquals(
            "…and B was never even looked up. Accounts asked about were:",
            listOf("acc-A"),
            asked,
        )
    }

    @Test fun `no draft opened is nothing to expunge, whoever the composer is writing as`() {
        val asked = mutableListOf<String>()
        assertNull(
            "no server draft was opened, so this gesture has nothing on any server to touch — and " +
                "an account taken from the picker would be a destroy addressed at a guess. " +
                "Credentials returned were:",
            credentialsDestroyingEmptiedServerDraft(
                openedUnderAccountId = null,
                composingAsAccountId = "acc-B",
                lookup = { id -> asked += id; "creds-of-$id" },
            ),
        )
        assertEquals("…and nothing was looked up at all. Accounts asked about were:", emptyList<String>(), asked)
    }

    @Test fun `writing as the account the draft came from is the ordinary case, and still expunges`() {
        assertEquals(
            "the rule must not refuse the case that is 99% of the traffic — one account, its own " +
                "draft, emptied and saved. Answering null there leaves an empty shell at the top " +
                "of Drafts after every hand-emptied save (#69). Credentials returned were:",
            "creds-of-acc-A",
            credentialsDestroyingEmptiedServerDraft(
                openedUnderAccountId = "acc-A",
                composingAsAccountId = "acc-A",
                lookup = { "creds-of-$it" },
            ),
        )
    }
}
