package app.sterna.ui.message

import app.sterna.core.jmap.model.EmailAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [originalSenderToShow], EXECUTED — whether the reader gets an "Original sender" line at all, and
 */
class OriginalSenderToShowTest {

    private val alias = listOf(EmailAddress(name = "emon", email = "alias@alias.example"))

    @Test fun `a name and an angle-bracket address are split`() {
        val shown = originalSenderToShow("emon <noreply@codeberg.org>", alias)

        assertNotNull(shown)
        assertEquals("emon", shown!!.name)
        assertEquals("noreply@codeberg.org", shown.email)
    }

    @Test fun `a bare address with no name is read whole`() {
        val shown = originalSenderToShow("noreply@codeberg.org", alias)

        assertNotNull(shown)
        assertNull("nothing precedes the address, so there is no name", shown!!.name)
        assertEquals("noreply@codeberg.org", shown.email)
    }

    /**
     * Case is not identity. Addresses come back from a relay in whatever casing it felt like,
     */
    @Test fun `an origin equal to the From, in another casing, shows nothing`() {
        assertNull(
            originalSenderToShow(
                "NoReply@Codeberg.org",
                listOf(EmailAddress(name = "emon", email = "noreply@codeberg.org")),
            ),
        )
    }

    /**
     * THE refusal. This header is entirely sender-controlled, and the line it feeds is read as
     */
    @Test fun `a value with no address in it shows nothing`() {
        assertNull(originalSenderToShow("(aucune adresse ici)", alias))
        assertNull(originalSenderToShow("unknown", alias))
    }

    @Test fun `an absent or blank header shows nothing`() {
        assertNull(originalSenderToShow(null, alias))
        assertNull(originalSenderToShow("   ", alias))
        assertNull(originalSenderToShow("", alias))
    }

    /** Nothing to compare against is not a reason to stay silent: a cold cache has no From yet. */
    @Test fun `an empty From does not suppress the line`() {
        val shown = originalSenderToShow("Ann Lee <ann@example.org>", emptyList())

        assertNotNull(shown)
        assertEquals("Ann Lee", shown!!.name)
        assertEquals("ann@example.org", shown.email)
    }
}
