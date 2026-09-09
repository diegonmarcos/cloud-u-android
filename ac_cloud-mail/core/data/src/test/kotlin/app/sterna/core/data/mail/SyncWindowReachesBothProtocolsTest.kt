package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — `MailRepository` needs Room, an Android `Context` and a
 */
class SyncWindowReachesBothProtocolsTest {

    private fun lines(function: String): List<String> =
        DaoQuerySource.mailFunctionBody("MailRepository", function).lines().map { it.trim() }

    private fun assertLine(function: String, line: String) {
        val body = lines(function)
        assertTrue(
            "MailRepository.$function no longer contains the line:\n  $line\nits body is:\n" +
                body.joinToString("\n"),
            line in body,
        )
    }

    // -- one number, both protocols ----------------------------------------------------------------

    @Test fun `the protocol switch is downstream of the window, and hands it on untouched`() {
        // `limit` is the account's window (InboxViewModel.refreshFolder). It reaches this line
        // whole and leaves it whole, into the IMAP branch; the JMAP branch below sizes itself on
        // the same variable. Nothing between the setting and either walk may re-decide it.
        assertLine(
            "refresh",
            "if (credentials.protocol == MailProtocol.IMAP) return refreshImap(credentials, mailboxId, limit, pruneBeforeMillis)",
        )
        assertLine("refresh", "val sizing = folderSyncSizing(limit, session.getBatchSize())")
    }

    @Test fun `neither branch caps the window on its way to the walk`() {
        // The mutation this is written against: `folderSyncSizing(minOf(limit, 1000), …)`, or the
        // same on the IMAP side — the old ceiling re-introduced one layer below the constant,
        // where the label would go on saying "Everything".
        listOf("refresh", "refreshImap", "imapWriteThrough").forEach { function ->
            val capped = lines(function).filter {
                !it.startsWith("//") && ("coerceAtMost" in it || "minOf(" in it || "min(" in it)
            }
            assertEquals(
                "MailRepository.$function now caps something on the path the sync window travels; " +
                    "if that is deliberate, the settings label is no longer true:\n" +
                    capped.joinToString("\n"),
                emptyList<String>(), capped,
            )
        }
    }

    // -- the retention floor: the window, and only when there is an age ------------------------------

    @Test fun `the JMAP prune reads the floor off the window and never off the request size`() {
        // Codeberg #110. `sizing.pageSize` here would cap what retention keeps at what the
        // server hands over in one request — 500 rows of a folder the user asked to keep whole.
        assertLine(
            "refresh",
            "pruneRetention(credentials.id, target.id, pruneBeforeMillis, " +
                "sync.getOrNull()?.fetchedIds?.toSet(), sizing.retentionFloor)",
        )
        assertEquals(
            "refresh() now mentions sizing.pageSize outside the walk it belongs to",
            emptyList<String>(),
            lines("refresh").filter { !it.startsWith("//") && "sizing.pageSize" in it },
        )
    }

    @Test fun `the prune only runs when the window carries an age, which a count window does not`() {
        // Every count window (100 / 1 000 / 10 000, and "Everything") has `maxAgeDays == null`, so
        // `InboxViewModel.refreshFolder` computes a null cutoff and both guards below skip the
        assertLine("refresh", "if (syncError == null && pruneBeforeMillis != null) {")
        assertLine("refreshImap", "if (pruneBeforeMillis != null) {")
    }

    @Test fun `the IMAP prune is given the window, as its own floor`() {
        // The IMAP branch has no `sizing`: `limit` IS the floor there, which is why the same
        // number must not have been shrunk on the way in (the test above).
        val body = lines("refreshImap")
        val call = body.indexOf("pruneRetention(")
        assertTrue("refreshImap no longer prunes at all:\n" + body.joinToString("\n"), call >= 0)
        assertEquals(
            "refreshImap passes something other than the account's window as the retention floor",
            listOf("credentials.id,", "load.targetMailboxId,", "pruneBeforeMillis,", "reconcilableIds(load, credentials.id),", "limit,", ")"),
            body.subList(call + 1, call + 7),
        )
    }
}
