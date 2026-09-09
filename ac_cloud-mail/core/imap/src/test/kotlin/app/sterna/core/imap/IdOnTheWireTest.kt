package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The RFC 2971 `ID` command as it really leaves the client, driven against a scripted server on
 */
class IdOnTheWireTest {

    /**
     * A server advertising [capabilities] on top of IMAP4rev1. It can announce them the two ways
     */
    private fun server(
        vararg capabilities: String,
        advertiseAtLogin: Boolean = true,
        idAnswer: (String) -> String = { ok(it) },
    ) = FakeImapServer { tag, line ->
        val advertised = capabilities.joinToString(" ")
        when {
            advertiseAtLogin && (line.startsWith("LOGIN") || line.startsWith("AUTHENTICATE")) ->
                "$tag OK [CAPABILITY IMAP4rev1 $advertised] logged in\r\n"
            line.startsWith("CAPABILITY") -> "* CAPABILITY IMAP4rev1 $advertised\r\n" + ok(tag)
            line.substringBefore(' ') == "ID" -> idAnswer(tag)
            line.startsWith("SELECT") -> selectResponse(tag, exists = 7)
            else -> ok(tag)
        }
    }

    /**
     * Every `ID` line received, whole. Matched on the VERB and not on a prefix of the arguments,
     */
    private fun FakeImapServer.idLines(): List<String> =
        issued().filter { it.substringBefore(' ') == "ID" }

    /**
     * The verbs of every line received, LOGIN and AUTHENTICATE included — the ORDER witness.
     */
    private fun FakeImapServer.verbs(): List<String> =
        commands.toList().map { it.substringBefore(' ') }

    /**
     * The fix itself: a server that says it knows ID is told the client's name and version, after
     * the login and before the first SELECT — the window NetEase judges.
     */
    @Test
    fun `a server advertising ID is told the name and version, after login and before SELECT`() {
        server("ID").use { server ->
            val status = ImapClient("1.2.3").openSession(server.config).use { it.select("INBOX") }

            assertEquals(listOf("""ID ("name" "Sterna Mail" "version" "1.2.3")"""), server.idLines())
            assertEquals(listOf("LOGIN", "ID", "SELECT", "LOGOUT"), server.verbs())
            assertEquals(7, status.exists)
        }
    }

    /**
     * The guard. A server that PUBLISHED its capability list and put no ID in it gets none, and
     * the session is exactly the one it has always been.
     */
    @Test
    fun `a server that never advertised ID is sent none`() {
        server("UIDPLUS").use { server ->
            val status = ImapClient("1.2.3").openSession(server.config).use { it.select("INBOX") }

            assertEquals(emptyList<String>(), server.idLines())
            assertEquals(listOf("""SELECT "INBOX"""", "LOGOUT"), server.issued())
            assertEquals(7, status.exists)
        }
    }

    /**
     * The heart of the best-effort: a server that advertises ID and then answers NO to it must
     */
    @Test
    fun `a server refusing the ID keeps a usable session`() {
        server("ID", idAnswer = { "$it NO [SERVERBUG] ID not supported\r\n" }).use { server ->
            val status = ImapClient("1.2.3").openSession(server.config).use { it.select("INBOX") }

            assertEquals(listOf("""ID ("name" "Sterna Mail" "version" "1.2.3")"""), server.idLines())
            assertEquals(listOf("LOGIN", "ID", "SELECT", "LOGOUT"), server.verbs())
            assertEquals(7, status.exists)
        }
    }

    /**
     * The OAuth half of the same door: an account authenticating with XOAUTH2 (Outlook/Gmail)
     */
    @Test
    fun `an XOAUTH2 session names itself too`() {
        server("ID").use { server ->
            val config = server.config.copy(accessToken = "tok-abc")
            val status = ImapClient("1.2.3").openSession(config).use { it.select("INBOX") }

            assertEquals(listOf("""ID ("name" "Sterna Mail" "version" "1.2.3")"""), server.idLines())
            assertEquals(listOf("AUTHENTICATE", "ID", "SELECT", "LOGOUT"), server.verbs())
            assertEquals(7, status.exists)
        }
    }

    /**
     * A version string carrying CR/LF sends NOTHING. `quote` refuses the newline, the refusal
     */
    @Test
    fun `a version carrying a newline sends no ID and no injected command`() {
        server("ID").use { server ->
            val client = ImapClient("9.9\r\nA0001 CREATE \"evil\"")
            val status = client.openSession(server.config).use { it.select("INBOX") }

            assertEquals(emptyList<String>(), server.idLines())
            // Whole list, not just the absence of an ID: the injected CREATE would show up here.
            assertEquals(listOf("""SELECT "INBOX"""", "LOGOUT"), server.issued())
            assertEquals(7, status.exists)
        }
    }

    /**
     * No version known (the default constructor, which is what every test and every non-`:app`
     */
    @Test
    fun `a mute server is asked, then named, in that order`() {
        server("ID", advertiseAtLogin = false).use { server ->
            val status = ImapClient().openSession(server.config).use { it.select("INBOX") }

            assertEquals(listOf("""ID ("name" "Sterna Mail")"""), server.idLines())
            assertEquals(
                listOf("CAPABILITY", """ID ("name" "Sterna Mail")""", """SELECT "INBOX"""", "LOGOUT"),
                server.issued(),
            )
            assertEquals(7, status.exists)
        }
    }

    /**
     * The other half of the question being PUT: a server that volunteered nothing and answers the
     */
    @Test
    fun `a mute server whose answer has no ID is asked and then left alone`() {
        server("UIDPLUS", advertiseAtLogin = false).use { server ->
            val status = ImapClient("1.2.3").openSession(server.config).use { it.select("INBOX") }

            assertEquals(emptyList<String>(), server.idLines())
            assertEquals(listOf("CAPABILITY", """SELECT "INBOX"""", "LOGOUT"), server.issued())
            assertEquals(7, status.exists)
        }
    }

    /**
     * What the session KEEPS of the question above: nothing. It is put by hand —
     */
    @Test
    fun `asking for the ID capability leaves the session cache cold`() {
        server("ID", advertiseAtLogin = false).use { server ->
            ImapClient().openSession(server.config).use { session ->
                // The reader after the ID: it must ask again, because nothing was kept.
                assertTrue("the session answered from a cache it should not have", session.hasCapability("ID"))
            }

            assertEquals(
                listOf("CAPABILITY", """ID ("name" "Sterna Mail")""", "CAPABILITY", "LOGOUT"),
                server.issued(),
            )
        }
    }
}
