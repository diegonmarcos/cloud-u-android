package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Every rule of [UnsubscribeHeader.parse], one at a time, plus the witness for each — because
 */
class UnsubscribeHeaderTest {

    private val oneClick = "List-Unsubscribe=One-Click"

    // ---- the https / One-Click pair, which is the whole privacy claim ----

    @Test fun `an https uri with the One-Click header is a one-click post`() {
        val options = UnsubscribeHeader.parse("<https://list.example.com/u/abc>", oneClick)

        assertEquals("https://list.example.com/u/abc", options?.oneClickUrl)
        assertNull("a one-click url must NOT also be offered as a page", options?.pageUrl)
        assertEquals(UnsubscribeAction.ONE_CLICK, options?.preferredAction())
    }

    /** The witness: the SAME header without the POST promise is a page, never a POST. */
    @Test fun `the same https uri without the One-Click header is only a page`() {
        val options = UnsubscribeHeader.parse("<https://list.example.com/u/abc>", null)

        assertNull("nothing may be POSTed without RFC 8058's promise", options?.oneClickUrl)
        assertEquals("https://list.example.com/u/abc", options?.pageUrl)
        assertEquals(UnsubscribeAction.OPEN_PAGE, options?.preferredAction())
    }

    @Test fun `the One-Click header is matched ignoring case and whitespace`() {
        listOf(
            "LIST-UNSUBSCRIBE=ONE-CLICK",
            "list-unsubscribe=one-click",
            "List-Unsubscribe=One-Click ",
            "List-Unsubscribe=\r\n\tOne-Click",
        ).forEach { post ->
            assertEquals(
                "\"$post\" is RFC 8058's value",
                "https://l.example.com/u",
                UnsubscribeHeader.parse("<https://l.example.com/u>", post)?.oneClickUrl,
            )
        }
    }

    /** And the witness for that: a value that is NOT RFC 8058's promises nothing. */
    @Test fun `a List-Unsubscribe-Post value that is not One-Click promises nothing`() {
        listOf("One-Click", "List-Unsubscribe=Yes", "", "   ").forEach { post ->
            val options = UnsubscribeHeader.parse("<https://l.example.com/u>", post)
            assertNull("\"$post\" must not authorise a POST", options?.oneClickUrl)
            assertEquals("https://l.example.com/u", options?.pageUrl)
        }
    }

    @Test fun `a One-Click header with no https uri offers no post at all`() {
        val options = UnsubscribeHeader.parse("<mailto:leave@list.example.com>", oneClick)

        assertNull(options?.oneClickUrl)
        assertNull(options?.pageUrl)
        assertEquals("leave@list.example.com", options?.mailto?.address)
        assertEquals(UnsubscribeAction.MAIL, options?.preferredAction())
    }

    // ---- http is refused outright (decision D4) ----

    @Test fun `an http uri is refused, never demoted to a page`() {
        assertNull(UnsubscribeHeader.parse("<http://list.example.com/u/abc>", oneClick))
        assertNull(UnsubscribeHeader.parse("<http://list.example.com/u/abc>", null))
    }

    @Test fun `an http uri is dropped without taking the mailto with it`() {
        val options = UnsubscribeHeader.parse(
            "<http://list.example.com/u>, <mailto:leave@list.example.com>",
            null,
        )

        assertNull(options?.pageUrl)
        assertEquals("leave@list.example.com", options?.mailto?.address)
    }

    // ---- several uris, and the order they are read in ----

    @Test fun `both a https uri and a mailto are kept, the post preferred`() {
        val options = UnsubscribeHeader.parse(
            "<https://list.example.com/u/abc>, <mailto:leave@list.example.com>",
            oneClick,
        )

        assertEquals("https://list.example.com/u/abc", options?.oneClickUrl)
        assertEquals("leave@list.example.com", options?.mailto?.address)
        assertEquals(UnsubscribeAction.ONE_CLICK, options?.preferredAction())
    }

    /** Without the POST promise the same pair prefers the mail: it is the one that loads no page. */
    @Test fun `a page and a mailto together prefer the mail`() {
        val options = UnsubscribeHeader.parse(
            "<https://list.example.com/u/abc>, <mailto:leave@list.example.com>",
            null,
        )

        assertEquals("https://list.example.com/u/abc", options?.pageUrl)
        assertEquals(UnsubscribeAction.MAIL, options?.preferredAction())
    }

    @Test fun `the first uri of each kind wins`() {
        val options = UnsubscribeHeader.parse(
            "<https://first.example.com/u>, <https://second.example.com/u>, " +
                "<mailto:first@example.com>, <mailto:second@example.com>",
            oneClick,
        )

        assertEquals("https://first.example.com/u", options?.oneClickUrl)
        assertEquals("first@example.com", options?.mailto?.address)
    }

    @Test fun `a comma inside a uri does not split it`() {
        val options = UnsubscribeHeader.parse("<https://l.example.com/u?id=a,b,c>", oneClick)

        assertEquals("https://l.example.com/u?id=a,b,c", options?.oneClickUrl)
    }

    // ---- the shapes senders actually emit ----

    @Test fun `a folded header is read as one line`() {
        val options = UnsubscribeHeader.parse(
            "<https://l.example.com/u/abc>,\r\n\t<mailto:leave@l.example.com>",
            oneClick,
        )

        assertEquals("https://l.example.com/u/abc", options?.oneClickUrl)
        assertEquals("leave@l.example.com", options?.mailto?.address)
    }

