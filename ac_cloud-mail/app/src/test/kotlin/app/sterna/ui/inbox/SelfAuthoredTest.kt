package app.sterna.ui.inbox

import app.sterna.core.data.account.StoredIdentity
import app.sterna.core.jmap.model.EmailAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The per-row "is this the user's own mail" predicate behind showing "To: …" instead of the
 *  self sender wherever a self-authored message is read, e.g. once moved to Trash (Codeberg #69). */
class SelfAuthoredTest {

    private fun identities(vararg addrs: String) =
        addrs.map { StoredIdentity(id = it, name = "", email = it) }

    @Test fun `matches an identity case- and space-insensitively`() {
        val from = listOf(EmailAddress(name = "Me", email = "  Me@Example.COM "))
        assertTrue(isSelfAuthored(from, identities("me@example.com")))
    }

    @Test fun `non-matching sender is not self-authored`() {
        val from = listOf(EmailAddress(email = "someone.else@example.com"))
        assertFalse(isSelfAuthored(from, identities("me@example.com")))
    }

    @Test fun `absent sender is not self-authored`() {
        assertFalse(isSelfAuthored(emptyList(), identities("me@example.com")))
    }

    @Test fun `blank sender is not self-authored`() {
        assertFalse(isSelfAuthored(listOf(EmailAddress(email = "   ")), identities("me@example.com")))
    }

    @Test fun `no identities means never self-authored`() {
        val from = listOf(EmailAddress(email = "me@example.com"))
        assertFalse(isSelfAuthored(from, emptyList()))
    }
}
