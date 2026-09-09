package app.sterna.ui.settings

import app.sterna.core.data.account.StoredIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The account editor now has two ways to save: the button at the bottom and the Save offered by the
 */
class AccountEditorSaveTest {

    private val manual = StoredIdentity("man-1", "Alex", "alex@example.org", signature = "-- Alex")
    private val second = StoredIdentity("man-2", "Alex pro", "pro@example.org", signature = "-- Pro")
    private val server = StoredIdentity("srv-1", "Alex", "alex@work.example", signature = "")

    // --- What a Save writes ---------------------------------------------------------------------

    @Test fun bothSaveRoutesShareOneAnswer() {
        // There is a single decision to compare against: calling it twice with the editor's state
        // (button) and the same state at exit time (dialog) yields the same fields.
        val fromButton = accountSaveFields(listOf(manual, second), listOf(server), "man-2")
        val fromDialog = accountSaveFields(listOf(manual, second), listOf(server), "man-2")
        assertEquals(fromButton, fromDialog)
        assertEquals(listOf(manual, second), fromButton.identities)
        assertEquals("man-2", fromButton.defaultIdentityId)
    }

    @Test fun rowsLeftWithoutAnAddressAreDropped() {
        val blank = StoredIdentity("man-3", "Nameless", "", signature = "nope")
        val fields = accountSaveFields(listOf(manual, blank), emptyList(), null)
        assertEquals(listOf(manual), fields.identities)
    }

    @Test fun theLegacySignatureMirrorsTheFirstManualIdentity() {
        assertEquals("-- Alex", accountSaveFields(listOf(manual, second), emptyList(), null).signature)
        // Nothing manual left (server-only account): no legacy signature to mirror.
        assertEquals("", accountSaveFields(emptyList(), listOf(server), null).signature)
    }

    @Test fun onlyTheManualListIsPersisted() {
        // Server identities re-merge live on read; freezing them into the manual list was the old
        // fold that polluted storage.
        val fields = accountSaveFields(listOf(manual), listOf(server), null)
        assertEquals(listOf(manual), fields.identities)
        assertFalse(fields.identities.any { it.id == "srv-1" })
    }

    @Test fun aFrozenCopyOfAServerIdentityIsHealedAway() {
        val frozen = server.copy(id = "man-frozen")
        val fields = accountSaveFields(listOf(manual, frozen), listOf(server), null)
        assertEquals(listOf(manual), fields.identities)
    }

    // --- The default sender ----------------------------------------------------------------------

    @Test fun aDefaultPointingAtAServerIdentityIsKept() {
        assertEquals("srv-1", accountSaveFields(listOf(manual), listOf(server), "srv-1").defaultIdentityId)
    }

    @Test fun aDefaultPointingAtADeletedRowIsDropped() {
        // Degrades to "the first one" rather than dangling at save time.
        assertNull(accountSaveFields(listOf(manual), listOf(server), "man-gone").defaultIdentityId)
        // Including when the row it pointed at was the one blanked out of the editor.
        val blanked = second.copy(email = "")
        assertNull(accountSaveFields(listOf(manual, blanked), emptyList(), "man-2").defaultIdentityId)
    }

    @Test fun noDefaultStaysNoDefault() {
        assertNull(accountSaveFields(listOf(manual), emptyList(), null).defaultIdentityId)
    }

    // --- When Save may be offered at all ---------------------------------------------------------

    private fun jmap(username: String = "alex@example.org", server: String = "jmap.example.org") =
        canSaveAccount(username, isImap = false, server = server, imapHost = "", imapPort = "", smtpHost = "", smtpPort = "")

    private fun imap(
        username: String = "alex@example.org",
        imapHost: String = "imap.example.org", imapPort: String = "993",
        smtpHost: String = "smtp.example.org", smtpPort: String = "587",
    ) = canSaveAccount(username, isImap = true, server = "", imapHost = imapHost, imapPort = imapPort, smtpHost = smtpHost, smtpPort = smtpPort)

    @Test fun aCompleteFormCanBeSavedEitherWay() {
        assertTrue(jmap())
        assertTrue(imap())
    }

    @Test fun aHalfFilledFormCannotBeSavedFromTheDialogEither() {
        // The dialog's Save is gated by the same rule as the button, so leaving through it can
        // never write an account the button itself refused.
        assertFalse(jmap(username = ""))
        assertFalse(jmap(server = ""))
        assertFalse(imap(username = ""))
        assertFalse(imap(imapHost = ""))
        assertFalse(imap(smtpHost = ""))
    }

    @Test fun aPortThatIsNotANumberCountsAsMissing() {
        assertFalse(imap(imapPort = ""))
        assertFalse(imap(imapPort = "not-a-port"))
        assertFalse(imap(smtpPort = "58 7"))
    }

    @Test fun theOtherProtocolsFieldsAreIgnored() {
        // A JMAP account is judged on its server URL alone, whatever the IMAP fields hold...
        assertTrue(canSaveAccount("alex@example.org", isImap = false, server = "jmap.example.org",
            imapHost = "", imapPort = "nonsense", smtpHost = "", smtpPort = ""))
        // ...and an IMAP account on its hosts and ports alone, with no server URL at all.
        assertTrue(canSaveAccount("alex@example.org", isImap = true, server = "",
            imapHost = "imap.example.org", imapPort = "993", smtpHost = "smtp.example.org", smtpPort = "587"))
    }
}
