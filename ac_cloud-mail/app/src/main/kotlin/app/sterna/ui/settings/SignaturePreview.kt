package app.sterna.ui.settings

import app.sterna.core.data.account.signatureWireHtml
import app.sterna.ui.compose.signatureBlockHtml

/**
 * The document the editor's preview WebView renders: a sample email around THE SIGNATURE AS SENT.
 *
 * The signature part is [signatureBlockHtml] - the very function the send path calls to put the
 * signature on the wire, which runs [signatureWireHtml] (the one sanitiser). There is no second
 * pipeline here, so the preview cannot show something a recipient will not get, or hide something they
 * will. Everything around it (sample text, light/dark card, width, reply quote) is this file's own,
 * trusted, markup.
 */
internal object SignaturePreview {

    /** [dark]: card colours; [mobile]: 360px card instead of 640px; [inReply]: the message sits inside a reply's quote. */
    data class Options(
        val dark: Boolean = false,
        val mobile: Boolean = false,
        val inReply: Boolean = false,
        /** Visual mode: the signature is editable in place and reports edits through the page's bridge. */
        val visual: Boolean = false,
        /** The "-- " line above the signature, as the owner's setting has it. */
        val delimiter: Boolean = true,
    )

    /** The JS interface name the visual-mode page calls; the host registers it under this name. */
    const val BRIDGE = "SigBridge"
    private const val EDIT_ID = "sig-edit"
    private const val NONCE = "sigedit"

    const val MOBILE_WIDTH_PX = 360
    const val DESKTOP_WIDTH_PX = 640

    fun document(source: String, options: Options = Options()): String {
        val fg = if (options.dark) "#e8e8e8" else "#202020"
        val card = if (options.dark) "#1f1f1f" else "#ffffff"
        val page = if (options.dark) "#101010" else "#e9e9e9"
        val rule = if (options.dark) "#555555" else "#bbbbbb"
        val width = if (options.mobile) MOBILE_WIDTH_PX else DESKTOP_WIDTH_PX
        val signature = signatureBlockHtml(source, options.delimiter)
        val body = if (options.visual) editable(signature, options.delimiter) else signature
        val message = SAMPLE_BODY + body
        val framed = if (options.inReply) {
            "<p>Sounds good, thanks!</p><p class=\"meta\">On Mon, 12 May 2025 at 09:14, Sam wrote:</p>" +
                "<blockquote>$message</blockquote>"
        } else {
            message
        }
        val script = if (options.visual) "<script nonce=\"$NONCE\">$VISUAL_SCRIPT</script>" else ""
        val scriptSrc = if (options.visual) "; script-src 'nonce-$NONCE'" else ""
        return "<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; img-src https: http: data:; style-src 'unsafe-inline'$scriptSrc\">" +
            "<style>html{background:$page}body{margin:0;padding:8px}" +
            ".mail{max-width:${width}px;margin:0 auto;padding:14px 16px;background:$card;color:$fg;" +
            "font:14px/1.5 sans-serif;word-wrap:break-word;border-radius:6px;box-shadow:0 0 0 1px $rule}" +
            ".mail p{margin:0 0 10px}.meta{color:$rule}" +
            ".mail blockquote{margin:8px 0 0;padding-left:12px;border-left:3px solid $rule}" +
            "img{max-width:100%;height:auto}a{color:#1a73e8}" +
            "#$EDIT_ID{outline:1px dashed #1a73e8;outline-offset:3px;min-height:1em}</style>" +
            "</head><body><div class=\"mail\">$framed</div>$script</body></html>"
    }

    private const val SAMPLE_BODY =
        "<p>Hi Sam,</p><p>Thanks for sending the draft over. I will go through it tonight and " +
            "come back to you tomorrow morning.</p><p>Best regards,</p>"

    /**
     * The signature block with its content (not its lead-in) in the editable region. The lead-in is
     * everything up to the first signature byte, so it is found by recomputing it from an empty signature.
     */
    private fun editable(block: String, delimiter: Boolean): String {
        val head = signatureBlockHtml("", delimiter)
        val content = block.removePrefix(head)
        return head + "<div id=\"$EDIT_ID\" contenteditable=\"true\" spellcheck=\"false\">$content</div>"
    }

    private const val VISUAL_SCRIPT =
        "var s=document.getElementById('$EDIT_ID');" +
            "function push(){$BRIDGE.onHtml(s.innerHTML)}" +
            "s.addEventListener('input',push);" +
            "window.sigExec=function(c,v){s.focus();document.execCommand('styleWithCSS',false,true);" +
            "document.execCommand(c,false,v||null);push()};"

    /**
     * Source for what the visual page reports: the editable region's innerHTML without the artefacts
     * a browser's editing adds. `rgb(r, g, b)` becomes `#rrggbb`, a lone trailing `<br>` goes, and the
     * editing attributes are dropped. Idempotent, and a no-op on markup the page did not touch.
     */
    fun fromVisual(inner: String): String {
        var out = inner
            .replace(EDIT_ATTRS, "")
            .replace(RGB) { m ->
                val (r, g, b) = m.destructured
                "#%02x%02x%02x".format(r.toInt().coerceIn(0, 255), g.toInt().coerceIn(0, 255), b.toInt().coerceIn(0, 255))
            }
            .replace(Regex("<br>\\s*$"), "")
        // A browser writes `style="color: #cc0000;"`; keep the owner's compact spelling.
        out = out.replace(Regex("""style="([^"]*)"""")) { m ->
            "style=\"" + m.groupValues[1].replace(Regex(";\\s*$"), "").replace(Regex(":\\s+"), ":").replace(Regex(";\\s+"), ";") + "\""
        }
        return out.trim()
    }

    /** What the visual page shows for [source] - the sanitised markup, i.e. [signatureWireHtml]. */
    fun visualContent(source: String): String = signatureWireHtml(source)

    private val EDIT_ATTRS = Regex("""\s(?:contenteditable|spellcheck)="[^"]*"""")
    private val RGB = Regex("""rgb\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})\s*\)""")
}
