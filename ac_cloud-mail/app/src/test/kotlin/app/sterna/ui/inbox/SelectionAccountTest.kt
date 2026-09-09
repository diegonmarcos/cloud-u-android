package app.sterna.ui.inbox

import app.sterna.core.data.mail.EmailKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The move-to-folder picker offers the SELECTION's account folders, so a secondary-account message
 */
class SelectionAccountTest {
    private fun key(account: String?, id: String) = EmailKey(account, id)

    @Test
    fun `empty selection has no account`() {
        assertNull(selectionAccount(emptySet()))
    }

    @Test
    fun `a single-account selection resolves to that account`() {
        assertEquals("acct-A", selectionAccount(setOf(key("acct-A", "1"), key("acct-A", "2"))))
    }

    @Test
    fun `a selection spanning two accounts resolves to null`() {
        assertNull(selectionAccount(setOf(key("acct-A", "1"), key("acct-B", "2"))))
    }

    @Test
    fun `an unknown (null) account among known ones resolves to null`() {
        assertNull(selectionAccount(setOf(key("acct-A", "1"), key(null, "2"))))
    }

    @Test
    fun `an all-unknown selection resolves to null`() {
        assertNull(selectionAccount(setOf(key(null, "1"), key(null, "2"))))
    }
}
