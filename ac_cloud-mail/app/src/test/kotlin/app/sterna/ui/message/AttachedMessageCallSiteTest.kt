package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * [AttachedMessageTest] executes the decision; this pins that the decision is still WIRED: the
 */
class AttachedMessageCallSiteTest {

    /**
     * The one code line of the view model that names [isAttachedMessage], whole: the type it
     */
    @Test fun `the tap hands a message-rfc822 part to the sheet and stops there`() {
        assertEquals(
            "openAttachment must route on isAttachedMessage(part.type) into openAttachedMessage and " +
                "return, once and whole. Lines found:",
            listOf("if (isAttachedMessage(part.type)) { openAttachedMessage(part, ownerId); return }"),
            codeLines(viewModel().readText()).filter { "isAttachedMessage" in it },
        )
    }

    /**
     * And it routes AFTER the double-tap guard is taken, not before it: the line above is pinned
     */
    @Test fun `the route is taken with the double-tap guard already held`() {
        val lines = codeLines(viewModel().readText())
        val guard = lines.indexOf("openingAttachment = true")
        val route = lines.indexOf("if (isAttachedMessage(part.type)) { openAttachedMessage(part, ownerId); return }")
        assertEquals("openingAttachment = true must be set exactly once", guard, lines.lastIndexOf("openingAttachment = true"))
        assertEquals("the route must come after the guard is taken (guard at $guard, route at $route)", true, guard in 0 until route)
    }

    /**
     * Closing the sheet stops the download behind it. Without the cancel, a `Loaded` landing after
     */
    @Test fun `closing the sheet cancels the download behind it`() {
        assertEquals(
            "the attached-message job must be declared, cancelled and dropped on close, and " +
                "assigned by the launch. Lines found:",
            listOf(
                "private var attachedMessageJob: Job? = null",
                // dismissAttachedMessage
                "attachedMessageJob?.cancel()",
                "attachedMessageJob = null",
                // load()'s prologue, written out for ReaderStateResetOnLoadTest
                "attachedMessageJob?.cancel()",
                "attachedMessageJob = null",
                "attachedMessageJob = viewModelScope.launch {",
            ),
            codeLines(viewModel().readText()).filter { "attachedMessageJob" in it },
        )
    }

    /** The sheet is collected from the reader, and declared once, private, in the same file. */
    @Test fun `the reader collects the attached-message sheet`() {
        assertEquals(
            "MessageScreen must collect viewModel.attachedMessage into AttachedMessageSheet, and " +
                "the sheet must still exist. Lines found:",
            listOf(
                "attachedState?.let { AttachedMessageSheet(state = it, onDismiss = { viewModel.dismissAttachedMessage() }) }",
                "private fun AttachedMessageSheet(",
            ),
            codeLines(screen().readText()).filter { "AttachedMessageSheet" in it },
        )
    }

    /**
     * Nothing is written to disk on this path, and no app is asked to open anything: the
     */
    @Test fun `showing an attached message writes nothing and starts nothing`() {
        val body = openAttachedMessageBody()
        assertEquals(
            "openAttachedMessage must not cache, expose or hand off the bytes. Lines found:",
            emptyList<String>(),
            body.filter { "cacheAttachment" in it || "FileProvider" in it || "Intent(" in it },
        )
    }

    /**
     * The code lines of `openAttachedMessage`, from its declaration to the next member function
     */
    private fun openAttachedMessageBody(): List<String> {
        val lines = viewModel().readText().lines()
        val start = lines.indexOfFirst { it.trim().startsWith("private fun openAttachedMessage(") }
        assertEquals("openAttachedMessage must still be declared in MessageViewModel", true, start >= 0)
        val rest = lines.drop(start + 1)
        val end = rest.indexOfFirst { it.startsWith("    fun ") || it.startsWith("    private fun ") }
            .let { if (it < 0) rest.size else it }
        val body = codeLines(rest.take(end).joinToString("\n"))
        assertEquals("openAttachedMessage must have a body", true, body.isNotEmpty())
        return body
    }

    /** [text]'s lines, trimmed, comment-only lines dropped. */
    private fun codeLines(text: String): List<String> = text.lines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private fun viewModel(): File =
        locate("app/src/main/kotlin/app/sterna/ui/message/MessageViewModel.kt")

    private fun screen(): File =
        locate("app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt")

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
