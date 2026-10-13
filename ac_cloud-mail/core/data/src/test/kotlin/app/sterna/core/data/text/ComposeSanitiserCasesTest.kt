package app.sterna.core.data.text

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the composer's quoted/forwarded HTML goes through: scripts and active content out, inline CSS kept. */
class ComposeSanitiserCasesTest {

    @Test fun `script elements go with their content`() {
        val out = sanitiseReceivedHtml("<p>hi</p><script>alert(1)</script><script src=\"https://evil.example/x.js\"></script>")
        assertFalse(out, out.contains("<script", ignoreCase = true))
        assertFalse(out, out.contains("alert(1)"))
        assertFalse(out, out.contains("evil.example"))
        assertTrue(out, out.contains("<p>hi</p>"))
    }

    @Test fun `event handlers are dropped and the element kept`() {
        val out = sanitiseReceivedHtml("<div onclick=\"steal()\" onload=\"x()\">text</div>")
        assertFalse(out, out.contains("onclick") || out.contains("onload") || out.contains("steal"))
        assertTrue(out, out.contains("text"))
    }

    @Test fun `a javascript link loses its target`() {
        val out = sanitiseReceivedHtml("<a href=\"javascript:alert(1)\">x</a>")
        assertFalse(out, out.contains("javascript", ignoreCase = true))
    }

    @Test fun `inline css survives`() {
        val out = sanitiseReceivedHtml("<p style=\"color:#d93025;font-weight:bold\">red</p>")
        assertTrue(out, out.contains("style=") && out.contains("color:#d93025") && out.contains("font-weight:bold"))
    }

    @Test fun `iframes and objects are gone`() {
        val out = sanitiseReceivedHtml("<iframe src=\"https://x.example\"></iframe><object data=\"x.swf\"></object>ok")
        assertFalse(out, out.contains("iframe") || out.contains("object"))
        assertTrue(out, out.contains("ok"))
    }
}
