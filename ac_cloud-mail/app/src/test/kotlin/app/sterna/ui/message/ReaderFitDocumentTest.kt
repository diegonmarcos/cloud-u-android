package app.sterna.ui.message

import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.core.jmap.model.EmailBodyValue
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [buildHtmlDocument] EXECUTED over a synthetic desktop-authored email, and the page it emits judged
 * on whether its fit rules WIN — not on whether they are present.
 *
 * The trap this class exists to avoid: a viewport meta, `useWideViewPort` and `loadWithOverviewMode`
 * were ALL already set while the reader was overflowing, so asserting any of them passes against the
 * bug and proves nothing. What had actually gone wrong was the cascade — the one width rule in either
 * template was `img { max-width: 100% }`, without `!important`, and an inline `style="width:900px"`
 * on the message beats that every time. So every assertion below is about which declaration OUTRANKS
 * which, and about the fixture being genuinely too wide in the first place.
 */
class ReaderFitDocumentTest {

    private val noContent = "NO-CONTENT-SENTINEL"

    /** The synthetic email. Authored for this repository — never anyone's real mail. */
    private val fixture: String =
        javaClass.getResourceAsStream("/fit/wide-email.html")
            ?.bufferedReader()?.use { it.readText() }
            ?: error("fixture /fit/wide-email.html is missing from the test resources")

    private fun email(html: String = fixture): Email = Email(
        id = "1",
        htmlBody = listOf(EmailBodyPart(partId = "h", type = "text/html")),
        bodyValues = mapOf("h" to EmailBodyValue(value = html)),
    )

    private val light = EmailTheme("#ffffff", "#111111", "#0b5fff", false)
    private val dark = EmailTheme("#101418", "#e3e3e3", "#a8c7fa", dark = true)

    private fun doc(theme: EmailTheme, plainText: Boolean = false): String =
        buildHtmlDocument(email(), theme = theme, plainText = plainText, noContent = noContent)

    /** Everything between `<style>` and `</style>` — the rules OUR page brings. */
    private fun stylesheet(doc: String): String =
        doc.substringAfter("<style>").substringBefore("</style>")

    /**
     * `max-width`/`white-space` declarations in the stylesheet that do NOT carry `!important`.
     * Comment lines are stripped first: the prose in [FIT_CSS] explains the rules and would
     * otherwise satisfy a check meant to police them (this repository has shipped that bug before).
     */
    private fun weakDeclarations(css: String, property: String): List<String> =
        css.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .lineSequence()
            .filter { "$property:" in it }
            .filterNot { "!important" in it }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

    // ── The fixture has to be too wide, or nothing below means anything ──────────────────────────

    @Test fun `the fixture declares more width than a phone has, and the sanitiser keeps it`() {
        // False green #3: every email fits if it was authored to fit. This pins that the fixture is
        // adversarial, so replacing it with a well-behaved message fails HERE rather than silently
        // turning the rest of this class into a no-op.
        val page = doc(light)
        val shapes = mapOf(
            "a fixed-width layout table" to "width=\"1200\"",
            "a cell with a declared width" to "width=\"380\"",
            "an image declared wider than any phone" to "width=\"2000\"",
            "an INLINE width, which is what beats a non-important rule" to "style=\"width:900px\"",
        )
        for ((shape, markup) in shapes) {
            assertTrue(
                "$shape ($markup) must survive sanitiseReceivedHtml and reach the page. If the " +
                    "sanitiser started stripping it, this fixture no longer reproduces the overflow " +
                    "and every other assertion in this class is vacuous.\nDocument was:\n$page",
                markup in page,
            )
        }
        // One unbreakable token, long enough that no width cap can split it.
        val longestToken = page.split(Regex("[\\s<>\"]+")).maxOf { it.length }
        assertTrue(
            "the fixture must carry an unbreakable token far wider than a phone; longest was " +
                "$longestToken characters",
            longestToken >= 150,
        )
        assertTrue(
            "the fixture must carry a <pre> the MESSAGE wrote — it defaults to white-space: pre " +
                "and scrolls forever, which no width rule fixes.\nDocument was:\n$page",
            "<pre>" in page,
        )
    }

    // ── The cascade: our rules must OUTRANK the message's own declarations ───────────────────────

