package app.sterna.ui.message

import app.sterna.core.jmap.model.EmailAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [buildPrintDocument] EXECUTED, and the document it emits read as text. The print document is
 */
class PrintDocumentTest {

    private val labels = PrintLabels(from = "LBL-FROM", to = "LBL-TO", cc = "LBL-CC", date = "LBL-DATE")

    private fun header(subject: String = "SUBJECT-SENTINEL", cc: String = "") = PrintHeader(
        subject = subject,
        from = "Ada <ada@example.test>",
        to = "Bob <bob@example.test>",
        cc = cc,
        date = "DATE-SENTINEL",
    )

    private val rich = ReaderBody("<p>BODY-SENTINEL</p>", richHtml = true, derived = false)
    private val plain = ReaderBody("<pre class=\"plain\">BODY-SENTINEL</pre>", richHtml = false, derived = false)

    /**
     * Paper goes out WHOLE. The reader folds the trailing quoted history behind a `<details>`
     */
    @Test fun `the print document never folds the quoted history away`() {
        val quoted = ReaderBody(
            "<p>My answer.</p><blockquote type=\"cite\"><p>Forty messages.</p></blockquote>",
            richHtml = true,
            derived = false,
        )
        val doc = buildPrintDocument(header(), labels, quoted)

        assertFalse(
            "no <details> may reach paper.\nDocument was:\n$doc",
            "s-quote" in doc || "<details" in doc,
        )
        assertTrue(
            "and the quoted history itself must be printed, in full.\nDocument was:\n$doc",
            "<blockquote type=\"cite\"><p>Forty messages.</p></blockquote>" in doc,
        )
    }

    @Test fun `the subject, the sender and the body reach the document`() {
        val doc = buildPrintDocument(header(), labels, rich)

        assertTrue(
            "the subject must be printed as the document's title.\nDocument was:\n$doc",
            "SUBJECT-SENTINEL" in doc,
        )
        assertTrue(
            "the sender must be printed, escaped: the address is in angle brackets and would " +
                "otherwise be swallowed as a tag.\nDocument was:\n$doc",
            "Ada &lt;ada@example.test&gt;" in doc,
        )
        assertTrue(
            "the labels, the recipient and the date must be printed.\nDocument was:\n$doc",
            "LBL-FROM" in doc && "LBL-TO" in doc && "Bob &lt;bob@example.test&gt;" in doc &&
                "LBL-DATE" in doc && "DATE-SENTINEL" in doc,
        )
        assertTrue(
            "the body must follow the header.\nDocument was:\n$doc",
            "<p>BODY-SENTINEL</p>" in doc && doc.indexOf("DATE-SENTINEL") < doc.indexOf("BODY-SENTINEL"),
        )
    }

    @Test fun `a subject carrying markup is printed as text, not interpreted`() {
        val doc = buildPrintDocument(header(subject = "<b>x</b>"), labels, rich)

        assertTrue(
            "the subject must be HTML-escaped: a mail can put anything in its subject line.\n" +
                "Document was:\n$doc",
            "&lt;b&gt;x&lt;/b&gt;" in doc,
        )
        assertFalse(
            "the raw `<b>x</b>` must NOT appear — that is markup executed on the printed page.\n" +
                "Document was:\n$doc",
            "<b>x</b>" in doc,
        )
    }

    @Test fun `the print document has neither the reader's vh bands nor its dark invert`() {
        val doc = buildPrintDocument(header(), labels, rich)

        assertFalse(
            "no `vh` length may reach the print document: the reader's two bands reserve room " +
                "for the collapsing header and the reply bar, which the printer does not have.\n" +
                "Document was:\n$doc",
            "vh" in doc,
        )
        assertFalse(
            "no `invert(` may reach the print document: it is always light, whatever the theme.\n" +
                "Document was:\n$doc",
            "invert(" in doc,
        )
        assertTrue(
            "the print document must opt out of the device's dark theme the way the reader's " +
                "rich document does — `only light`, in the meta and in the CSS.\nDocument was:\n$doc",
            "<meta name=\"color-scheme\" content=\"only light\">" in doc &&
                "color-scheme: only light" in doc,
        )
    }

    @Test fun `a plain-text body prints in the reader's typeface, and a derived-text note stays a note`() {
        val doc = buildPrintDocument(header(), labels, plain)

        assertTrue(
            "`pre.plain` must print in sans-serif as the reader shows it, not in monospace.\nDocument was:\n$doc",
            Regex("""pre\.plain\s*\{[^}]*font-family:\s*sans-serif""").containsMatchIn(doc),
        )
        assertTrue(
            "`p.s-note` (the \"derived text\" line readerBody emits) must have its own smaller rule.\nDocument was:\n$doc",
            Regex("""p\.s-note\s*\{[^}]*font-size:\s*0\.85em""").containsMatchIn(doc),
        )
    }

