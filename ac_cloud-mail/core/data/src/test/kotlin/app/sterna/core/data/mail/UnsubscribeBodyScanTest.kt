package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The FALLBACK, and what its false positives look like — stated as tests rather than as a promise.
 *
 * This is a guess over sender-controlled HTML. These cases are the shape of the guess: what it
 * finds, what it refuses to find, and the one it gets wrong on purpose rather than by accident.
 */
class UnsubscribeBodyScanTest {

    private val words = listOf("unsubscribe", "opt-out", "désabonner")

    private fun scan(html: String? = null, plain: String? = null) =
        UnsubscribeBodyScan.scan(html, plain, words)?.pageUrl

    @Test fun theWordInTheUrlIsEnough() {
        assertEquals(
            "https://list.example.com/unsubscribe?u=42",
            scan(html = """<p>bye</p><a href="https://list.example.com/unsubscribe?u=42">Click here</a>"""),
        )
    }

    @Test fun theWordInTheAnchorTextAloneIsNotEnough() {
        // One weak signal. Anchor text is prose, and prose says "unsubscribe" about links that are
        // not one ("to unsubscribe, reply to this mail" wrapped around the sender's homepage).
        assertNull(scan(html = """<a href="https://example.com/home">Unsubscribe</a>"""))
    }

    @Test fun theWordInBothIsEnough() {
        assertEquals(
            "https://l.example.com/u/opt-out/9",
            scan(html = """<a href="https://l.example.com/u/opt-out/9">Unsubscribe here</a>"""),
        )
    }

    /** http:// hands the click, and the identifier in it, to anyone on the path. This road is
     *  already the low-confidence one; it does not also get to be the insecure one. */
    @Test fun plainHttpIsNeverOffered() {
        assertNull(scan(html = """<a href="http://list.example.com/unsubscribe">Unsubscribe</a>"""))
    }

    @Test fun aMessageWithNoSuchLinkOffersNothing() {
        assertNull(scan(html = """<a href="https://example.com/sale">Buy now</a><p>Regards</p>"""))
        assertNull(scan(html = "", plain = "Hello, see you Tuesday."))
        assertNull(scan(html = null, plain = null))
    }

    /** text/plain has no anchors, so the evidence is proximity — and the word must be NEAR the URL,
     *  not merely somewhere in a long message. */
    @Test fun aBareUrlNeedsTheWordBesideIt() {
        assertEquals(
            "https://l.example.com/u/7",
            scan(plain = "To unsubscribe from this list visit https://l.example.com/u/7"),
        )
        val faraway = "unsubscribe\n" + "x".repeat(400) + "\nhttps://l.example.com/u/7"
        assertNull("a word 400 characters away was treated as being about this URL", scan(plain = faraway))
    }

    /** Trailing sentence punctuation is not part of the URL. */
    @Test fun aBareUrlIsNotSwallowedWithItsFullStop() {
        assertEquals(
            "https://l.example.com/unsubscribe",
            scan(plain = "Opt-out at https://l.example.com/unsubscribe."),
        )
    }

    /**
     * THE KNOWN FALSE POSITIVE, written down rather than discovered.
     *
     * Senders routinely put ONE anchor where two belong: "Unsubscribe or manage your preferences",
     * pointing at a preferences page. It scores as high as a real opt-out can — the word is in both
     * the text and the URL — and the scan offers a settings page as a way out of the list. Nothing
     * in the text distinguishes the two cases, because the sender did not distinguish them either.
     *
     * This is the reason the result is always a PAGE the user looks at and never a POST the app
     * fires. A wrong page wastes a tap; a wrong POST is an unattributable request to a stranger,
     * sent by the app, confirming the address is live.
     */
    @Test fun aSingleAnchorOfferingBothIsOfferedAsAnUnsubscribeAndIsNotOne() {
        assertEquals(
            "https://l.example.com/prefs/unsubscribe-settings",
            scan(html = """<a href="https://l.example.com/prefs/unsubscribe-settings">Unsubscribe or manage your preferences</a>"""),
        )
    }

    /**
     * The tie the scoring DOES get right, kept so a later change to the weights cannot quietly
     * invert it: a footer with a preferences link and a real opt-out gives the opt-out, because the
     * opt-out carries the word in both its text and its URL and the preferences link only in its
     * URL. That is 3 against 2 — not luck, and not document order.
     */
    @Test fun aFooterOfferingBothLinksPrefersTheStrongerEvidence() {
        val footer = """
            <a href="https://l.example.com/prefs/unsubscribe-settings">Manage preferences</a>
            <a href="https://l.example.com/unsubscribe/9">Unsubscribe</a>
        """.trimIndent()
        assertEquals("https://l.example.com/unsubscribe/9", scan(html = footer))
    }

    /** The words are an argument, not a constant: a device in another language passes its own, and
     *  a caller passing none gets nothing rather than an English-only default. */
    @Test fun theWordListIsSuppliedByTheCaller() {
        val html = """<a href="https://l.example.com/desabonner/3">Se désabonner</a>"""
        assertNull("an empty word list still matched something", UnsubscribeBodyScan.scan(html, null, emptyList()))
        assertEquals(
            "https://l.example.com/desabonner/3",
            UnsubscribeBodyScan.scan(html, null, listOf("désabonner", "desabonner"))?.pageUrl,
        )
    }

    /** Whatever it finds, it is an OPEN_PAGE and nothing else — never the one-click POST. */
    @Test fun aGuessIsNeverAOneClickPost() {
        val found = UnsubscribeBodyScan.scan(
            """<a href="https://l.example.com/unsubscribe/9">Unsubscribe</a>""", null, words,
        )!!
        assertNull("a guessed link became a one-click POST", found.oneClickUrl)
        assertNull(found.mailto)
        assertEquals(UnsubscribeAction.OPEN_PAGE, found.preferredAction())
    }
}
