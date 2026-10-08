package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The toggle's rules: what Show Original / Show Translated may do, and when the page uses the translation. */
class ReaderTranslationStateTest {
    private val src = "<p>Hello</p>"
    private val translated = "<p>HOLA</p>"

    @Test fun `nothing exists until a translation does, and then the toggle is enabled`() {
        val none = ReaderTranslation(lang = "es", source = src)
        assertFalse(none.exists)
        assertNull(none.fragmentFor(src))
        val have = none.copy(fragment = translated, shown = true)
        assertTrue(have.exists)
    }

    @Test fun `the page uses the translation only while it is shown and still made from this text`() {
        val shown = ReaderTranslation("es", src, translated, shown = true)
        assertEquals(translated, shown.fragmentFor(src))
        assertNull("toggled to the original", shown.copy(shown = false).fragmentFor(src))
        assertNull("the reading mode changed the text underneath", shown.fragmentFor("<pre class=\"plain\">Hello</pre>"))
        assertNull("no text yet", shown.fragmentFor(null))
    }

    @Test fun `a running or failed translation does not exist`() {
        assertFalse(ReaderTranslation("es", src, running = true).exists)
        assertFalse(ReaderTranslation("es", src, error = "no network").exists)
    }

    @Test fun `the summary box is drawn once there is anything to say`() {
        assertFalse(ReaderSummary().visible)
        assertTrue(ReaderSummary(text = "short").visible)
        assertTrue(ReaderSummary(running = true).visible)
        assertTrue(ReaderSummary(error = "no key").visible)
    }
}
