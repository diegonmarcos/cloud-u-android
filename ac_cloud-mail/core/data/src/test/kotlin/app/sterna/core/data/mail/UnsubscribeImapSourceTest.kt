package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The IMAP half of the unsubscribe headers, read off a real message source — the same message
 */
class UnsubscribeImapSourceTest {

    private fun message(headers: String) =
        headers.trimIndent().replace("\n", "\r\n") + "\r\n\r\nHello.\r\n"

    @Test fun `both headers are read off the source`() {
        val (list, post) = unsubscribeHeadersOf(
            message(
                """
                From: news@list.example.com
                Subject: Weekly digest
                List-Unsubscribe: <https://l.example.com/u/abc>
                List-Unsubscribe-Post: List-Unsubscribe=One-Click
                """,
            ),
        )

        assertEquals("<https://l.example.com/u/abc>", list)
        assertEquals("List-Unsubscribe=One-Click", post)
    }

    /** Header names are case-insensitive on the wire, and senders use every casing there is. */
    @Test fun `the header names are matched whatever their casing`() {
        val (list, post) = unsubscribeHeadersOf(
            message(
                """
                From: news@list.example.com
                LIST-UNSUBSCRIBE: <mailto:leave@l.example.com>
                list-unsubscribe-post: List-Unsubscribe=One-Click
                """,
            ),
        )

        assertEquals("<mailto:leave@l.example.com>", list)
        assertEquals("List-Unsubscribe=One-Click", post)
    }

    /**
     * A folded header, which is the common shape for two URIs: read as ONE value, then split by
     */
    @Test fun `a folded header comes back whole and parses into both gestures`() {
        val (list, post) = unsubscribeHeadersOf(
            message(
                "From: news@list.example.com\n" +
                    "List-Unsubscribe: <https://l.example.com/u/abc>,\n" +
                    "\t<mailto:leave@l.example.com>\n" +
                    "List-Unsubscribe-Post: List-Unsubscribe=One-Click",
            ),
        )

        val options = UnsubscribeHeader.parse(list, post)
        assertEquals("https://l.example.com/u/abc", options?.oneClickUrl)
        assertEquals("leave@l.example.com", options?.mailto?.address)
    }

    /** An ordinary message reads as nothing to offer, which is not a failure of any kind. */
    @Test fun `a message with no such header reads as nothing offered`() {
        val (list, post) = unsubscribeHeadersOf(
            message(
                """
                From: alex@masto.top
                Subject: lunch?
                """,
            ),
        )

        assertNull(list)
        assertNull(post)
        assertNull(UnsubscribeHeader.parse(list, post))
    }

    /**
     * A header in the BODY is not a header. Worth pinning: an unsubscribe URL quoted inside a
     */
    @Test fun `a header-looking line inside the body is not read`() {
        val (list, _) = unsubscribeHeadersOf(
            "From: alex@masto.top\r\nSubject: fwd\r\n\r\n" +
                "List-Unsubscribe: <https://evil.example.com/u>\r\n",
        )

        assertNull(list)
    }
}
