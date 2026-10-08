package app.sterna.agentapi

import app.sterna.agentapi.AgentMailContract.Parsed
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #913 the fleet agent door to mail: the contract a caller relies on, and the shape that keeps it read-only. */
class AgentMailContractTest {
    private fun ok(from: String? = null, subject: String? = null, since: String? = null, limit: String? = null) =
        (AgentMailContract.criteria(from, subject, since, limit) as Parsed.Ok).criteria

    private fun refused(from: String? = null, subject: String? = null, since: String? = null, limit: String? = null) =
        (AgentMailContract.criteria(from, subject, since, limit) as Parsed.Refused).why

    @Test fun `defaults are the whole mailbox, newest 50`() {
        val c = ok()
        assertNull(c.from); assertNull(c.subject)
        assertEquals(0L, c.since)
        assertEquals(50, c.limit)
    }

    @Test fun `filters are trimmed and blank means none`() {
        assertEquals("wg-gesucht.de", ok(from = "  wg-gesucht.de ").from)
        assertNull(ok(from = "   ").from)
        assertEquals("Neue Anzeige", ok(subject = "Neue Anzeige").subject)
    }

    @Test fun `since and limit are validated`() {
        assertEquals(1_700_000_000_000L, ok(since = "1700000000000").since)
        assertTrue(refused(since = "yesterday").contains("epoch millis"))
        assertTrue(refused(since = "-5").contains("negative"))
        assertEquals(200, ok(limit = "200").limit)
        assertEquals(1, ok(limit = "1").limit)
        assertTrue(refused(limit = "0").contains("1..200"))
        assertTrue(refused(limit = "201").contains("1..200"))
        assertTrue(refused(limit = "many").contains("not a number"))
    }

    @Test fun `an over-long filter is refused`() {
        assertTrue(refused(from = "x".repeat(201)).contains("from"))
        assertTrue(refused(subject = "y".repeat(201)).contains("subject"))
        assertEquals(200, ok(from = "x".repeat(200)).from!!.length)
    }

    @Test fun `like wildcards and the escape are made literal`() {
        assertEquals("a\\%b\\_c\\\\d", AgentMailContract.likeEscape("a%b_c\\d"))
        assertEquals("plain", AgentMailContract.likeEscape("plain"))
    }

    @Test fun `the statement binds every value and never splices one`() {
        val (sql, args) = AgentMailContract.sql(AgentMailContract.Criteria("wg'; DROP TABLE emails;--", "50%", 123L, 7))
        assertFalse(sql.contains("DROP"))
        assertFalse(sql.contains("wg-gesucht"))
        assertTrue(sql.startsWith("SELECT id, accountId, mailboxId, subject, fromName, fromEmail, receivedAt, sortKey, seen FROM emails WHERE sortKey >= ?"))
        assertTrue(sql.endsWith("ORDER BY sortKey DESC LIMIT ?"))
        assertEquals(5, sql.count { it == '?' })
        assertArrayEquals(arrayOf<Any>(123L, "%wg'; DROP TABLE emails;--%", "%wg'; DROP TABLE emails;--%", "%50\\%%", 7), args)
    }

    @Test fun `no filter, no like`() {
        val (sql, args) = AgentMailContract.sql(AgentMailContract.Criteria(null, null, 0L, 50))
        assertFalse(sql.contains("LIKE"))
        assertArrayEquals(arrayOf<Any>(0L, 50), args)
    }

    @Test fun `a body is cut at the cap and says so`() {
        val small = AgentMailContract.body("hello", "<p>hello</p>")
        assertEquals("hello", small.text); assertFalse(small.truncated)
        val big = AgentMailContract.body("a".repeat(AgentMailContract.MAX_BODY_CHARS + 1), "")
        assertEquals(AgentMailContract.MAX_BODY_CHARS, big.text.length); assertTrue(big.truncated)
        val bigHtml = AgentMailContract.body("", "b".repeat(AgentMailContract.MAX_BODY_CHARS + 5))
        assertTrue(bigHtml.truncated)
        val exact = AgentMailContract.body("a".repeat(AgentMailContract.MAX_BODY_CHARS), "")
        assertFalse(exact.truncated)
    }

    @Test fun `the columns are the contract`() {
        assertArrayEquals(arrayOf("id", "account_id", "mailbox_id", "subject", "from_name", "from_email", "received_at", "sort_key", "seen"), AgentMailContract.MESSAGE_COLUMNS)
        assertArrayEquals(arrayOf("text", "html", "truncated"), AgentMailContract.BODY_COLUMNS)
        assertEquals("com.diegonmarcos.cloud.permission.CONSTELLATION_DATA", AgentMailContract.PERMISSION)
    }

    @Test fun `the provider can only query and the manifest guards it with the signature permission`() {
        val src = File("src/main/kotlin/app/sterna/agentapi/AgentMailProvider.kt").readText()
        assertTrue(src.contains("override fun insert(uri: Uri, values: ContentValues?): Uri? = null"))
        assertTrue(src.contains("override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0"))
        assertTrue(src.contains("override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0"))
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val provider = manifest.substringAfter("app.sterna.agentapi.AgentMailProvider").substringBefore("/>")
        assertTrue(provider.contains("\${applicationId}.agentmail"))
        assertTrue(provider.contains("android:permission=\"${AgentMailContract.PERMISSION}\""))
    }
}
