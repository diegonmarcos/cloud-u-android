package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — it reads `MessageScreen.kt` as text and proves nothing about
 */
class BodySpacerWiringTest {

    @Test fun `the reader measures the body's own viewport`() {
        val lines = codeLines()

        assertEquals(
            "ConversationBody must hold the body viewport's height, keyed on the message like the " +
                "header's — it is the denominator of both spacers. Expected this line, verbatim:\n" +
                "  var bodyViewportPx by remember(msg.id) { mutableIntStateOf(0) }",
            1, lines.count { it == "var bodyViewportPx by remember(msg.id) { mutableIntStateOf(0) }" },
        )

        val at = lines.indexOf(".onSizeChanged { bodyViewportPx = it.height }")
        assertTrue(
            "the viewport must be measured on the body's own Box — the WebView is a " +
                "fillMaxSize() child of it, so it is the WebView's height, and it is measured in " +
                "the SAME layout pass as the header. Expected this line, verbatim:\n" +
                "  .onSizeChanged { bodyViewportPx = it.height }\nLines found around " +
                "fillMaxSize(): ${lines.filter { "onSizeChanged" in it }}",
            at > 0,
        )
        assertEquals(
            "…and it must sit on THAT Box (between .fillMaxSize() and the surface background), " +
                "not on the header, not on the invisible measuring copy of the bar: measured " +
                "anywhere else the denominator is the wrong height and every blank is wrong with it.",
            listOf(".fillMaxSize()", ".background(MaterialTheme.colorScheme.surface),"),
            listOf(lines[at - 1], lines[at + 1]),
        )
    }

    @Test fun `the document waits for both measurements before it loads once`() {
        assertEquals(
            "the body must load only once both the header's height and the body viewport are " +
                "measured. With the guard back on the header alone, the first load reserves a " +
                "spacer computed against a viewport of 0 — and either the reader gets no blank at " +
                "all under an opaque header, or the document is rebuilt and reloaded under her " +
                "eyes, back at the top of the message. Expected this line, verbatim:\n" +
                "  if (headerHeightPx > 0 && bodyViewportPx > 0) {",
            1, codeLines().count { it == "if (headerHeightPx > 0 && bodyViewportPx > 0) {" },
        )
    }

    @Test fun `each spacer is asked for its own reservation, against the viewport`() {
        val lines = codeLines()

        assertEquals(
            "the TOP blank must be bodySpacerCss(headerHeightPx, bodyViewportPx) — the header's " +
                "measured height first, the viewport second. Swapped, the two arguments still " +
                "compile (both Int) and the collapsing header, which is opaque, covers the top of " +
                "every message. Expected this line, verbatim:\n" +
                "  val topSpacerCss = bodySpacerCss(headerHeightPx, bodyViewportPx)",
            1, lines.count { it == "val topSpacerCss = bodySpacerCss(headerHeightPx, bodyViewportPx)" },
        )
        assertEquals(
            "the BOTTOM blank must be bodySpacerCss(bottomInsetPx, bodyViewportPx) — the inset " +
                "first, the viewport second. Swapped, it also compiles: the end of the document " +
                "gets a blank of the whole viewport measured against the bar, so either the " +
                "Reply/Forward bar covers the last line of every message or every message ends " +
                "with a vast white nothing. Expected this line, verbatim:\n" +
                "  val bottomSpacerCss = bodySpacerCss(bottomInsetPx, bodyViewportPx)",
            1, lines.count { it == "val bottomSpacerCss = bodySpacerCss(bottomInsetPx, bodyViewportPx)" },
        )
    }

    @Test fun `the document is keyed on the two blanks and nothing else changes under a pinch`() {
        assertEquals(
            "the two lengths must appear TWICE, verbatim and in this order — once in the " +
                "remember() key, once in the buildHtmlDocument(...) call:\n" +
                "  full, msg.inlineImages, emailTheme, topSpacerCss, bottomSpacerCss,\n" +
                "This line is pinned WHOLE on purpose. Anything added to it that a pinch can " +
                "change (a page scale, a zoom level) re-keys the document under the reader's " +
                "finger: the body reloads mid-gesture, flashes white, jumps back to the top and " +
                "resets the zoom — in a loop. And a length present in the call but not in the key " +
                "leaves the reader on a document built for a viewport that is gone.",
            2,
            codeLines().count {
                it == "full, msg.inlineImages, emailTheme, topSpacerCss, bottomSpacerCss,"
            },
        )
    }

    // -- reading the source ------------------------------------------------------------------

    /** `MessageScreen.kt`'s code as trimmed lines, comments cut: the comments beside these call
     *  sites quote the very expressions the rules pin. */
    private fun codeLines(): List<String> = MESSAGE_SCREEN.readLines().mapNotNull { line ->
        val trimmed = line.trim()
        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) null
        else withoutTrailingComment(line).trim().takeIf { it.isNotBlank() }
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
                !inString && c == '/' && line.getOrNull(i + 1) == '/' -> return line.substring(0, i)
            }
            i++
        }
        return line
    }

    companion object {
        private const val MESSAGE_SCREEN_PATH =
            "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt"

        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, MESSAGE_SCREEN_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the source as text and needs a working directory inside the checkout",
                )
        }

        private val MESSAGE_SCREEN: File by lazy { File(root, MESSAGE_SCREEN_PATH) }
    }
}
