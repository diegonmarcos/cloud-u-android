package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * What the client asks a server about SUBSCRIPTION, and what it does with the answer (#174).
 */
class SubscribedFoldersTest {

    /**
     * A server holding four mailboxes, subscribed to two of them plus one it no longer has
     */
    private fun server(lsub: (String) -> String = ::subscriptions) = FakeImapServer { tag, line ->
        when {
            line.startsWith("LSUB") -> lsub(tag)
            line.startsWith("LIST") ->
                "* LIST (\\HasNoChildren) \".\" \"INBOX\"\r\n" +
                    "* LIST (\\HasNoChildren) \".\" \"Corbeille\"\r\n" +
                    "* LIST (\\HasNoChildren) \".\" \"Archives\"\r\n" +
                    "* LIST (\\HasNoChildren) \".\" \"&BBAEQARFBDgEMg-\"\r\n" + ok(tag) // Архив
            else -> ok(tag)
        }
    }

    /** INBOX and the Cyrillic Архив are subscribed; "Vanished" is subscribed but no longer listed. */
    private fun subscriptions(tag: String) =
        "* LSUB (\\HasNoChildren) \".\" \"INBOX\"\r\n" +
            "* LSUB (\\HasNoChildren) \".\" \"&BBAEQARFBDgEMg-\"\r\n" +
            "* LSUB (\\HasNoChildren) \".\" \"Vanished\"\r\n" + ok(tag)

    private fun FakeImapServer.lsubLines() = issued().filter { it.startsWith("LSUB") }

    /**
     * T1 — the setting on: a second round trip asks for the subscriptions, and the folders come
     */
    @Test
    fun `with the setting on the folders carry what LSUB said`() {
        server().use { server ->
            val folders = server.session().use { it.listFolders(onlySubscribed = true) }

            assertEquals("""LSUB "" "*"""", server.lsubLines().single())
            assertEquals(
                mapOf("INBOX" to true, "Corbeille" to false, "Archives" to false, "Архив" to true),
                folders.associate { it.path to it.isSubscribed },
            )
        }
    }

    /**
     * T5 — a subscription naming a mailbox the LIST did not: the intersection IGNORES it. It does
     * not invent a folder, and it does not make the answer look wrong either.
     */
    @Test
    fun `a subscription to a mailbox that is gone adds no folder`() {
        server().use { server ->
            val folders = server.session().use { it.listFolders(onlySubscribed = true) }

            assertEquals(listOf("INBOX", "Corbeille", "Archives", "Архив"), folders.map { it.path })
        }
    }

    /**
     * T2 — the setting off (the default): NOT ONE LSUB leaves the client, and every folder is
     */
    @Test
    fun `with the setting off no LSUB is sent at all`() {
        server().use { server ->
            val folders = server.session().use { it.listFolders() }

            assertEquals("no LSUB may go out when nothing asked for one — ${server.issued()}", emptyList<String>(), server.lsubLines())
            assertEquals(listOf(true, true, true, true), folders.map { it.isSubscribed })
        }
    }

    /**
     * T3 — THE test of this panel. The server refuses the LSUB; the folder list still comes back
     */
    @Test
    fun `a refused LSUB leaves every folder subscribed`() {
        server { tag -> "$tag NO subscriptions unavailable\r\n" }.use { server ->
            val folders = server.session().use { it.listFolders(onlySubscribed = true) }

            assertTrue("the LSUB was never even sent, so this proves nothing", server.lsubLines().isNotEmpty())
            assertEquals(listOf("INBOX", "Corbeille", "Archives", "Архив"), folders.map { it.path })
            assertEquals(listOf(true, true, true, true), folders.map { it.isSubscribed })
        }
    }

    /**
     * T3b — the answer that arrives and says nothing: an OK with no LSUB line. Indistinguishable
     */
    @Test
    fun `an LSUB that names nothing hides nothing`() {
        server { tag -> "* OK nothing to say\r\n" + ok(tag) }.use { server ->
            val folders = server.session().use { it.listFolders(onlySubscribed = true) }

            assertEquals(listOf(true, true, true, true), folders.map { it.isSubscribed })
        }
    }

    /**
     * T4 — the two parsers must decode a mailbox name THE SAME WAY, or the intersection misses
     */
    @Test
    fun `a Cyrillic mailbox decodes to the same path on both sides`() {
        val wire = "&BBAEQARFBDgEMg-" // Архив
        val listed = parseListFolders(untagged("* LIST (\\HasNoChildren) \".\" \"$wire\"\r\n"))
        val subscribed = parseLsubPaths(untagged("* LSUB (\\HasNoChildren) \".\" \"$wire\"\r\n"))

        assertEquals(setOf("Архив"), subscribed)
        assertEquals(listOf("Архив"), listed.map { it.path })
        assertEquals(listOf(true), withSubscriptions(listed, subscribed).map { it.isSubscribed })
    }

    /**
     * The pure decision, with its arguments: what an LSUB that named OTHER folders does to the
     * one it did not name, and what an absent answer (`null`) does to all of them.
     */
    @Test
    fun `the intersection marks only what the answer named`() {
        val listed = parseListFolders(
            untagged("* LIST (\\HasNoChildren) \".\" \"INBOX\"\r\n") +
                untagged("* LIST (\\HasNoChildren) \".\" \"Archives\"\r\n"),
        )

        assertEquals(
            mapOf("INBOX" to true, "Archives" to false),
            withSubscriptions(listed, setOf("INBOX", "Vanished")).associate { it.path to it.isSubscribed },
        )
        assertEquals(listOf(true, true), withSubscriptions(listed, null).map { it.isSubscribed })
        assertEquals(listOf(true, true), withSubscriptions(listed, emptySet()).map { it.isSubscribed })
    }

    /** An LSUB line that carries no mailbox name at all is dropped, not turned into an empty path. */
    @Test
    fun `a malformed LSUB line yields no path`() {
        assertEquals(emptySet<String>(), parseLsubPaths(untagged("* LSUB (\\HasNoChildren)\r\n")))
        assertEquals(emptySet<String>(), parseLsubPaths(untagged("* LIST (\\HasNoChildren) \".\" \"INBOX\"\r\n")))
    }

    private fun untagged(line: String): List<List<Any?>> =
        listOf(ImapParser(ByteArrayInputStream(line.toByteArray(Charsets.UTF_8))).readResponse())
}
