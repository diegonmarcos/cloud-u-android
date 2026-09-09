package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The folder LIST as it really leaves the client, driven against a scripted server on loopback.
 */
class ListFoldersOnTheWireTest {

    /**
     * A server advertising [capabilities] on top of IMAP4rev1, listing one English and one French
     */
    private fun server(vararg capabilities: String, advertiseAtLogin: Boolean = false) = FakeImapServer { tag, line ->
        val advertised = capabilities.toList()
        when {
            // What Dovecot, Stalwart and Gmail actually do: the list rides in the LOGIN
            // completion's `[CAPABILITY …]` response code and no CAPABILITY command is ever sent.
            advertiseAtLogin && line.startsWith("LOGIN") ->
                "$tag OK [CAPABILITY IMAP4rev1 ${advertised.joinToString(" ")}] logged in\r\n"
            line.startsWith("CAPABILITY") ->
                "* CAPABILITY IMAP4rev1 ${advertised.joinToString(" ")}\r\n" + ok(tag)
            line.startsWith("LIST") -> when {
                "RETURN" in line && "SPECIAL-USE" !in advertised ->
                    "$tag BAD unknown LIST return option\r\n"
                "RETURN" in line -> listing(tag, trashAttributes = "\\Trash \\HasNoChildren")
                else -> listing(tag, trashAttributes = "\\HasNoChildren")
            }
            else -> ok(tag)
        }
    }

    private fun listing(tag: String, trashAttributes: String) =
        "* LIST (\\HasNoChildren) \".\" \"INBOX\"\r\n" +
            "* LIST ($trashAttributes) \".\" \"Corbeille\"\r\n" + ok(tag)

    private fun FakeImapServer.listLine(): String = issued().single { it.startsWith("LIST") }

    /**
     * The fix itself: on a server that says it knows SPECIAL-USE, the attributes are asked for by
     * name, and the French trash comes back with a role instead of nothing.
     */
    @Test
    fun `a server advertising SPECIAL-USE is asked for the attributes by name`() {
        // SPECIAL-USE and nothing else, deliberately: it is the capability RFC 6154 §2 has a
        // special-use server advertise, and it is the one the guard must be reading. A server
        // announcing LIST-EXTENDED as well would make this test pass on either name.
        server("SPECIAL-USE").use { server ->
            val folders = server.session().use { it.listFolders() }

            assertEquals("""LIST "" "*" RETURN (SPECIAL-USE)""", server.listLine())
            assertEquals("trash", folders.single { it.path == "Corbeille" }.role)
        }
    }

    /**
     * The guard, and the reason it is not a detail: a server that never advertised the extension
     */
    @Test
    fun `a server that never advertised SPECIAL-USE still gets the bare LIST`() {
        server("UIDPLUS").use { server ->
            val folders = server.session().use { it.listFolders() }

            assertEquals("""LIST "" "*"""", server.listLine())
            // The witness: the folders are still there. Had the return option gone out anyway,
            // this server's BAD would have thrown and left the account with no mailboxes at all.
            assertEquals(listOf("INBOX", "Corbeille"), folders.map { it.path })
            // This server withholds the attributes, so the role can only come from the name —
            // which is now read in nine languages (`FolderRolesTest`), French among them.
            assertEquals("trash", folders.single { it.path == "Corbeille" }.role)
        }
    }

    /**
     * THE case the two above cannot stand in for: the capability list arrives in the LOGIN
     */
    @Test
    fun `the capability list volunteered at login is what the guard reads`() {
        server("SPECIAL-USE", advertiseAtLogin = true).use { server ->
            val folders = server.session().use { it.listFolders() }

            assertEquals("""LIST "" "*" RETURN (SPECIAL-USE)""", server.listLine())
            assertTrue(
                "the login list was not kept: a CAPABILITY round trip was made — ${server.issued()}",
                server.issued().none { it.startsWith("CAPABILITY") },
            )
            assertEquals("trash", folders.single { it.path == "Corbeille" }.role)
        }
    }

    /**
     * A source lint on the whole body that builds the two commands, pinned LINE BY LINE. It is a
     */
    @Test
    fun `the LIST command line is built under the capability guard`() {
        assertEquals(
            listOf(
                "fun listFolders(onlySubscribed: Boolean = false): List<ImapFolder> {",
                """val list = if (hasCapability("SPECIAL-USE")) "LIST \"\" \"*\" RETURN (SPECIAL-USE)" else "LIST \"\" \"*\"""" + "\"",
                "val folders = parseListFolders(command(list).untagged)",
                "if (!onlySubscribed) return folders",
                """val subscribed = runCatching { parseLsubPaths(command("LSUB \"\" \"*\"").untagged) }.getOrNull()""",
                "return withSubscriptions(folders, subscribed)",
            ),
            sourceBlock("fun listFolders(", 6),
        )
    }

    /**
     * [count] consecutive CODE lines of `ImapClient.kt` from the single one starting with [prefix],
     */
    private fun sourceBlock(prefix: String, count: Int): List<String> {
        val path = "core/imap/src/main/kotlin/app/sterna/core/imap/ImapClient.kt"
        val root = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .firstOrNull { java.io.File(it, path).isFile }
            ?: error("cannot locate the repo root from ${java.io.File("").absolutePath}")
        val code = java.io.File(root, path).readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
        val starts = code.indices.filter { code[it].startsWith(prefix) }
        val start = starts.singleOrNull()
            ?: return listOf("«${starts.size} code lines start with `$prefix`»")
        return code.subList(start, minOf(start + count, code.size))
    }
}
