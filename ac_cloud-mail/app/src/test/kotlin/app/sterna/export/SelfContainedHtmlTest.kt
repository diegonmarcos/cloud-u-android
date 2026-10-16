package app.sterna.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfContainedHtmlTest {
    private val dataUri = "data:image/png;base64,AAAA"

    @Test fun `headers block and body are in one document`() {
        val html = buildSelfContainedHtml(message(body = "<p>Body</p>"), LABELS, emptyMap())
        assertTrue(html.startsWith("<!DOCTYPE html>"))
        assertTrue(html.contains("<title>Rapport d'été</title>"))
        assertTrue(html.contains("<b>From:</b> Alex &lt;alex@example.org&gt;"))
        assertTrue(html.contains("<b>To:</b> me@example.org"))
        assertFalse("empty Cc is left out", html.contains("Cc:"))
        assertTrue(html.indexOf("<hr>") < html.indexOf("<p>Body</p>"))
    }

    @Test fun `css is inline and nothing external is referenced`() {
        val html = buildSelfContainedHtml(message(body = "<style>p{color:red}</style><p>x</p>"), LABELS, emptyMap())
        assertTrue(html.contains("<style>"))
        assertTrue(html.contains("p{color:red}"))
        assertFalse(html.contains("<link"))
        assertFalse(html.contains("<script src"))
    }

    @Test fun `cid images with bytes become data URIs, both spellings`() {
        val html = buildSelfContainedHtml(
            message(body = "<img src=\"cid:logo@x\"><img src=\"cid:<pic@x>\">"),
            LABELS, mapOf("logo@x" to dataUri, "<pic@x>" to "data:image/gif;base64,BBBB"),
        )
        assertTrue(html.contains("src=\"$dataUri\""))
        assertTrue(html.contains("src=\"data:image/gif;base64,BBBB\""))
        assertFalse(html.contains("cid:"))
    }

    @Test fun `a cid without bytes is left as is, never fetched`() {
        val html = buildSelfContainedHtml(message(body = "<img src=\"cid:gone@x\">"), LABELS, mapOf("other" to dataUri))
        assertTrue(html.contains("cid:gone@x"))
    }

    @Test fun `subject is escaped`() {
        val html = buildSelfContainedHtml(message(subject = "<script>alert(1)</script>"), LABELS, emptyMap())
        assertFalse(html.contains("<script>alert"))
        assertEquals(1, Regex("&lt;script&gt;").findAll(html.substringBefore("</title>")).count())
    }
}
