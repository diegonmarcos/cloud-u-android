// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.autocomplete

import com.diegonmarcos.superapp.autofill.AutocompleteContract
import com.diegonmarcos.superapp.autofill.AutocompleteContract.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1103 item 3: the pages IDs, Addresses, URLs, Secrets; the Secrets masked by default and revealed only behind the device check. */
class AutocompleteEngineTest {
    private val secret = Row("secrets", "Wi-Fi", "correct-horse")
    private val id = Row("ids", "DNI", "X1234567Z")

    @Test fun thePagesAreExactlyTheFourInOrderAndMatchTheWireContract() {
        assertEquals(listOf("IDs", "Addresses", "URLs", "Secrets"), AutocompletePage.entries.map { it.title })
        assertEquals(AutocompleteContract.PAGES, AutocompleteEngine.declaredPages())
        assertEquals(listOf(AutocompletePage.SECRETS), AutocompletePage.entries.filter { it.masked })
        assertNull(AutocompletePage.of("passwords"))
    }

    @Test fun secretsAreMaskedByDefaultAndShownOnlyOnceUnlocked() {
        assertEquals(AutocompleteEngine.MASK, AutocompleteEngine.shown(secret, unlocked = false))
        assertEquals("correct-horse", AutocompleteEngine.shown(secret, unlocked = true))
        assertEquals("X1234567Z", AutocompleteEngine.shown(id, unlocked = false)) // the other pages are plain
        assertFalse(AutocompleteEngine.shown(secret, false).contains("correct"))
    }

    @Test fun aLockedSecretAsksForTheDeviceCheckBeforeItPastesOrCopies() {
        assertEquals(AutocompleteEngine.Access.NEEDS_UNLOCK, AutocompleteEngine.access(secret, unlocked = false))
        assertEquals(AutocompleteEngine.Access.USE, AutocompleteEngine.access(secret, unlocked = true))
        assertEquals(AutocompleteEngine.Access.USE, AutocompleteEngine.access(id, unlocked = false))
        assertTrue(AutocompleteEngine.isSensitive(secret))
        assertFalse(AutocompleteEngine.isSensitive(id))
    }

    @Test fun theUnlockLapsesAndIsForgottenWhenThePanelCloses() {
        AutocompleteUnlock.lock()
        assertFalse(AutocompleteUnlock.isUnlocked(100L))
        AutocompleteUnlock.grant(1_000L)
        assertTrue(AutocompleteUnlock.isUnlocked(1_000L + AutocompleteUnlock.TTL_MS - 1))
        assertFalse(AutocompleteUnlock.isUnlocked(1_000L + AutocompleteUnlock.TTL_MS))
        AutocompleteUnlock.lock()
        assertFalse(AutocompleteUnlock.isUnlocked(1_001L))
    }

    @Test fun aPageShowsOnlyItsOwnRows() {
        val all = listOf(id, secret, Row("urls", "Blog", "https://example.org"))
        assertEquals(listOf(id), AutocompleteEngine.rows(all, AutocompletePage.IDS))
        assertEquals(listOf(secret), AutocompleteEngine.rows(all, AutocompletePage.SECRETS))
        assertTrue(AutocompleteEngine.rows(all, AutocompletePage.ADDRESSES).isEmpty())
    }
}
