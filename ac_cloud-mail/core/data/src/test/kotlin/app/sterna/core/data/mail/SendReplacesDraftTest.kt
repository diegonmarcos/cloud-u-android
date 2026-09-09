package app.sterna.core.data.mail

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

/**
 * The decision the SEND path runs before it may destroy the draft a message was edited from:
 */
class SendReplacesDraftTest {

    /** The witness: a proven replacement still buys its destroy — no stale duplicate in Drafts. */
    @Test fun aProvenReplacementCarriesTheIdToTheRow() = runTest {
        assertEquals(
            "d1",
            replaceableDraftIdOrNull("d1", attachmentCount = 2, bodyIsLossy = false) { true },
        )
    }

    /** The defect: a body the composer could not reproduce must keep the id off the row. */
    @Test fun aLossyBodyKeepsTheIdOffTheRow() = runTest {
        assertNull(
            "a send that cannot reproduce the draft must not authorise destroying it",
            replaceableDraftIdOrNull("d1", attachmentCount = 2, bodyIsLossy = true) { true },
        )
    }

    /** An addressing shown NOT to be carried refuses the destroy on its own. */
    @Test fun anAddressingDisprovenKeepsTheIdOffTheRow() = runTest {
        assertNull(
            replaceableDraftIdOrNull("d1", attachmentCount = 0, bodyIsLossy = false) { false },
        )
    }

    /**
     * Offline, dropped socket, a row that is gone: the proof cannot be ESTABLISHED. An unknown
     */
    @Test fun anAddressingThatCannotBeReadIsUnknownAndKeepsTheIdOff() = runTest {
        assertNull(
            replaceableDraftIdOrNull("d1", attachmentCount = 0, bodyIsLossy = false) { id ->
                draftAddressingIsCarried(
                    replacesEmailId = id,
                    replacement = setOf("jordan.lee@masto.top"),
                    originalAddressing = { throw IOException("offline") },
                )
            },
        )
    }

    /** The id being replaced is the one the proof is asked about — not some other draft's. */
    @Test fun theProofIsAskedForTheDraftBeingReplaced() = runTest {
        val asked = mutableListOf<String>()
        replaceableDraftIdOrNull("imap:acct:Drafts:42", attachmentCount = 0, bodyIsLossy = false) { id ->
            asked += id
            true
        }
        assertEquals(listOf("imap:acct:Drafts:42"), asked)
    }

    /** No draft behind the send: nothing to destroy, nothing to prove, no server read. */
    @Test fun noDraftBehindTheSendAsksNoProofAndWritesNoId() = runTest {
        var asked = false
        assertNull(
            replaceableDraftIdOrNull(null, attachmentCount = 3, bodyIsLossy = true) {
                asked = true
                true
            },
        )
        assertFalse("no id to destroy must cost no server read", asked)
    }
}
