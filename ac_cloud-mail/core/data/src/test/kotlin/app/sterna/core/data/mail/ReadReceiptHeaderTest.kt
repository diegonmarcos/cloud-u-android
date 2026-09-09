package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The `Disposition-Notification-To:` parser (RFC 8098), EXECUTED on the shapes a real header
 */
class ReadReceiptHeaderTest {

    // ---- nothing to be had: the empty list, and never anything else ---------------------------

    @Test fun `an absent header names nobody`() {
        assertEquals(emptyList<String>(), ReadReceiptHeader.recipients(null))
    }

    @Test fun `an empty or blank header names nobody`() {
        assertEquals(emptyList<String>(), ReadReceiptHeader.recipients(""))
        assertEquals(emptyList<String>(), ReadReceiptHeader.recipients("   "))
        assertEquals(emptyList<String>(), ReadReceiptHeader.recipients(" \t\r\n "))
    }

    /**
     * The refusal that matters. A header that is there but yields no destination must answer
     */
    @Test fun `a header naming nothing usable names nobody`() {
        assertEquals(emptyList<String>(), ReadReceiptHeader.recipients("<>"))
        assertEquals(emptyList<String>(), ReadReceiptHeader.recipients("Ann Lee <>"))
        assertEquals(emptyList<String>(), ReadReceiptHeader.recipients("notanaddress"))
        assertEquals(emptyList<String>(), ReadReceiptHeader.recipients("@example.org"))
        assertEquals(emptyList<String>(), ReadReceiptHeader.recipients("ann@"))
        assertEquals(emptyList<String>(), ReadReceiptHeader.recipients(","))
    }

    /**
     * An angle bracket that never closes. The `<…>` form does not match, and the fallback then
     */
    @Test fun `an unclosed angle bracket names nobody`() {
        assertEquals(emptyList<String>(), ReadReceiptHeader.recipients("Ann <ann@example.org"))
        assertEquals(emptyList<String>(), ReadReceiptHeader.recipients("ann@example.org>"))
    }

    /**
     * And the one that FABRICATES a destination: an address written inside a quoted display
     */
    @Test fun `an address hidden in a quoted display name is not the destination`() {
        assertEquals(
            listOf("ann@example.org"),
            ReadReceiptHeader.recipients("\"<x@elsewhere.example>\" ann@example.org"),
        )
        assertEquals(
            listOf("ann@example.org"),
            ReadReceiptHeader.recipients("\"<x@elsewhere.example>\" <ann@example.org>"),
        )
    }

    /**
     * RFC 5322 §4.4's obsolete route: `<@relay,@hop:mailbox@domain>` is ONE address, and the
     */
    @Test fun `an obsolete source route names one mailbox, not the relays`() {
        assertEquals(
            listOf("ann@example.org"),
            ReadReceiptHeader.recipients("<@relay.example,@hop.example:ann@example.org>"),
        )
        assertEquals(
            listOf("ann@example.org", "bob@example.net"),
            ReadReceiptHeader.recipients("<@relay.example,@hop.example:ann@example.org>, bob@example.net"),
        )
    }

    /** A quoted string left unterminated is still salvaged down to the address it holds. */
    @Test fun `an unterminated quote does not cost the address`() {
        assertEquals(listOf("ann@example.org"), ReadReceiptHeader.recipients("\"ann@example.org"))
    }

    /** RFC 5322 §3.4 group syntax with no members — `undisclosed-recipients:;` and friends. */
    @Test fun `an empty group names nobody`() {
        assertEquals(emptyList<String>(), ReadReceiptHeader.recipients("undisclosed-recipients:;"))
        assertEquals(emptyList<String>(), ReadReceiptHeader.recipients("Team: ;"))
    }

    // ---- the ordinary shapes ------------------------------------------------------------------

    @Test fun `a bare address is the address`() {
        assertEquals(listOf("ann@example.org"), ReadReceiptHeader.recipients("ann@example.org"))
        assertEquals(listOf("ann@example.org"), ReadReceiptHeader.recipients("  ann@example.org  "))
    }

