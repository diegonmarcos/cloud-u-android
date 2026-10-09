package app.sterna.core.data.mail

import app.sterna.core.data.db.DomainIndexRow
import app.sterna.core.data.db.FromDomainUpdate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Group by Domain": which domain a From address is filed under. */
class SenderDomainTest {
    private fun reg(address: String?) = SenderDomain.registrable(address)

    @Test fun `a subdomain is grouped with its registrable domain`() {
        assertEquals("github.com", reg("noreply@github.com"))
        assertEquals("github.com", reg("notifications@notifications.github.com"))
        assertEquals("github.com", reg("a@deep.mail.notifications.github.com"))
        assertEquals("x.io", reg("amy@x.io"))
    }

    @Test fun `multi-label public suffixes keep the label before them`() {
        assertEquals("example.co.uk", reg("news@mail.example.co.uk"))
        assertEquals("example.co.uk", reg("news@example.co.uk"))
        assertEquals("bbc.org.uk", reg("x@a.b.bbc.org.uk"))
        assertEquals("loja.com.br", reg("pedidos@shop.loja.com.br"))
        assertEquals("bank.com.au", reg("alerts@secure.bank.com.au"))
        assertEquals("rakuten.co.jp", reg("info@mail.rakuten.co.jp"))
        // a bare suffix is all there is to group by
        assertEquals("co.uk", reg("odd@co.uk"))
        // a plain ccTLD is not a multi-label suffix
        assertEquals("example.uk", reg("x@mail.example.uk"))
    }

    @Test fun `case, spaces and trailing dots do not split a group`() {
        assertEquals("github.com", reg("  Someone@Notifications.GitHub.COM  "))
        assertEquals("github.com", reg("someone@github.com."))
        assertEquals("example.co.uk", reg("X@MAIL.Example.CO.UK."))
        assertEquals(reg("a@github.com"), reg("B@GITHUB.COM"))
    }

    @Test fun `the domain is after the last at sign`() {
        assertEquals("example.com", reg("\"odd@local\"@mail.example.com"))
    }

    @Test fun `malformed or missing addresses have no domain and read as unknown`() {
        for (bad in listOf(null, "", "   ", "no-at-sign", "user@", "user@.", "user@a..b", "user@.example.com",
            "user@exa mple.com", "user@[192.0.2.1]", "user@-bad-.com")) {
            assertNull("'$bad' has no domain", reg(bad))
            assertEquals("", SenderDomain.indexed(bad))
        }
        assertEquals(SenderDomain.UNKNOWN, SenderDomain.label(SenderDomain.indexed("broken")))
        assertEquals(SenderDomain.UNKNOWN, SenderDomain.label(null))
        assertEquals("github.com", SenderDomain.label("github.com"))
    }

    @Test fun `a single-label host is its own domain`() {
        assertEquals("localhost", reg("root@localhost"))
    }

    @Test fun `the list covers the suffixes the owner named`() {
        assertTrue(SenderDomain.MULTI_LABEL_SUFFIXES.containsAll(listOf("co.uk", "com.br", "com.au", "co.jp", "org.uk")))
    }

    @Test fun `the group key is prefixed so it cannot equal a thread id`() {
        val key = SenderDomain.domainKey("a@notifications.github.com")
        assertEquals(SenderDomain.DOMAIN_KEY_PREFIX + "github.com", key)
        assertEquals(key, SenderDomain.domainKey("b@GitHub.com"))
        assertTrue(SenderDomain.domainKey("broken").startsWith(SenderDomain.DOMAIN_KEY_PREFIX))
    }

    @Test fun `the backfill indexes every unindexed row in batches`() {
        val pending = mutableListOf(
            DomainIndexRow("a", "1", "x@notifications.github.com"),
            DomainIndexRow("a", "2", "y@shop.loja.com.br"),
            DomainIndexRow("a", "3", null),
        )
        val stored = mutableListOf<FromDomainUpdate>()
        val n = runBlocking {
            SenderDomain.backfill(
                batch = 2,
                next = { limit -> pending.take(limit).also { pending.removeAll(it) } },
                store = { stored += it },
            )
        }
        assertEquals(3, n)
        assertEquals(
            listOf(FromDomainUpdate("a", "1", "github.com"), FromDomainUpdate("a", "2", "loja.com.br"), FromDomainUpdate("a", "3", "")),
            stored,
        )
    }
}
