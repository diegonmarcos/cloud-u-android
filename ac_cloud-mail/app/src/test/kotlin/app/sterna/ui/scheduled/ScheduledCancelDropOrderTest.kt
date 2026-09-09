package app.sterna.ui.scheduled

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The shipped text of the destruction block, whole and in order (#170).
 */
class ScheduledCancelDropOrderTest {

    @Test fun `the row is destroyed before the job, inside the guard`() {
        val code = codeLines(cancel().readText())
        val at = code.indexOfFirst { it.startsWith("if (scheduledCancelDropsTheRow") }
        check(at >= 0) { "no 'if (scheduledCancelDropsTheRow…' left in ScheduledSendCancel.kt:\n" + code.joinToString("\n") }

        assertEquals(
            "the row is the only copy and its delete is the committed write; the job's cancellation " +
                "is asynchronous and unproven at its return. A row left behind is re-armed at the " +
                "next process start and the cancelled message goes out",
            listOf(
                "if (scheduledCancelDropsTheRow(report)) {",
                "deleteRow(id)",
                "dropWorker(id)",
                "}",
            ),
            code.subList(at, minOf(at + 4, code.size)),
        )
    }

    @Test fun `each write is declared once and called once`() {
        assertEquals(
            "a second call site, or a wrapper around either line, changes what is destroyed without " +
                "changing the order the journalled test sees",
            listOf(
                "dropWorker: (Long) -> Unit,",
                "deleteRow: suspend (Long) -> Unit,",
                "deleteRow(id)",
                "dropWorker(id)",
            ),
            codeLines(cancel().readText())
                .filter { "dropWorker" in it || "deleteRow" in it },
        )
    }

    /** [text]'s lines, trimmed, comment-only lines dropped. */
    private fun codeLines(text: String): List<String> = text.lines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private fun cancel(): File =
        locate("app/src/main/kotlin/app/sterna/ui/scheduled/ScheduledSendCancel.kt")

    /** [relative] resolved from the test's working directory, walking up. */
    private fun locate(relative: String): File {
        val fromModule = relative.substringAfter("app/")
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            File(dir, relative).takeIf { it.isFile }?.let { return it }
            File(dir, fromModule).takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        error("Cannot find $relative from ${System.getProperty("user.dir")}")
    }
}
