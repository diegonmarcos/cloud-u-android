// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.inputlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #1082: the crash "revertCommit check failed: we thought we were reverting "must", but before the
 * cursor we found "Must"". The grammar fixer capitalises the word after the commit, so the field
 * differs from the remembered word by case only. A tiny field model plays the real sequence.
 */
class RevertCommitCheckTest {
    /** The field as a string; the committed word and separator as InputLogic remembers them. */
    private class Field(var text: String)

    /** What InputLogic does on backspace after an auto-correction: revert, or a normal one-char delete. */
    private fun backspace(field: Field, typed: String, committed: String, separator: String): String {
        val deleteLength = committed.length + separator.length
        val actual = RevertCommitCheck.actualCommittedWord(committed, separator.length, field.text.takeLast(deleteLength))
        if (actual != null) { // revert: delete word and separator, put back what was typed
            field.text = field.text.dropLast(deleteLength) + typed + (if (separator == " ") "" else separator)
            return "reverted"
        }
        field.text = field.text.dropLast(1)
        return "plain delete"
    }

    @Test fun capitalisedAfterTheCommitStillReverts() {
        val field = Field("mst ")                  // typed "mst", space: autocorrected to "must" ...
        field.text = "must "
        field.text = "Must "                       // ... and the grammar fixer made it "Must"
        assertEquals("reverted", backspace(field, typed = "mst", committed = "must", separator = " "))
        assertEquals("mst", field.text)            // the typed word is back, no crash
    }

    @Test fun anUntouchedCommitStillReverts() {
        val field = Field("must ")
        assertEquals("reverted", backspace(field, "mst", "must", " "))
        assertEquals("mst", field.text)
    }

    @Test fun theRecordedWordIsTheFieldTextWithItsCase() {
        assertEquals("Must", RevertCommitCheck.actualCommittedWord("must", 1, "Must ").toString())
        assertEquals("must", RevertCommitCheck.actualCommittedWord("must", 1, "x must ").toString())
    }

    @Test fun aDifferentWordIsNotRevertedAndDoesNotThrow() {
        val field = Field("mist ")
        assertEquals("plain delete", backspace(field, "mst", "must", " "))
        assertEquals("mist", field.text)
    }

    @Test fun textThatIsTooShortOrMissingIsNotRevertedAndDoesNotThrow() {
        assertNull(RevertCommitCheck.actualCommittedWord("must", 1, "ust "))
        assertNull(RevertCommitCheck.actualCommittedWord("must", 1, ""))
        assertNull(RevertCommitCheck.actualCommittedWord("must", 1, null))
    }

    @Test fun nonSpaceSeparatorsAndEmptySeparator() {
        assertNotNull(RevertCommitCheck.actualCommittedWord("must", 1, "Must,"))
        assertNotNull(RevertCommitCheck.actualCommittedWord("must", 0, "Must"))
        assertNull(RevertCommitCheck.actualCommittedWord("must", 0, "Must "))
    }
}
