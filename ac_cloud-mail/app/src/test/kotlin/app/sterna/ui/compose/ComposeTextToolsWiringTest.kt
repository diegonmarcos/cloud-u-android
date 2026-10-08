package app.sterna.ui.compose

import app.sterna.ui.settings.TEXT_TOOLS_ENTRIES
import app.sterna.ui.text.TextTool
import app.sterna.ui.text.TextToolSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT plus declarations: the composer's in-place tools are declared on the surface, drawn
 * under Subject and in the overflow, applied as an undoable edit, and nothing on that path sends.
 */
class ComposeTextToolsWiringTest {
    private val screen = File("src/main/kotlin/app/sterna/ui/compose/ComposeScreen.kt").readText()

    @Test fun `the composer offers the three in-place tools and Translate`() {
        assertEquals(
            setOf(TextTool.ANSWER, TextTool.ENHANCE, TextTool.GRAMMAR, TextTool.TRANSLATE),
            TextToolSurface.COMPOSE.tools.toSet(),
        )
        assertEquals(
            setOf(TextTool.ANSWER, TextTool.ENHANCE, TextTool.GRAMMAR),
            TextTool.values().filter { it.appliesInPlace }.toSet(),
        )
        assertTrue("Answer Prediction reads the thread", TextTool.ANSWER.readsThread)
        assertFalse(TextTool.ENHANCE.readsThread)
    }

    @Test fun `the toolbar sits directly below Subject`() {
        val subject = screen.indexOf("focusRequester = subjectFocus,")
        val bar = screen.indexOf("TextToolInPlaceBar(")
        val next = screen.indexOf("compose_pgp_subject_note")
        assertTrue(subject in 0 until bar && bar < next)
    }

    @Test fun `the same tools are named items in the overflow`() {
        assertTrue(screen.contains("TextToolNamedItems(textTools.surface"))
        assertTrue(screen.contains("skip = { it.appliesInPlace }"))
    }

    @Test fun `a result is one undoable edit and the path never sends`() {
        assertTrue(screen.contains("textToolUndo = TextToolUndo(body, RichBody(body.text, ranges, blocks, links))"))
        val start = screen.indexOf("fun applyTextTool(")
        val end = screen.indexOf("// Which compose this is")
        val path = screen.substring(start, end)
        assertFalse("applying a result must never send", Regex("""\b(send|sendNow|scheduleSend|saveDraft)\b""").containsMatchIn(path))
    }

    @Test fun `Answer Prediction has no thread on a fresh message and says so`() {
        assertTrue(screen.contains("if (thread.isBlank())"))
        assertTrue(screen.contains("R.string.text_tool_no_thread"))
    }

    @Test fun `Answer Prediction is configured right after Text Resume`() {
        val routes = TEXT_TOOLS_ENTRIES.map { it.route }
        assertEquals(routes.indexOf("textResume") + 1, routes.indexOf("textAnswer"))
    }
}
