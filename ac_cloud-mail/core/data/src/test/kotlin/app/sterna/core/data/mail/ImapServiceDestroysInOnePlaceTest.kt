package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every destroy written with the word `delete` in `ImapMailService` is the ONE guarded expunge of
 */
class ImapServiceDestroysInOnePlaceTest {

    /** The code lines of [source] matching [needle] — comments dropped. Whole lines: the assertion
     *  compares them, never searches inside them. */
    private fun codeLinesNaming(source: String, needle: Regex): List<String> =
        source.lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { needle.containsMatchIn(it) }

    private fun codeLinesNaming(source: String, needle: String): List<String> =
        codeLinesNaming(source, Regex(Regex.escape(needle)))

    /**
     * The needle is the WORD, not the call. `delete(` — a name glued to a parenthesis — is a
     */
    @Test fun `every delete the service writes is the one guarded expunge`() {
        val source = DaoQuerySource.mailSource("ImapMailService")
        val guarded = listOf("if (mayDestroy) session.delete(uids)")
        val why =
            "the ONLY `delete` ImapMailService may write is `deleteBatch`'s, guarded by the " +
                "numbering the server just stated. Any other one is an expunge that opposes " +
                "NOTHING: `onMailbox` falls back on `expectedUidValidity ?: recorded`, matches the " +
                "folder's current numbering against itself, and erases UIDs that may now name " +
                "other, live messages (#99). A second door need not be called to be a defect — it " +
                "is public, it compiles, and it hands the first caller the destroy this branch " +
                "refuses everywhere else. ⛔ This says nothing about destroys NOT spelled " +
                "`delete`: `setFlag(…, \"\\\\Deleted\", …)` and `deleteFolder` are unopposed too, " +
                "and are filed, not covered here."
        // The word, which catches `session::delete` and `session.delete (uid)` as well.
        assertEquals(why, guarded, codeLinesNaming(source, Regex("""\bdelete\b""")))
        // And the call as it is written today, so the surviving line cannot drift into some other
        // shape while the rule above stays satisfied by it.
        assertEquals(why, guarded, codeLinesNaming(source, "session.delete("))
    }
}
