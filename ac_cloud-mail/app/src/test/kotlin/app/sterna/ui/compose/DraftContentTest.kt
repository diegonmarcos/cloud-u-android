package app.sterna.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one rule that decides whether leaving compose persists a draft (#69). Both directions matter
 */
class DraftContentTest {

    private fun hasContent(
        to: String = "",
        cc: String = "",
        bcc: String = "",
        subject: String = "",
        body: String = "",
        attachment: Boolean = false,
    ) = draftHasContent(to, cc, bcc, subject, body, attachment)

    // --- A recipient is content (#69) -----------------------------------------------------------

    @Test fun aRecipientAloneIsWorthSaving() {
        assertTrue(hasContent(to = "alex@example.org"))
    }

    @Test fun aCcAloneIsWorthSaving() {
        assertTrue(hasContent(cc = "alex@example.org"))
    }

    @Test fun aBccAloneIsWorthSaving() {
        assertTrue(hasContent(bcc = "alex@example.org"))
    }

    @Test fun aRecipientStillBeingTypedIsWorthSaving() {
        // The chips field keeps committed addresses plus the token in progress in one string; a
        // half-typed address is what the user would lose, so it counts too.
        assertTrue(hasContent(to = "alex@example.org, jord"))
    }

    @Test fun semicolonSeparatedRecipientsCount() {
        assertTrue(hasContent(to = "alex@example.org;jordan@example.org"))
    }

    // --- ...but only a real one -----------------------------------------------------------------

    @Test fun aWhollyEmptyComposeIsNotWorthSaving() {
        assertFalse(hasContent())
    }

    @Test fun blanksInARecipientFieldAreNotAnAddress() {
        assertFalse(hasContent(to = "   "))
        assertFalse(hasContent(cc = "\n\t "))
        assertFalse(hasContent(bcc = " "))
    }

    @Test fun separatorsAloneAreNotAnAddress() {
        assertFalse(hasContent(to = ","))
        assertFalse(hasContent(to = " , ; , "))
        assertFalse(hasContent(cc = ";;"))
    }

    @Test fun blankRecipientsWithBlankSubjectAndBodyStayEmpty() {
        // The exact shape of "opened compose, typed nothing, left": nothing may be persisted, and
        // an opened draft emptied to this must have its original deleted rather than re-saved.
        assertFalse(hasContent(to = " ", cc = " ", bcc = " ", subject = "  ", body = "\n "))
    }

    // --- 1.3.12 non-regression: the other content still counts on its own ------------------------

    @Test fun aSubjectAloneIsWorthSaving() {
        assertTrue(hasContent(subject = "Lunch"))
    }

    @Test fun aBodyAloneIsWorthSaving() {
        assertTrue(hasContent(body = "see you at noon"))
    }

    @Test fun anAttachmentAloneIsWorthSaving() {
        assertTrue(hasContent(attachment = true))
    }

    // --- The shared address parser the rule leans on --------------------------------------------

    @Test fun parseAddrsDropsBlanksAndSeparators() {
        assertEquals(emptyList<String>(), parseAddrs(""))
        assertEquals(emptyList<String>(), parseAddrs("   "))
        assertEquals(emptyList<String>(), parseAddrs(" , ; "))
        assertEquals(
            listOf("alex@example.org", "jordan@example.org"),
            parseAddrs(" alex@example.org , jordan@example.org ; "),
        )
    }
}
