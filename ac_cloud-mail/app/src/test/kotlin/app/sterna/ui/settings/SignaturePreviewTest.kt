package app.sterna.ui.settings

import app.sterna.core.data.account.StoredSignature
import app.sterna.core.data.account.signatureWireHtml
import app.sterna.core.data.text.RichBody
import app.sterna.ui.compose.htmlBodyWithSignature
import app.sterna.ui.compose.signatureBlockHtml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignaturePreviewTest {

    private val sources = listOf(
        "<b>Alex</b><br><a href=\"https://x.y\">site</a>",
        "  <div style=\"color:#cc0000\">Hi</div>\n",
        "<p>ok</p><script>alert(1)</script><img src=\"https://a.b/c.png\" onerror=\"alert(2)\">",
        "<style>p{color:red}</style><table><tr><td>x</td></tr></table><iframe src=\"//e\"></iframe>",
        "<a href=\"javascript:alert(1)\">x</a><!-- c -->1 < 2",
    )

    // -- preview == what is sent --------------------------------------------------

    @Test fun thePreviewCarriesTheVerySignatureBlockTheSendPathPutsOnTheWire() {
        for (src in sources) for (delimiter in listOf(true, false)) {
            val stored = StoredSignature.of("s", "n", src)
            val body = "Hi\n\n" + (if (delimiter) "-- \n" else "") + stored.text
            val sent = htmlBodyWithSignature(RichBody.plain(body), stored.text, stored.html, delimiter)
            val block = signatureBlockHtml(src, delimiter)
            // The send path really emits this block...
            if (stored.isHtml) assertTrue("send path lacks $block in $sent", sent.contains(block))
            // ...and the preview embeds the same string, byte for byte.
            val doc = SignaturePreview.document(src, SignaturePreview.Options(delimiter = delimiter))
            assertTrue("preview lacks $block", doc.contains(block))
        }
    }

    @Test fun thePreviewAndTheStoredRenderableHtmlAreOneFunction() {
        for (src in sources) {
            val stored = StoredSignature.of("s", "n", src)
            if (stored.isHtml) assertEquals(stored.renderableHtml(), signatureWireHtml(src))
            assertEquals(signatureWireHtml(src), SignatureHtmlTools.previewDocument(src).substringAfter("<body>").substringBefore("</body>"))
        }
    }

    @Test fun nothingTheSanitiserDropsReachesThePreview() {
        val doc = SignaturePreview.document(sources[2])
        assertFalse(doc, doc.contains("alert"))
        assertFalse(doc.contains("onerror"))
        assertFalse(doc.contains("<script"))   // plain mode has no script at all
        assertFalse(doc.contains("script-src"))
    }

    @Test fun optionsOnlyChangeTheFrame() {
        val light = SignaturePreview.document(sources[0])
        val dark = SignaturePreview.document(sources[0], SignaturePreview.Options(dark = true))
        val mobile = SignaturePreview.document(sources[0], SignaturePreview.Options(mobile = true))
        val reply = SignaturePreview.document(sources[0], SignaturePreview.Options(inReply = true))
        assertTrue(light.contains("max-width:640px") && mobile.contains("max-width:360px"))
        assertTrue(dark.contains("#1f1f1f") && !light.contains("#1f1f1f"))
        assertTrue(reply.contains("<blockquote>") && !light.contains("<blockquote>"))
        val block = signatureBlockHtml(sources[0], true)
        listOf(light, dark, mobile, reply).forEach { assertTrue(it.contains(block)) }
        assertTrue(light.contains("default-src 'none'"))
    }

    // -- source <-> visual -----------------------------------------------------------

    @Test fun visualModeEditsTheSanitisedMarkupInPlaceAndOnlyThereMayScriptRun() {
        val doc = SignaturePreview.document(sources[2], SignaturePreview.Options(visual = true))
        assertTrue(doc.contains("contenteditable=\"true\""))
        assertTrue(doc.contains("script-src 'nonce-"))
        // the one script is ours: the signature's own <script> is gone before the page is built
        assertEquals(1, Regex("<script").findAll(doc).count())
        assertFalse(doc.contains("alert(1)"))
        assertTrue(doc.contains(signatureWireHtml(sources[2])))
    }

    @Test fun roundTripThroughTheVisualPageIsStable() {
        for (src in sources) {
            val shown = SignaturePreview.visualContent(src)
            val back = SignaturePreview.fromVisual(shown)
            // once through the sanitiser the markup is a fixed point of the round trip...
            assertEquals(shown.trim(), back.let { signatureWireHtml(it) })
            // ...and the round trip is idempotent
            assertEquals(back, SignaturePreview.fromVisual(SignaturePreview.visualContent(back)))
        }
    }

    @Test fun fromVisualRemovesBrowserEditingArtefacts() {
        val raw = "<span style=\"color: rgb(204, 0, 0);\">Hi</span> <b contenteditable=\"true\" spellcheck=\"false\">x</b><br>"
        assertEquals("<span style=\"color:#cc0000\">Hi</span> <b>x</b>", SignaturePreview.fromVisual(raw))
    }
}