    @Test fun `no width rule reaches the page without the important that makes it win`() {
        // THE regression test. Before this fix the light and dark templates each carried
        // `img { max-width: 100%; height: auto; }` with no `!important`, and that is precisely why
        // the page overflowed while looking correct. Any future rule that forgets `!important` is
        // the same bug again, so this fails closed on ALL of them rather than naming one.
        for ((name, page) in listOf("light" to doc(light), "dark" to doc(dark))) {
            val css = stylesheet(page)
            val weak = weakDeclarations(css, "max-width")
            assertTrue(
                "the $name template has max-width declarations without !important: $weak\n" +
                    "An inline `style=\"width:900px\"` on the message outranks every one of them, " +
                    "so the page still overflows. Give them !important or delete them.",
                weak.isEmpty(),
            )
        }
    }

    @Test fun `the rule that caps every element is important, which is what beats an inline style`() {
        for ((name, page) in listOf("light" to doc(light), "dark" to doc(dark))) {
            val css = stylesheet(page)
            assertTrue(
                "the $name template must cap EVERY descendant of body, not just <img>: email holds " +
                    "its width on whatever element is to hand — a table, a td, a wrapper div. " +
                    "`img` alone was the old rule and the page overflowed anyway.\nCSS was:\n$css",
                "body * { max-width: 100% !important; }" in css,
            )
            assertTrue(
                "the $name template must restore the aspect ratio it just clamped — width alone " +
                    "squashes the fixture's 2000x600 image into the phone's ratio.\nCSS was:\n$css",
                "img, video, svg, canvas { height: auto !important; }" in css,
            )
            assertTrue(
                "the $name template must give the fixture's 200-character token somewhere to break; " +
                    "no width cap can split a single unbreakable word.\nCSS was:\n$css",
                "word-break: break-word !important;" in css,
            )
            assertTrue(
                "the $name template must wrap a <pre> the MESSAGE wrote (pre.plain is the body we " +
                    "paint ourselves and was already wrapped — this is the other one).\nCSS was:\n$css",
                "pre, code { white-space: pre-wrap !important;" in css,
            )
        }
    }

    @Test fun `both templates carry the fit rules, because they share no other CSS`() {
        // The two templates duplicate everything else they have in common (`.s-deceptive`,
        // `details.s-quote`, …). A fit rule that reached only one of them would mean the page fits
        // in one theme and overflows in the other — and he reads in dark.
        val darkPage = doc(dark)
        assertTrue(
            "this must be the INVERTED document, or the assertion below says nothing about it",
            "invert(1)" in darkPage,
        )
        assertTrue(
            "both templates must interpolate the SAME shared constant, so the two copies cannot " +
                "drift apart again",
            FIT_CSS.trim() in stylesheet(darkPage).trim() &&
                FIT_CSS.trim() in stylesheet(doc(light)).trim(),
        )
    }

    // ── What must NOT have changed ───────────────────────────────────────────────────────────────

    @Test fun `the plaintext path still paints its own wrapped body`() {
        // HTML is new here; plain text is what he has been reading for weeks. The fit rules are
        // harmless to it, but the rule that wraps the body WE paint must still be there and must
        // still be the app's own font.
        val page = buildHtmlDocument(email(), theme = light, plainText = true, noContent = noContent)
        assertTrue(
            "the derived plain-text body must still be wrapped by pre.plain.\nDocument was:\n$page",
            "pre.plain { white-space: pre-wrap; word-wrap: break-word; font-family: sans-serif; }" in page,
        )
        assertTrue(
            "a plain-text render must NOT be the inverted rich-HTML document",
            "invert(1)" !in page,
        )
    }

    @Test fun `an ordinary narrow message is not made worse`() {
        // False green #5 in reverse: the fix must not reach for anything that changes a message
        // that already fitted. A plain paragraph still comes out as a plain paragraph.
        val page = buildHtmlDocument(
            email("<p>PROSE-SENTINEL</p>"), theme = light, noContent = noContent,
        )
        assertTrue("ordinary prose must survive untouched.\nDocument was:\n$page",
            "<p>PROSE-SENTINEL</p>" in page)
        assertTrue(
            "nothing may pin a width on an ordinary message: the cap is a MAX, never a fixed size",
            "width: 100% !important" !in stylesheet(page).replace("max-width: 100% !important", ""),
        )
    }
}
