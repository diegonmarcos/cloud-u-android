package app.sterna.core.data.text

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Executes [safeFileName] against pairs written by hand — never by re-running the rule. What it
 */
class SafeFileNameTest {

    @Test fun `an accented subject is kept as it is`() {
        assertEquals("EXPORT-8BIT-03 Réunion décalée", safeFileName("EXPORT-8BIT-03 Réunion décalée", "message"))
    }

    @Test fun `every character hostile to a path becomes an underscore`() {
        assertEquals("Re_ a_b_c_d_e_f_g_h_i", safeFileName("Re: a/b\\c*d?e\"f<g>h|i", "message"))
    }

    @Test fun `a newline and a tab inside the subject become underscores`() {
        assertEquals("Invoice_March_final", safeFileName("Invoice\nMarch\tfinal", "message"))
    }

    @Test fun `the result is cut at maxLength code units`() {
        val long = "a".repeat(100)
        assertEquals(80, safeFileName(long, "message").length)
        assertEquals("a".repeat(80), safeFileName(long, "message"))
        assertEquals("aaaaa", safeFileName(long, "message", maxLength = 5))
    }

    /** Never seen red on a bench: added with its fix after review, on the final run's budget. */
    @Test fun `the cut never splits a surrogate pair`() {
        // 79 letters then U+1F600 (two code units): a cut at 80 would keep the high surrogate alone.
        val subject = "a".repeat(79) + "\uD83D\uDE00"
        assertEquals("a".repeat(79), safeFileName(subject, "message"))
        assertEquals("a".repeat(79) + "\uD83D\uDE00", safeFileName(subject, "message", maxLength = 81))
    }

    @Test fun `null, empty and blank fall back — three slashes do not`() {
        assertEquals("message", safeFileName(null, "message"))
        assertEquals("message", safeFileName("", "message"))
        assertEquals("message", safeFileName("   ", "message"))
        // Pinned as is: a subject made only of slashes is still a subject; it is the
        // replacement that turns it into `___`, and that is a name, not nothing.
        assertEquals("___", safeFileName("///", "message"))
    }

    @Test fun `surrounding whitespace is trimmed`() {
        assertEquals("Hello", safeFileName("  Hello  ", "message"))
    }
}
