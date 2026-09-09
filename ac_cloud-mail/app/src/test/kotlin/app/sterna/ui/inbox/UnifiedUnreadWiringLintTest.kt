package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and same disclaimer as
 */
class UnifiedUnreadWiringLintTest {

    @Test
    fun `the drawer's unread total is the shared no-argument derivation, nothing else`() {
        assertEquals(
            "InboxViewModel.unifiedUnread must be exactly the no-argument overload — the same call " +
                "the home-screen widget makes. Passing scopes in again is a second derivation of " +
                "which inboxes count, and the two numbers on screen can then disagree (#112). " +
                "Compared as a whole line precisely because every plausible mutation here is " +
                "LONGER than the line it replaces.",
            listOf("private val unifiedUnread = repo.observeUnifiedInboxUnread()"),
            declaration(INBOX_VIEW_MODEL, "unifiedUnread"),
        )
    }

    /**
     * The lines of `val` [name]'s declaration in [file], comments dropped whole and blank lines
     */
    private fun declaration(file: File, name: String): List<String> {
        val lines = file.readLines().map { it.trim() }
        val start = lines.indexOfFirst { Regex("""\bval\s+$name\b""").containsMatchIn(it) }
        check(start >= 0) { "${file.name} declares no 'val $name' — did it get renamed?" }
        val out = mutableListOf<String>()
        var depth = 0
        var i = start
        while (i < lines.size) {
            val line = lines[i]
            out += line
            depth += line.count { it == '(' || it == '{' } - line.count { it == ')' || it == '}' }
            i++
            if (depth > 0) continue
            val next = lines.getOrNull(i) ?: break
            if (!CONTINUATION.containsMatchIn(next)) break
        }
        return out.map(::withoutTrailingComment).filter { it.isNotBlank() }
    }

    /** [line] up to its `//` comment, ignoring a `//` inside a string literal. */
    private fun withoutTrailingComment(line: String): String {
        var inString = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inString && c == '\\' -> i++
                c == '"' -> inString = !inString
                !inString && c == '/' && line.getOrNull(i + 1) == '/' ->
                    return line.substring(0, i).trimEnd()
            }
            i++
        }
        return line.trimEnd()
    }

    private companion object {
        /** A line that carries on the previous one because it opens with an operator. */
        val CONTINUATION = Regex("""^\s*(\.|\?:|\?\.|\+|&&|\|\||,)""")

        const val PATH = "app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt"

        /** Repo root, walked up from the module's working directory. */
        val ROOT: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the source as text and needs a working directory inside the checkout",
                )
        }

        val INBOX_VIEW_MODEL: File by lazy { File(ROOT, PATH) }
    }
}