    @Test fun `a plain-text body keeps its pre block`() {
        val doc = buildPrintDocument(header(), labels, plain)

        assertTrue(
            "a plain-text body must be inserted as the `<pre class=\"plain\">` the reader built.\n" +
                "Document was:\n$doc",
            "<pre class=\"plain\">BODY-SENTINEL</pre>" in doc,
        )
    }

    @Test fun `a rich body gets its inline images embedded`() {
        val body = ReaderBody("<p><img src=\"cid:logo\"></p>", richHtml = true, derived = false)
        val doc = buildPrintDocument(header(), labels, body, inlineImages = mapOf("logo" to "data:image/png;base64,AAA"))

        assertTrue(
            "the cid: reference must be replaced by its data URI — the printer's WebView has no " +
                "client that could resolve it.\nDocument was:\n$doc",
            "src=\"data:image/png;base64,AAA\"" in doc,
        )
        assertFalse("no cid:logo may remain.\nDocument was:\n$doc", "cid:logo" in doc)
    }

    @Test fun `the Cc line is printed only when there is someone in Cc`() {
        val without = buildPrintDocument(header(cc = ""), labels, rich)
        val with = buildPrintDocument(header(cc = "x@y"), labels, rich)

        assertFalse(
            "with nobody in Cc the Cc label must be ABSENT, not printed against a blank.\n" +
                "Document was:\n$without",
            "LBL-CC" in without,
        )
        assertTrue(
            "with someone in Cc the Cc label and the address must be printed.\nDocument was:\n$with",
            "LBL-CC" in with && "x@y" in with,
        )
    }

    @Test fun `the print job is named after the subject, and after the app when there is none`() {
        assertEquals(
            "a message with a subject names its print job after that subject, trimmed.",
            "Invoice 42",
            printJobName("  Invoice 42  ", "APP-SENTINEL"),
        )
        assertEquals(
            "a message with no subject names its print job after the app, never blank.",
            "APP-SENTINEL",
            printJobName("   ", "APP-SENTINEL"),
        )
    }

    @Test fun `a rich body's own dark-mode styles are defanged, so paper stays light`() {
        val body = ReaderBody(
            "<style>@media (prefers-color-scheme: dark) { body { background: #000 } }</style><p>BODY-SENTINEL</p>",
            richHtml = true,
            derived = false,
        )
        val doc = buildPrintDocument(header(), labels, body)

        assertTrue(
            "the message's dark-mode media query must be made always-false, as the reader's " +
                "document does: on a dark-theme device the WebView matches it and a marketing " +
                "email would print its dark variant.\nDocument was:\n$doc",
            "prefers-color-scheme:dark) and (max-width:0px" in doc,
        )
        assertFalse(
            "the original media query must not survive.\nDocument was:\n$doc",
            "(prefers-color-scheme: dark)" in doc,
        )
    }

    @Test fun `a plain-text body is left alone, even when its text quotes a dark-mode media query`() {
        val body = ReaderBody("<pre class=\"plain\">prefers-color-scheme: dark</pre>", richHtml = false, derived = false)
        val doc = buildPrintDocument(header(), labels, body)

        assertTrue(
            "text the reader painted herself is not markup; rewriting it would mutilate the message.\nDocument was:\n$doc",
            "<pre class=\"plain\">prefers-color-scheme: dark</pre>" in doc,
        )
    }

    @Test fun `a participant is printed with name AND address, or the bare address`() {
        assertEquals(
            "on paper the address is not one tap away: the name alone is not enough.",
            "Ada <ada@example.test>",
            printAddress(EmailAddress(name = " Ada ", email = "ada@example.test")),
        )
        assertEquals(
            "with no name, the address alone, without empty brackets.",
            "ada@example.test",
            printAddress(EmailAddress(name = "  ", email = "ada@example.test")),
        )
        assertEquals(
            "several participants are joined with a comma, like the header on screen.",
            "Ada <ada@example.test>, bob@example.test",
            printAddresses(listOf(EmailAddress("Ada", "ada@example.test"), EmailAddress(null, "bob@example.test"))),
        )
    }

    @Test fun `embedInlineImages replaces both spellings of a cid reference`() {
        val out = embedInlineImages(
            "<img src=\"cid:logo\"><img src=\"cid:<logo>\">",
            mapOf("logo" to "data:image/png;base64,AAA"),
        )

        assertTrue(
            "both `cid:logo` and `cid:<logo>` must be replaced by the data URI — the reader's " +
                "document does both, in that order.\nOutput was:\n$out",
            out == "<img src=\"data:image/png;base64,AAA\"><img src=\"data:image/png;base64,AAA\">",
        )
    }
}
