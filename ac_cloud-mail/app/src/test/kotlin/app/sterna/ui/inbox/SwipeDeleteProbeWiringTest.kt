package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and the same disclaimer as
 */
class SwipeDeleteProbeWiringTest {

    @Test fun `the swipe's delete probe falls back to NOT destroying`() {
        val body = body(INBOX_VIEW_MODEL, "delete")
        assertEquals(
            "⛔ the whole line, arguments and fallback included. `.getOrDefault(true)` here is " +
                "permanent destruction of ordinary mail every time the folder lookup cannot be " +
                "made — offline, or on a LIST that times out. Body was:\n$body",
            listOf("if (runCatching { repo.deleteWouldDestroy(credentials, email) }.getOrDefault(false)) {"),
            codeLinesNaming(body, "deleteWouldDestroy("),
        )
        assertEquals(
            "and that probe must be the ONLY thing in this gesture with a fallback: a second " +
                "getOrDefault(...) is a second decision no test here can see",
            listOf("if (runCatching { repo.deleteWouldDestroy(credentials, email) }.getOrDefault(false)) {"),
            codeLinesNaming(body, "getOrDefault("),
        )
    }

    @Test fun `and each branch of it goes where it belongs`() {
        val body = body(INBOX_VIEW_MODEL, "delete")
        assertEquals(
            "the destroy arm is the HELD-BACK destroy (a real Undo window), the move arm is the " +
                "ordinary swipe with its own Undo. Whole lines: swapping the two arms, or " +
                "inlining a destroy here, must not read as the same set of lines. Body was:\n$body",
            listOf(
                "heldBackDestroy(listOf(email), getApplication<Application>()" +
                    ".getString(R.string.status_message_deleted_forever))",
                "swipeRemove(email, getApplication<Application>()" +
                    ".getString(R.string.status_message_deleted)) { c, id -> repo.delete(c, id) }",
            ),
            codeLinesNaming(body, "getApplication<Application>()"),
        )
    }

    // -- reading the sources ------------------------------------------------------------------
    // Same readers as BulkSelectionWiringTest, deliberately duplicated rather than shared: a
    // helper these lints agree on is a helper a single edit can loosen for all of them at once.

    /** The code lines of [body] naming [needle], comments dropped, whitespace normalised. */
    private fun codeLinesNaming(body: String, needle: String): List<String> =
        body.lines().map { it.replace(Regex("""\s+"""), " ").trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { needle in it }

    /** The lines of [file] that are code, with comments taken off. */
    private fun codeLines(file: File): List<String> = file.readLines().mapNotNull { line ->
        val code = line.trimStart()
        if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) null
        else withoutTrailingComment(line).takeIf { it.isNotBlank() }
    }

    /** [line] up to its first `//` outside a double-quoted string; `\` escapes the next character. */
    private fun withoutTrailingComment(line: String): String {
        var inString = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inString && c == '\\' -> i++
                c == '"' -> inString = !inString
                !inString && c == '/' && line.getOrNull(i + 1) == '/' -> return line.substring(0, i).trimEnd()
            }
            i++
        }
        return line.trimEnd()
    }

    /**
     * The declaration of `fun`/`val` [name] in [file] and its body, as text. Fails loudly when the
     */
    private fun body(file: File, name: String): String {
        val lines = codeLines(file)
        val declaration = Regex("""\b(fun|val|var)\s+$name\b""")
        val start = lines.indexOfFirst { declaration.containsMatchIn(it) }
        check(start >= 0) { "${file.name} declares no '$name' — did it get renamed?" }
        val indent = lines[start].indentWidth()
        val out = mutableListOf<String>()
        var closed = false
        var depth = 0
        for (i in start until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            if (closed && i > start && line.indentWidth() <= indent) break
            out += line
            depth += line.count { it == '(' || it == '{' } - line.count { it == ')' || it == '}' }
            closed = depth == 0
        }
        return out.joinToString("\n")
    }

    private fun String.indentWidth() = length - trimStart().length

    companion object {
        private const val APP_SOURCES = "app/src/main/kotlin"
        private const val INBOX_VIEW_MODEL_PATH = "$APP_SOURCES/app/sterna/ui/inbox/InboxViewModel.kt"

        /** Repo root, walked up from the module's working directory. */
        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, INBOX_VIEW_MODEL_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        private val INBOX_VIEW_MODEL: File by lazy { File(root, INBOX_VIEW_MODEL_PATH) }
    }
}
