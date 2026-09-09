package app.sterna.core.data.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The read-receipt switch's stored value, RUN — the first of the two locks on #148.
 */
class ReadReceiptSettingDefaultTest {

    @Test fun `nobody is asked until they ask to be asked`() {
        assertFalse(
            "a reader who has never seen this switch must never be offered to answer a sender's " +
                "read-receipt request",
            askReadReceiptFrom(emptyPreferences()),
        )
        assertFalse("and the named default says so too", ASK_READ_RECEIPT_DEFAULT)
    }

    @Test fun `the stored answer is honoured in both directions`() {
        assertTrue(askReadReceiptFrom(preferencesOf(storedKey to true)))
        assertFalse(askReadReceiptFrom(preferencesOf(storedKey to false)))
    }

    /**
     * The key is spelled out here rather than imported: it names persisted user data. Renaming it
     */
    @Test fun `the switch is read back from the key it was written to`() {
        assertTrue(askReadReceiptFrom(preferencesOf(booleanPreferencesKey("ask_read_receipt") to true)))
        // A neighbouring switch must not answer for it.
        assertFalse(askReadReceiptFrom(preferencesOf(booleanPreferencesKey("confirm_links") to true)))
    }

    /** The persisted key, spelled as the store spells it — see the test above. */
    private val storedKey = booleanPreferencesKey("ask_read_receipt")
}
