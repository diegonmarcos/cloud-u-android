package app.sterna.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app's side of the editor page, executed: what it parses, quotes and fills in. */
class HtmlEditorStateTest {

    @Test fun `the page's state string lights the toolbar`() {
        val f = parseEditorState("b=1&i=0&u=1&s=0&ul=0&ol=1&al=c&blk=h2&color=rgb%281%2C%202%2C%203%29&link=https%3A%2F%2Fx.org&range=1&quote=0")
        assertTrue(f.bold && f.underline && f.numbers && f.hasRange)
        assertFalse(f.italic || f.strike || f.bullets || f.inQuote)
        assertEquals('c', f.align)
        assertEquals("h2", f.block)
        assertEquals("rgb(1, 2, 3)", f.color)
        assertEquals("https://x.org", f.link)
    }

    @Test fun `no link is no link`() {
        assertNull(parseEditorState("link=-&b=0").link)
        assertNull(parseEditorState("").link)
    }

    @Test fun `a javascript string result is unquoted`() {
        assertEquals("<div>a \"q\"</div>\nx", unquoteJsResult("\"<div>a \\\"q\\\"</div>\\nx\""))
        assertEquals("a\\b", unquoteJsResult("\"a\\\\b\""))
        assertEquals("", unquoteJsResult("null"))
        assertEquals("", unquoteJsResult(null))
        assertEquals("<", unquoteJsResult("\"\\u003C\""))
    }

    @Test fun `a string handed to the page is a safe literal`() {
        assertEquals("\"a\\\"b\\\\c\\n\\u003c/script>\"", jsString("a\"b\\c\n</script>"))
        assertEquals("\"\\u2028\"", jsString("\u2028"))
    }

    @Test fun `the page is filled in with the theme and an escaped placeholder`() {
        val doc = editorDocument(
            "color:%%FG%%;bg:%%HINT%%;a:%%ACCENT%%;font:%%FONT_PX%%px;p=\"%%PLACEHOLDER%%\"",
            "#111111", "#222222", "#333333", 16, "Write \"here\" <now>",
        )
        assertEquals("color:#111111;bg:#222222;a:#333333;font:16px;p=\"Write &quot;here&quot; &lt;now>\"", doc)
        assertFalse("%%" in doc)
    }

    @Test fun `the real page has every slot the app fills`() {
        val page = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .map { java.io.File(it, "app/src/main/assets/html-editor.html") }
            .first { it.isFile }.readText()
        for (slot in listOf("%%FG%%", "%%HINT%%", "%%ACCENT%%", "%%FONT_PX%%", "%%PLACEHOLDER%%")) {
            assertTrue("the page lacks $slot", slot in page)
        }
        assertTrue("contenteditable=\"true\"" in page)
        assertTrue("the page must forbid the network", "default-src 'none'" in page)
        val filled = editorDocument(page, "#000000", "#888888", "#0066ff", 16, "Write")
        assertFalse("an unfilled slot is left", "%%" in filled)
    }

    @Test fun `the text tools' scope`() {
        assertEquals(EditorScope("sel", "word"), parseEditorScope("sel:word"))
        assertEquals(EditorScope("all", "a\nb"), parseEditorScope("all:a\nb"))
        assertNull(parseEditorScope("nothing"))
    }

    @Test fun `colours are css`() {
        assertEquals("#0066FF", cssColor(0xFF0066FF.toInt()))
    }
}
