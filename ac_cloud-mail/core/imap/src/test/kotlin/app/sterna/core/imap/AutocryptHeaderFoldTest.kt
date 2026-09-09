package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AutocryptHeader.fold], executed — the one piece of this volet that **no bench can measure**.
 */
class AutocryptHeaderFoldTest {

    /** Exactly what [OutgoingMime.build] writes in front of the folded value. */
    private val prefix = "${AutocryptHeader.NAME}: "

    private fun headerLines(value: String): List<String> =
        (prefix + AutocryptHeader.fold(value)).split("\r\n")

    private fun String.octets(): Int = toByteArray(Charsets.UTF_8).size

    private fun valueFor(key: String) = "addr=iris.s7@masto.top; keydata=$key"

    // --- the case the volet exists for -----------------------------------------------------------

    @Test fun `an RSA-4096 key is folded into lines RFC 5322 allows`() {
        val lines = headerLines(valueFor(AutocryptKeys.RSA_4096))
        assertTrue(
            "the RSA header must not fit on one line, or this test measures nothing",
            lines.size > 1,
        )
        lines.forEach {
            assertTrue("line of ${it.octets()} octets: RFC 5322 §2.1.1 caps a line at 998", it.octets() <= 998)
        }
    }

    @Test fun `every continuation begins with a space`() {
        val lines = headerLines(valueFor(AutocryptKeys.RSA_4096))
        lines.drop(1).forEach {
            // The space is what makes it a folded continuation and not a new header field, and what
            // keeps it clear of SmtpClient's dot-stuffing.
            assertTrue("a continuation must start with a space, got: ${it.take(20)}", it.startsWith(" "))
        }
    }

    @Test fun `unfolding gives the base64 back, character for character`() {
        val value = valueFor(AutocryptKeys.RSA_4096)
        val unfolded = AutocryptHeader.fold(value).replace("\r\n ", "")
        assertEquals(value, unfolded)
        assertEquals(
            "the key a correspondent reassembles must be the key we cached",
            AutocryptKeys.RSA_4096,
            unfolded.substringAfter("keydata="),
        )
    }

    @Test fun `a very long value folds onto as many lines as it needs`() {
        val value = "addr=iris.s7@masto.top; keydata=" + "k".repeat(4000)
        val lines = headerLines(value)
        assertEquals(5, lines.size)
        lines.forEach { assertTrue(it.octets() <= 998) }
        assertEquals(value, AutocryptHeader.fold(value).replace("\r\n ", ""))
    }

    // --- the case every bench shows ---------------------------------------------------------------

    @Test fun `an ed25519 key stays on a single line`() {
        val value = valueFor(AutocryptKeys.ED25519)
        val folded = AutocryptHeader.fold(value)
        assertEquals("nothing may be inserted into a value that already fits", value, folded)
        assertFalse(folded.contains("\r"))
        assertFalse(folded.contains("\n"))
        assertTrue((prefix + folded).octets() <= 998)
    }

    // --- the boundary itself ----------------------------------------------------------------------

    @Test fun `a value that exactly fills the first line is not folded`() {
        val value = "v".repeat(998 - prefix.length)
        assertEquals(value, AutocryptHeader.fold(value))
        assertEquals(998, (prefix + value).octets())
    }

    @Test fun `one character more folds, and the first line is still legal`() {
        val value = "v".repeat(998 - prefix.length + 1)
        val lines = headerLines(value)
        assertEquals(2, lines.size)
        assertEquals(998, lines[0].octets())
        assertEquals(" v", lines[1])
        assertEquals(value, AutocryptHeader.fold(value).replace("\r\n ", ""))
    }

    @Test fun `the budget is octets, not characters`() {
        // An address is allowed non-ASCII (RFC 6532). Counting characters would let a line of
        // 987 two-octet characters out at nearly 2 000 octets — legal-looking, and refused.
        val value = "é".repeat(900) + AutocryptKeys.ED25519
        headerLines(value).forEach { assertTrue("line of ${it.octets()} octets", it.octets() <= 998) }
        assertEquals(value, AutocryptHeader.fold(value).replace("\r\n ", ""))
    }
}