    @Test fun `a display name is not the address`() {
        assertEquals(listOf("ann@example.org"), ReadReceiptHeader.recipients("Ann Lee <ann@example.org>"))
        assertEquals(listOf("ann@example.org"), ReadReceiptHeader.recipients("<ann@example.org>"))
    }

    @Test fun `several addresses come back in the order they were written`() {
        assertEquals(
            listOf("ann@example.org", "bob@example.net", "carol@example.com"),
            ReadReceiptHeader.recipients("ann@example.org, Bob <bob@example.net>, carol@example.com"),
        )
    }

    /**
     * A comma INSIDE a quoted display name is not a separator — the classic split(',') defect.
     */
    @Test fun `a quoted display name holding a comma stays one address`() {
        assertEquals(
            listOf("ann@example.org", "bob@example.net"),
            ReadReceiptHeader.recipients("\"Lee, Ann\" <ann@example.org>, bob@example.net"),
        )
        assertEquals(
            listOf("ann@example.org", "bob@example.net"),
            ReadReceiptHeader.recipients(
                "\"ann@elsewhere.example, Lee\" <ann@example.org>, bob@example.net",
            ),
        )
    }

    /** …and an `@` inside a quoted display name is not an address either. */
    @Test fun `a quoted display name holding an at sign is not taken for the address`() {
        assertEquals(
            listOf("ann@example.org"),
            ReadReceiptHeader.recipients("\"not@a.destination\" ann@example.org"),
        )
        assertEquals(
            listOf("ann@example.org"),
            ReadReceiptHeader.recipients("\"not@a.destination\" <ann@example.org>"),
        )
    }

    /** A folded header (CRLF + WSP) is one value; the fold must not glue two tokens together. */
    @Test fun `a folded header reads as the one line it is`() {
        assertEquals(
            listOf("ann@example.org", "bob@example.net"),
            ReadReceiptHeader.recipients("Ann Lee\r\n <ann@example.org>,\r\n\tbob@example.net"),
        )
        // The name and the address on separate lines: glued, `Lee<ann@…>` still parses, but the
        // bare form below has nothing to save it.
        assertEquals(
            listOf("ann@example.org"),
            ReadReceiptHeader.recipients("Ann Lee\r\n ann@example.org"),
        )
    }

    /** A group with members names its members, label and semicolon dropped. */
    @Test fun `a group names its members`() {
        assertEquals(
            listOf("ann@example.org", "bob@example.net"),
            ReadReceiptHeader.recipients("Team: ann@example.org, bob@example.net;"),
        )
        assertEquals(
            listOf("ann@example.org"),
            ReadReceiptHeader.recipients("Team:ann@example.org;"),
        )
    }

    /** Junk between two good addresses costs only itself. */
    @Test fun `an unusable entry is dropped without taking the others with it`() {
        assertEquals(
            listOf("ann@example.org", "bob@example.net"),
            ReadReceiptHeader.recipients("ann@example.org, <>, , notanaddress, bob@example.net"),
        )
    }

    /**
     * No policy, stated as a test: the same address twice comes back twice, and an address that
     */
    @Test fun `no address is deduplicated, reordered or judged`() {
        assertEquals(
            listOf("ann@example.org", "ann@example.org"),
            ReadReceiptHeader.recipients("ann@example.org, Ann <ann@example.org>"),
        )
        assertEquals(
            listOf("tracker@third.party"),
            ReadReceiptHeader.recipients("Sender <tracker@third.party>"),
        )
    }

    /** Addresses a stricter validator would wrongly throw away. */
    @Test fun `an unusual but deliverable address is kept`() {
        assertEquals(listOf("postmaster@localhost"), ReadReceiptHeader.recipients("postmaster@localhost"))
        assertEquals(
            listOf("ann+receipts@example.org"),
            ReadReceiptHeader.recipients("Ann <ann+receipts@example.org>"),
        )
        assertEquals(
            listOf("ann@xn--e1afmkfd.xn--p1ai"),
            ReadReceiptHeader.recipients("ann@xn--e1afmkfd.xn--p1ai"),
        )
    }
}
