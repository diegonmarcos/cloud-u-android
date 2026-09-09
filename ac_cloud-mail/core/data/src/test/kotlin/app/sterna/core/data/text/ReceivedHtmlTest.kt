package app.sterna.core.data.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sanitisation policy for received mail, executed (#193).
 *
 * Every assertion here was watched failing against a deliberately broken sanitiser before it was
 * kept — the break for each is named in the comment above it, so the next reader can repeat it.
 */
class ReceivedHtmlTest {

    private fun sanitised(html: String) = sanitiseReceivedHtml(html)

    // --- Executable content -----------------------------------------------------------------------

    /** Break: remove "script" from DROPPED_WITH_CONTENT. */
    @Test fun aScriptElementAndItsCodeBothGo() {
        val out = sanitised("<p>hi</p><script>alert(1)</script><p>bye</p>")
        assertFalse("the code survived: $out", out.contains("alert"))
        assertFalse("the tag survived: $out", out.lowercase().contains("script"))
        // The prose on both sides of it is untouched: dropping an element must not cost a sentence.
        assertTrue(out, out.contains("hi") && out.contains("bye"))
    }

    /**
     * Break: open the attribute allowlist to default-allow AND remove the `on*` rule. Opening the
     * allowlist alone leaves this passing, which is the point of the rule — it is the defence that
     * still holds when the allowlist is wrong.
     */
    @Test fun everyEventHandlerIsDroppedWhateverItIsSpelledOn() {
        for (html in listOf(
            """<div onclick="steal()">text</div>""",
            """<img SRC="cid:x" ONERROR="steal()">""",
            """<div onmouseover="steal()">text</div>""",
            "<div onload=steal()>text</div>",
            // Behind a newline inside the tag, where a line-oriented scan would miss it.
            "<div\n onfocus=\"steal()\">text</div>",
        )) {
            val out = sanitised(html)
            assertFalse("handler survived in $out", Regex("(?i)\\son[a-z]+\\s*=").containsMatchIn(out))
            assertFalse("payload survived in $out", out.contains("steal"))
        }
    }

    /** Break: remove the `if (scheme !in allowed) return false` line in urlSchemeSurvives. */
    @Test fun aJavascriptHrefIsDroppedAndTheWordsAreKept() {
        val out = sanitised("""<a href="javascript:alert(1)">click</a>""")
        assertFalse(out, out.lowercase().contains("javascript"))
        assertTrue("the link text was lost: $out", out.contains("click"))
    }

    /**
     * Break: stop decoding entities and control characters before reading the scheme. Both of these
     * are `javascript:` by the time a browser resolves them, so a check against the raw text passes
     * them.
     */
    @Test fun aSchemeHiddenInEntitiesOrControlCharactersIsStillRead() {
        assertFalse(sanitised("""<a href="java&#115;cript:x()">c</a>""").contains("cript:"))
        assertFalse(sanitised("<a href=\"java\tscript:x()\">c</a>").contains("script:"))
        assertFalse(sanitised("""<a href="vbscript:msgbox">c</a>""").lowercase().contains("vbscript"))
    }

    /** Break: make urlSchemeSurvives return true for any `data:`, not only a picture one. */
    @Test fun aDataUrlIsAPictureOrItIsNotALoad() {
        assertFalse(sanitised("""<img src="data:text/html;base64,PHNjcmlwdD4=">""").contains("text/html"))
        assertTrue(sanitised("""<img src="data:image/png;base64,iVBOR">""").contains("data:image/png"))
    }

    // --- The half-a-tag attack --------------------------------------------------------------------

    /**
     * Break: stop the quoted-value reader at `>` as well as at the quote. A scan that ends a tag at
     * the first `>` cuts this one in half, and the half left behind is the sender's choice.
     */
    @Test fun aGreaterThanInsideAQuotedAttributeDoesNotEndTheTag() {
        val out = sanitised("""<a title="a>b" href="http://x.test/">t</a>""")
        assertTrue("the value was truncated: $out", out.contains("""title="a&gt;b""""))
        // …and the attribute AFTER it still parses, rather than being read as text.
        assertTrue(out, sanitised("""<img alt="a>b" src="cid:ok">""").contains("""src="cid:ok""""))
    }

    // --- Subresources and document-level directives -----------------------------------------------

    /** Break: remove each of these from DROPPED_WITH_CONTENT. */
    @Test fun nothingMayNameASubresourceOrRestateTheDocumentsDirectives() {
        for ((html, gone) in listOf(
            """<link rel="stylesheet" href="http://e.test/a.css"><p>keep</p>""" to "stylesheet",
            """<meta http-equiv="refresh" content="0;url=http://e.test"><p>keep</p>""" to "refresh",
            """<base href="http://e.test/"><p>keep</p>""" to "base",
            """<iframe src="http://e.test"></iframe><p>keep</p>""" to "iframe",
            """<form action="http://e.test"><input name="pw"></form><p>keep</p>""" to "input",
            "<svg><script>x()</script></svg><p>keep</p>" to "svg",
        )) {
            val out = sanitised(html)
            assertFalse("$gone survived: $out", out.lowercase().contains(gone))
            // Each of these is a VOID or self-contained element followed by real content. Dropping it
            // must never swallow what comes after — that failure looks like an empty message.
            assertTrue("the body after $gone was eaten: $out", out.contains("keep"))
        }
    }

    /** Break: stop counting nesting depth in skipElementContent. */
    @Test fun aNestedDroppedElementDoesNotLeaveItsSecondHalfBehind() {
        val out = sanitised("<form><form></form></form><p>tail</p>")
        assertFalse(out, out.lowercase().contains("form"))
        assertTrue(out, out.contains("tail"))
    }

    /** Break: empty VOID_ELEMENTS, so the content walk hunts a `</input>` that a stray tag provides. */
    @Test fun aVoidElementDoesNotSwallowTextUpToAStrayCloseTag() {
        assertTrue(sanitised("<input><p>keep me</p></input>").contains("keep me"))
    }

    // --- The stylesheet ---------------------------------------------------------------------------

    /** Break: return the CSS unchanged from sanitiseCss. */
    @Test fun anImportRuleGoesAndTheRestOfTheStylesheetStays() {
        val out = sanitised("""<style>@import url("http://e.test/x.css"); p { color: red }</style><p>x</p>""")
        assertFalse("a remote stylesheet is a tracking signal: $out", out.contains("@import"))
        assertTrue("the CSS was thrown away with it: $out", out.contains("color: red"))
    }

    /** Break: walk a `<style>`'s content as markup instead of copying it as CSS. */
    @Test fun cssIsNotWalkedAsMarkup() {
        assertTrue(sanitised("<style>a > b { color: blue }</style>").contains("a > b"))
    }

    // --- Remote pictures are the load-time gate's business, not this file's ------------------------

    /**
     * Break: drop `src` from img's attributes, or add http/https to a deny rule. The sanitiser must
     * NOT strip a remote picture: the "load images" affordance needs something to load, and whether
     * the fetch happens is decided at load time.
     */
    @Test fun aRemotePictureSurvivesSanitisationSoTheGateCanStillHoldIt() {
        assertTrue(sanitised("""<img src="https://t.test/pixel.gif" width="1">""")
            .contains("https://t.test/pixel.gif"))
    }

    /**
     * Break: add "srcset"/"background" to the allowed attributes. These name remote content that the
     * images affordance cannot see (it reads `src`), so a picture arriving only through one of them
     * would be blocked with no way for the user to ask for it — a message with silently missing
     * pictures and no button.
     */
    @Test fun remoteReferencesTheImagesAffordanceCannotSeeAreDropped() {
        assertFalse(sanitised("""<img src="cid:a" srcset="https://t.test/p.gif 1x">""").contains("srcset"))
        assertFalse(sanitised("""<td background="https://t.test/p.gif">x</td>""").contains("background"))
    }

    // --- Content is never lost --------------------------------------------------------------------

    /** Break: make the unknown-element default drop the subtree instead of unwrapping it. */
    @Test fun anUnknownElementLosesItsTagsAndKeepsItsWords() {
        assertTrue(sanitised("<o:p>word</o:p>").contains("word"))
        val out = sanitised("<html><head></head><body><p>msg</p></body></html>")
        assertTrue("the message was dropped with its wrapper: $out", out.contains("<p>msg</p>"))
        // `<html>`, `<head>` and `<body>` cannot legally appear inside a fragment inserted into a
        // document body, so their tags go and their content stays.
        assertFalse(out, out.contains("<body"))
    }

    /** Break: return `s.length` instead of `from` when no closing tag is found. */
    @Test fun anUnclosedDroppedElementCostsItsOwnTagAndNotTheMessage() {
        assertTrue(sanitised("<script>x=1<p>tail</p>").contains("tail"))
    }

    /** Break: emit a bare `<` unescaped. */
    @Test fun aBareAngleBracketInProseIsEscapedNotPassedOn() {
        assertTrue(sanitised("a < b and c > d").contains("&lt;"))
    }

    /** Break: stop dropping comments, so the markup inside one is read as text. */
    @Test fun aCommentAndAConditionalCommentBothGoWithoutSmugglingMarkup() {
        for (html in listOf(
            "<!-- <script>x()</script> --><p>y</p>",
            "<![if !IE]><script>x()</script><![endif]><p>y</p>",
        )) {
            val out = sanitised(html)
            assertFalse(out, out.contains("x()"))
            assertTrue(out, out.contains("y"))
        }
    }

    /** Break: escape `&` in attributeValueEscape. */
    @Test fun anAmpersandInAUrlIsNotEscapedTwice() {
        assertFalse(sanitised("""<a href="http://x.test/?a=1&amp;b=2">l</a>""").contains("&amp;amp;"))
    }

    @Test fun nothingInHandTerminatesQuietly() {
        assertEquals("", sanitiseReceivedHtml(null))
        assertEquals("", sanitiseReceivedHtml(""))
        assertTrue(sanitised("just words").contains("just words"))
        // Malformed input must terminate rather than spin: these are the shapes that loop a scanner
        // which forgets to advance.
        assertTrue(sanitised("<<<<>>>>").length >= 0)
        assertTrue(sanitised("""<div ==== "" = = >x</div>""").contains("x"))
        assertTrue(sanitised("<p>text</p><div").contains("text"))
        assertTrue(sanitised("<div>".repeat(500) + "x" + "</div>".repeat(500)).contains("x"))
    }

    // --- A link whose text disagrees with its target -----------------------------------------------

    private fun marked(html: String) = markDeceptiveLinks(html) { host -> "[goes to $host]" }

    /** Break: compare the hosts without taking the part after the last `@`. */
    @Test fun aLinkThatNamesOneHostAndGoesToAnotherIsMarkedWithTheRealOne() {
        for ((html, host) in listOf(
            """<a href="https://evil.test/x">bank.test</a>""" to "evil.test",
            """<a href="https://evil.test/">https://bank.test/login</a>""" to "evil.test",
            // The userinfo trick: everything before the last `@` is a username, not a host.
            """<a href="https://bank.test@evil.test/">bank.test</a>""" to "evil.test",
            // A redirector whose text names the destination it is not going to directly.
            """<a href="https://track.test/r?u=bank.test">bank.test</a>""" to "track.test",
            // The claim wrapped in formatting is still a claim.
            """<a href="https://evil.test/"><b>bank.test</b></a>""" to "evil.test",
        )) {
            assertTrue("not marked: ${marked(html)}", marked(html).contains("[goes to $host]"))
        }
    }

    /**
     * Break: mark whenever the two hosts differ, without requiring the text to have made a claim.
     * That break marks nine of these, which is the noise that trains a user to ignore the marker.
     */
    @Test fun aLinkThatClaimsNothingOrClaimsTrulyIsLeftAlone() {
        for (html in listOf(
            """<a href="https://bank.test/login">bank.test</a>""",
            // `www.` and a port and letter case are not lies about where a link goes.
            """<a href="https://www.bank.test/">bank.test</a>""",
            """<a href="https://bank.test:8443/">bank.test</a>""",
            """<a href="https://BANK.test/">bank.test</a>""",
            // "Click here" over a tracked link made no claim to be contradicted.
            """<a href="https://track.test/r?u=1">click here</a>""",
            // A sentence that merely CONTAINS a hostname is prose, not a claim.
            """<a href="https://track.test/">visit bank.test today</a>""",
            """<a href="mailto:a@bank.test">a@bank.test</a>""",
            """<a name="anchor">bank.test</a>""",
            """<a href="https://evil.test/"></a>""",
            "just words",
        )) {
            assertEquals("marked when it should not be", html, marked(html))
        }
    }

    @Test fun onlyTheDeceptiveOneOfTwoLinksIsMarked() {
        val out = marked(
            """<a href="https://bank.test/">bank.test</a> and <a href="https://evil.test/">bank.test</a>""",
        )
        assertEquals("marked more than once", 1, Regex("\\[goes to ").findAll(out).count())
        assertTrue(out, out.contains("[goes to evil.test]"))
    }
}
