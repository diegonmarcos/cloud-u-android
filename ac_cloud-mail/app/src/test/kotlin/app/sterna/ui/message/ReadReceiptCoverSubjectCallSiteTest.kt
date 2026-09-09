package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * [ReadReceiptOfferTest] executes the capture; this pins WHAT IT IS CALLED WITH, and WHERE the
 */
class ReadReceiptCoverSubjectCallSiteTest {

    /** PIN 1 — the call site: the offer is built with the cover subject, and with nothing else. */
    @Test fun `the offer hands the read receipt the cover subject`() {
        assertEquals(
            "maybeOfferReadReceipt() must build the request with the captured cover subject, on " +
                "exactly one line and with exactly these arguments. `current.subject` there is " +
                "the subject on SCREEN, which is the protected one after a decrypt. Found:",
            listOf("request = readReceiptRequest(current, _deliveredTo.value, coverSubject),"),
            codeLines(offerFunction()).filter { "readReceiptRequest(" in it },
        )
    }

    /**
     * PIN 2 — every code line of the file that names `coverSubject`, whole and in order.
     */
    @Test fun `the cover subject is captured before any decrypt, and never rewritten`() {
        assertEquals(
            "`coverSubject` is what stops a decrypted subject from leaving the device. It may be " +
                "declared once, cleared for the next message in load()'s prologue, written from " +
                "the two pre-decrypt reads (never erased by a null), and read once by the offer " +
                "— and it may appear nowhere else. Lines found:",
            listOf(
                "private var coverSubject: String? = null",
                "coverSubject = null",
                "coverSubject = cached.subject ?: coverSubject",
                "coverSubject = coverSubjectAfterOpen(coverSubject, anchor, opened.crypto)",
                "request = readReceiptRequest(current, _deliveredTo.value, coverSubject),",
            ),
            codeLines(viewModel().readText()).filter { "coverSubject" in it },
        )
    }

    /**
     * The body of `maybeOfferReadReceipt` up to the function that follows it. Both markers are
     */
    private fun offerFunction(): String {
        val source = viewModel().readText()
        val start = source.indexOf("private fun maybeOfferReadReceipt() {")
        check(start >= 0) { "MessageViewModel no longer declares maybeOfferReadReceipt()" }
        val end = source.indexOf("fun openAttachment(", start)
        check(end > start) { "maybeOfferReadReceipt() is no longer followed by openAttachment()" }
        return source.substring(start, end)
    }

    /** [text]'s lines, trimmed, comment-only lines dropped. */
    private fun codeLines(text: String): List<String> = text.lines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private fun viewModel(): File =
        locate("app/src/main/kotlin/app/sterna/ui/message/MessageViewModel.kt")

    /** [relative] resolved from the test's working directory, walking up — as `DecryptedSubjectCallSiteTest` does. */
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
