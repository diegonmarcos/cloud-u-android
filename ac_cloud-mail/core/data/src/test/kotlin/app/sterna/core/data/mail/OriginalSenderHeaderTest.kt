package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

/**
 * [originalSenderHeaderOf], EXECUTED — which header names a forwarded message's real author, and
 */
class OriginalSenderHeaderTest {

    /** [text] as one RFC 2047 base64 encoded-word, the way a relay wraps a non-ASCII name. */
    private fun word(text: String): String =
        "=?utf-8?B?" + Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8)) + "?="

    /** The decoys, before and after: present in every fixture, chosen for the trap. */
    private fun around(vararg fields: Pair<String, String>): List<Pair<String, String>> =
        listOf("Received" to "from relay.example.test", "X-Spam-Score" to "0.1") +
            fields.toList() +
            listOf("X-Mailer" to "Relay 4.2", "To" to "alias@alias.example")

    @Test fun `the anonaddy spelling names the author`() {
        assertEquals(
            "Ann Lee <ann@example.org>",
            originalSenderHeaderOf(
                around("X-AnonAddy-Original-From-Header" to "Ann Lee <ann@example.org>"),
            ),
        )
    }

    /**
     * The four spellings in the wild, and one casing nobody would write on purpose. The rule is
     */
    @Test fun `every spelling of the same header is recognised, whatever its casing`() {
        assertEquals(
            "a@example.org",
            originalSenderHeaderOf(around("X-Google-Original-From" to "a@example.org")),
        )
        assertEquals(
            "b@example.org",
            originalSenderHeaderOf(around("X-SimpleLogin-Original-From" to "b@example.org")),
        )
        assertEquals(
            "c@example.org",
            originalSenderHeaderOf(around("X-Original-From" to "c@example.org")),
        )
        assertEquals(
            "d@example.org",
            originalSenderHeaderOf(around("x-anonaddy-ORIGINAL-from-header" to "d@example.org")),
        )
    }

    /** The ordinary message: nothing of the shape, nothing read — decoys included. */
    @Test fun `a message that was never relayed reads as null`() {
        assertNull(originalSenderHeaderOf(around("From" to "ann@example.org")))
    }

    /**
     * BOTH halves are required. `Original-From` with no `X-` is not an extension field, and a
     */
    @Test fun `original-from without the x- prefix is not this header`() {
        assertNull(originalSenderHeaderOf(around("Original-From" to "ann@example.org")))
    }

    /**
     * Two fields of the shape in one message: the FIRST occurrence is the one kept.
     */
    @Test fun `on a chain of two relays the first occurrence is the one kept`() {
        assertEquals(
            "inner@example.org",
            originalSenderHeaderOf(
                around(
                    "X-SimpleLogin-Original-From" to "inner@example.org",
                    "X-AnonAddy-Original-From-Header" to "outer@alias.example",
                ),
            ),
        )
    }

    /**
     * The sanitising door, crossed. The value is sender-controlled text on its way to a screen, so
     */
    @Test fun `an encoded-word value comes back decoded`() {
        assertEquals(
            "Émile Zola <emile@example.org>",
            originalSenderHeaderOf(
                around("X-Original-From" to word("Émile Zola <emile@example.org>")),
            ),
        )
    }

    /**
     * The other half of the same door, and the reason this header cannot be shown raw: it is
     */
    @Test fun `a bidi override is gone from the value`() {
        val answer = originalSenderHeaderOf(
            around("X-Original-From" to word("Ann ‮gro.elpmaxe@nna")),
        )

        assertEquals("Ann gro.elpmaxe@nna", answer)
        assertFalse("a bidi override reached the screen", answer!!.contains('‮'))
    }

    /** A header that is there and says nothing is not an origin: no line rather than a blank one. */
    @Test fun `a blank value reads as null`() {
        assertNull(originalSenderHeaderOf(around("X-Original-From" to "   ")))
        assertNull(originalSenderHeaderOf(around("X-Original-From" to "")))
    }
}
