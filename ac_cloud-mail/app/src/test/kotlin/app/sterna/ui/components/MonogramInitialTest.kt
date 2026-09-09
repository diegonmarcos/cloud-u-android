package app.sterna.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The letter drawn inside a badge, pinned as literals.
 */
class MonogramInitialTest {

    @Test fun `an ordinary latin name gives its first letter, capitalised`() {
        assertEquals("A", initialOf("alex rivera", "alex.rivera@masto.top"))
    }

    /** Not an ASCII-only rule: the same uppercasing has to reach a non-latin script. */
    @Test fun `a cyrillic name gives its first letter, capitalised`() {
        assertEquals("Я", initialOf("ярослав петров", "yaroslav@example.ru"))
    }

    /** No display name on the message: the address stands in, and it is the address that is read. */
    @Test fun `an empty label falls back to the address`() {
        assertEquals("B", initialOf("", "bob@example.org"))
    }

    /** Nothing to draw at all — the badge shows a question mark rather than an empty circle. */
    @Test fun `a label and a fallback with no letter or digit give a question mark`() {
        assertEquals("?", initialOf("!!!", "###"))
    }

    /** Quoted display names are common in mail headers; the quote is not the initial. */
    @Test fun `a label starting with punctuation skips to the first useful character`() {
        assertEquals("Z", initialOf("\"Zoé Martin\"", "zoe@example.lu"))
    }
}
