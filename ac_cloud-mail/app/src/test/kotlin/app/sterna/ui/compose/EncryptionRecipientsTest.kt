package app.sterna.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The composer's lock says whether the message will leave encrypted. These are the two pure rules
 * behind it (#35): which tokens count as recipients, and when the answer has to be computed again.
 */
class EncryptionRecipientsTest {

    // --- Which tokens count -----------------------------------------------------------------

    @Test fun noRecipientsAtAll() {
        assertEquals(emptyList<String>(), encryptionRecipients("", "", ""))
        assertEquals(emptyList<String>(), encryptionRecipients("  ", " , ; ", ""))
    }

    @Test fun aTokenBeingTypedIsNotARecipient() {
        listOf("b", "bo", "bob", "bob@", "bob@exa", "bob@example", "bob@example.", "bob@example.c")
            .forEach { assertEquals("should not count yet: $it", emptyList<String>(), encryptionRecipients(it, "", "")) }
    }

    @Test fun aCompleteAddressCounts() {
        assertEquals(listOf("bob@example.com"), encryptionRecipients("bob@example.com", "", ""))
        assertEquals(listOf("bob@example.co"), encryptionRecipients(" bob@example.co ", "", ""))
    }

    @Test fun readsToCcAndBcc() {
        assertEquals(
            listOf("a@x.com", "b@x.com", "c@x.com"),
            encryptionRecipients("a@x.com", "b@x.com", "c@x.com"),
        )
    }

    @Test fun sameAddressTwiceCountsOnce() {
        assertEquals(listOf("a@x.com"), encryptionRecipients("a@x.com", "a@x.com", ""))
    }

    @Test fun committedAddressesSurviveTheTokenBeingTyped() {
        // One chip, then the second recipient half typed: the chip still decides the state.
        assertEquals(listOf("alex@x.com"), encryptionRecipients("alex@x.com, jo", "", ""))
        assertEquals(listOf("alex@x.com"), encryptionRecipients("alex@x.com, jordan@", "", ""))
        assertEquals(
            listOf("alex@x.com", "jordan@y.org"),
            encryptionRecipients("alex@x.com, jordan@y.org", "", ""),
        )
    }

    @Test fun aCommittedButMalformedTokenIsNotARecipient() {
        // Blocked at the send gate as an invalid address; it must not make the lock claim anything.
        assertEquals(listOf("alex@x.com"), encryptionRecipients("alex@x.com, nonsense", "", ""))
    }

    // --- When to recompute ------------------------------------------------------------------

    @Test fun theFirstEvaluationAlwaysRuns() {
        assertTrue(recipientKeysStale(null, emptyList()))
        assertTrue(recipientKeysStale(null, listOf("a@x.com")))
    }

    @Test fun anUnchangedSetIsNotRecomputed() {
        assertFalse(recipientKeysStale(emptyList(), emptyList()))
        assertFalse(recipientKeysStale(listOf("a@x.com"), listOf("a@x.com")))
        // Order is not part of the answer: the same addresses, moved around, need no lookup.
        assertFalse(recipientKeysStale(listOf("a@x.com", "b@x.com"), listOf("b@x.com", "a@x.com")))
    }

    @Test fun addingCompletingOrDeletingAnAddressRecomputes() {
        assertTrue(recipientKeysStale(emptyList(), listOf("a@x.com")))
        assertTrue(recipientKeysStale(listOf("a@x.com"), listOf("a@x.com", "b@x.com")))
        assertTrue(recipientKeysStale(listOf("a@x.com", "b@x.com"), listOf("a@x.com")))
        assertTrue(recipientKeysStale(listOf("a@x.com"), emptyList()))
    }

    // --- The two rules together, over a keystroke-by-keystroke edit --------------------------

    /** The recipient sets the state would be recomputed for, replaying [fields] keystroke by
     *  keystroke exactly as the composer calls it. */
    private fun recomputations(fields: List<String>): List<List<String>> {
        var previous: List<String>? = null
        val runs = mutableListOf<List<String>>()
        fields.forEach { value ->
            val current = encryptionRecipients(value, "", "")
            if (recipientKeysStale(previous, current)) {
                previous = current
                runs += current
            }
        }
        return runs
    }

    /** Every prefix of [value], i.e. the field as it goes by while the address is typed. */
    private fun typed(value: String, from: String = ""): List<String> =
        (from.length..value.length).map { value.substring(0, it) }.filter { it.startsWith(from) }

    @Test fun typingTheFirstRecipientOnlyRecomputesOnceItIsAnAddress() {
        val runs = recomputations(typed("alex@x.io"))
        // The empty field (the state the composer opens with) and then the finished address.
        assertEquals(listOf(emptyList(), listOf("alex@x.io")), runs)
    }

    @Test fun typingASecondRecipientLeavesTheStateAloneUntilItIsAnAddress() {
        val fields = typed("alex@x.io, jordan@y.io", from = "alex@x.io")
        val runs = recomputations(fields)
        assertEquals(
            listOf(listOf("alex@x.io"), listOf("alex@x.io", "jordan@y.io")),
            runs,
        )
    }

    @Test fun aShorterPlausibleAddressOnTheWayIsItsOwnAnswer() {
        // The bounded limit of the rule: `bob@x.co` IS an address, so typing towards `bob@x.com`
        // passes through a state that is evaluated on its own. Two evaluations for the whole word
        // instead of one per keystroke — and both of them about something the user did write.
        assertEquals(
            listOf(emptyList(), listOf("bob@x.co"), listOf("bob@x.com")),
            recomputations(typed("bob@x.com")),
        )
    }

    @Test fun deletingARecipientRecomputes() {
        val runs = recomputations(
            listOf("alex@x.com, jordan@y.org", "alex@x.com, ", "alex@x.com", "alex@x.co", ""),
        )
        assertEquals(
            listOf(
                listOf("alex@x.com", "jordan@y.org"),
                listOf("alex@x.com"),
                listOf("alex@x.co"),
                emptyList(),
            ),
            runs,
        )
    }
}