    @Test fun `a uri without angle brackets is accepted`() {
        val options = UnsubscribeHeader.parse("https://l.example.com/u/abc", oneClick)

        assertEquals("https://l.example.com/u/abc", options?.oneClickUrl)
    }

    @Test fun `an uppercase scheme is recognised`() {
        assertEquals(
            "HTTPS://l.example.com/u",
            UnsubscribeHeader.parse("<HTTPS://l.example.com/u>", oneClick)?.oneClickUrl,
        )
        assertEquals(
            "leave@l.example.com",
            UnsubscribeHeader.parse("<MAILTO:leave@l.example.com>", null)?.mailto?.address,
        )
    }

    // ---- the mailto parameters ----

    @Test fun `a mailto carries its subject and body, percent-decoded`() {
        val options = UnsubscribeHeader.parse(
            "<mailto:leave@l.example.com?subject=unsubscribe%20me&body=token%3Dxyz>",
            null,
        )

        assertEquals("leave@l.example.com", options?.mailto?.address)
        assertEquals("unsubscribe me", options?.mailto?.subject)
        assertEquals("token=xyz", options?.mailto?.body)
    }

    /**
     * A blank parameter is not a subject. An empty `?subject=` would otherwise send a mail with
     * no subject line at all, where the caller's own default is both truthful and useful.
     */
    @Test fun `blank mailto parameters are treated as absent`() {
        val options = UnsubscribeHeader.parse("<mailto:leave@l.example.com?subject=&body=>", null)

        assertEquals("leave@l.example.com", options?.mailto?.address)
        assertNull(options?.mailto?.subject)
        assertNull(options?.mailto?.body)
    }

    /** `+` is a literal in a mailto, not a space: `a+news@` and `a+bills@` are two subscriptions. */
    @Test fun `a plus in a mailto address stays a plus`() {
        val options = UnsubscribeHeader.parse("<mailto:leave+news@l.example.com>", null)

        assertEquals("leave+news@l.example.com", options?.mailto?.address)
    }

    @Test fun `a mailto with several addresses writes to the first`() {
        val options = UnsubscribeHeader.parse("<mailto:one@l.example.com,two@l.example.com>", null)

        assertEquals("one@l.example.com", options?.mailto?.address)
    }

    // ---- a target that cannot be NAMED is not offered ----

    /**
     * `<https://>` parses to a scheme and nothing else. It used to be offered like any other URL,
     */
    @Test fun `an https uri with no host is not offered at all`() {
        listOf("<https://>", "<https:///u/abc>", "<https://?token=xyz>").forEach { header ->
            assertNull("\"$header\" has no host to name", UnsubscribeHeader.parse(header, oneClick))
            assertNull("\"$header\" has no host to name", UnsubscribeHeader.parse(header, null))
        }
    }

    /** …and dropping it takes nothing else with it: the mail is still a way out. */
    @Test fun `a hostless https uri is dropped without taking the mailto with it`() {
        val options = UnsubscribeHeader.parse("<https://>, <mailto:leave@l.example.com>", oneClick)

        assertNull(options?.oneClickUrl)
        assertNull(options?.pageUrl)
        assertEquals("leave@l.example.com", options?.mailto?.address)
        assertEquals(UnsubscribeAction.MAIL, options?.preferredAction())
    }

    // ---- whitespace: unfold the header, but never rewrite a value ----

    /**
     * Only FOLDING whitespace goes. A `mailto:` body is free text the sender wrote, spaces
     */
    @Test fun `a space inside a mailto parameter survives`() {
        val options = UnsubscribeHeader.parse(
            "<mailto:leave@l.example.com?subject=please unsubscribe&body=please remove me>",
            null,
        )

        assertEquals("please unsubscribe", options?.mailto?.subject)
        assertEquals("please remove me", options?.mailto?.body)
    }

    /** The witness for the rule above: a header folded MID-URI is still put back together. */
    @Test fun `a uri folded in the middle is joined back up`() {
        val options = UnsubscribeHeader.parse(
            "<https://l.example.com/u/\r\n\tabc?token=xyz>",
            oneClick,
        )

        assertEquals("https://l.example.com/u/abc?token=xyz", options?.oneClickUrl)
    }

    // ---- nothing usable means nothing at all, never an error ----

    @Test fun `an unreadable or absent header yields no options`() {
        listOf(
            null,
            "",
            "   ",
            "<>",
            "<ftp://l.example.com/u>",
            "unsubscribe by writing to us",
            "<mailto:>",
        ).forEach { header ->
            assertNull("\"$header\" offers no usable gesture", UnsubscribeHeader.parse(header, oneClick))
        }
    }

    // ---- the host, as the confirmation dialog names it ----

    @Test fun `hostOf names who is about to be contacted`() {
        assertEquals("list.example.com", UnsubscribeHeader.hostOf("https://list.example.com/u/abc"))
        assertEquals("list.example.com", UnsubscribeHeader.hostOf("https://list.example.com"))
        assertEquals("list.example.com:8443", UnsubscribeHeader.hostOf("https://list.example.com:8443/u"))
        assertEquals("list.example.com", UnsubscribeHeader.hostOf("https://user:pw@list.example.com/u?x=1"))
        assertEquals("list.example.com", UnsubscribeHeader.hostOf("https://list.example.com?x=1"))
        assertNull(UnsubscribeHeader.hostOf("not a url"))
    }
}
