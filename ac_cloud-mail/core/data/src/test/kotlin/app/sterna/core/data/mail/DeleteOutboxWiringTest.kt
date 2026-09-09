package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * THIS TEST READS SOURCE TEXT — the last resort, as [LocalDraftSaveWiringTest] does and for the
 */
class DeleteOutboxWiringTest {

    private fun bodyOf(function: String): String = DaoQuerySource.mailFunctionBody("MailRepository", function)

    private fun codeOf(body: String): List<String> =
        body.lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") || it.isEmpty() }

    /** Every CODE line of the whole file naming [needle], in file order — comments dropped by
     *  [codeOf], so the KDoc that names [outboxFilesDir] to say a draft is NOT stored there does
     *  not answer for the code. */
    private fun fileLinesNaming(needle: String): List<String> =
        codeOf(DaoQuerySource.mailSource("MailRepository")).filter { needle in it }

    @Test fun `consuming an outbox item takes its row AND its staged directory`() {
        // The order is part of the guard: the Room row goes FIRST, so a crash between the two
        // leaves an orphan directory (recoverable, invisible) rather than a row pointing at bytes
        // that are already gone.
        //
        // And the `runCatching` is deliberate: a full disk or a file still held open must not
        // fail the destruction of the row. This pins that the wrapper is THERE, in that shape — it
        // is not an invitation to let the failure through.
        //
        // WHOLE LINES, both of them: a `contains`-style check is blind to any mutation that
        // lengthens a line, and `deleteRecursively()` → `delete()` is a mutation that barely
        // changes it — `delete()` refuses a non-empty directory, the `runCatching` swallows the
        // `false` it returns, and the staged attachments stay on disk with every test green.
        assertEquals(
            "⛔ deleting an Outbox item must drop the Room row AND wipe its persistent staging " +
                "directory. Dropping, weakening or reordering the second line leaves that " +
                "directory on disk FOREVER — on all six callers at once — and nothing tells the " +
                "user: the app's storage just grows, with no screen that accounts for it:",
            listOf(
                "{",
                "outboxDao.delete(id)",
                "runCatching { java.io.File(outboxFilesDir, id.toString()).deleteRecursively() }",
                "}",
            ),
            codeOf(bodyOf("deleteOutbox")),
        )
    }

    @Test fun `every line naming the outbox files dir spells the same directory`() {
        // A CENSUS, not a search and not a count: three occurrences counted would still pass with
        // one of them pointing somewhere else, and a `contains` is blind to anything that lengthens
        //
        // Rule 1 above cannot see this: it reads deleteOutbox's body only, so the two creating
        // sites could drift under it with every test green.
        assertEquals(
            "⛔ the Outbox has no outboxDir(id): its durable directory is spelled out by hand at " +
                "every site, and this list is the only thing keeping those spellings equal. Let " +
                "one of them drift — an extra sub-directory, an id formatted another way — and " +
                "the path that is created is no longer the path that is deleted: staged " +
                "attachments stay on disk FOREVER, on every send, and nothing on screen or in a " +
                "log ever says so. ⚠ If this reddens legitimately, a FOURTH site is what has to " +
                "be argued here first: adding one is adding a hand-written copy of a path nobody " +
                "computes. Updating the list without that argument is how the guard is lost:",
            listOf(
                "private val outboxFilesDir: java.io.File,",
                "val dir = java.io.File(outboxFilesDir, id.toString()).apply { mkdirs() }",
                "val dir = java.io.File(outboxFilesDir, id.toString()).apply { mkdirs() }",
                "runCatching { java.io.File(outboxFilesDir, id.toString()).deleteRecursively() }",
            ),
            fileLinesNaming("outboxFilesDir"),
        )
    }
}
