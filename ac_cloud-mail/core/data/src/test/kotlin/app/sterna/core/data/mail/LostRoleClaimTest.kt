package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [isLostRoleClaim], EXECUTED on the values the cache actually holds.
 */
class LostRoleClaimTest {

    @Test fun `a marked claim is a lost claim`() {
        assertTrue("~trash", isLostRoleClaim("~trash"))
        assertTrue("~junk", isLostRoleClaim("~junk"))
        assertTrue("~spam", isLostRoleClaim("~spam"))
    }

    @Test fun `an elected role is not a lost claim`() {
        assertFalse("trash", isLostRoleClaim("trash"))
        assertFalse("junk", isLostRoleClaim("junk"))
        assertFalse("spam", isLostRoleClaim("spam"))
        assertFalse("inbox", isLostRoleClaim("inbox"))
        assertFalse("sent", isLostRoleClaim("sent"))
        assertFalse("archive", isLostRoleClaim("archive"))
    }

    @Test fun `no role at all is not a lost claim`() {
        assertFalse("a folder that never claimed anything must answer false", isLostRoleClaim(null))
        assertFalse("an empty stored role is not a mark", isLostRoleClaim(""))
    }

    @Test fun `a role this app does not know is not a lost claim either`() {
        assertFalse(isLostRoleClaim("important"))
        assertFalse(isLostRoleClaim("all"))
        assertFalse(isLostRoleClaim("XLIST-Whatever"))
    }

    /**
     * The mark is a PREFIX, and the two readings of it must agree on every input: a value this
     */
    @Test fun `it is the exact complement of the erasure, on every value tried here`() {
        val stored = listOf(null, "", "trash", "junk", "spam", "inbox", "~trash", "~junk", "~spam", "x~junk")
        assertEquals(
            "the erasure and the claim reading disagree about a stored role",
            listOf(false, false, false, false, false, false, true, true, true, false),
            stored.map { isLostRoleClaim(it) },
        )
        assertEquals(
            listOf(null, "", "trash", "junk", "spam", "inbox", null, null, null, "x~junk"),
            stored.map { unmarkedRole(it) },
        )
    }
}
