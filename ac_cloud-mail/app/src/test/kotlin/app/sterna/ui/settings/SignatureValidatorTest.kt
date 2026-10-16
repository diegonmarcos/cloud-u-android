package app.sterna.ui.settings

import app.sterna.ui.settings.SignatureValidator.Code
import app.sterna.ui.settings.SignatureValidator.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignatureValidatorTest {

    private fun codes(s: String) = SignatureValidator.validate(s).map { it.code }

    @Test fun cleanMarkupHasNoFindings() {
        assertEquals(emptyList<Code>(), codes("<div><b>Alex</b><br><img src=\"cid:x\" alt=\"logo\"><hr></div>"))
    }

    @Test fun anUnclosedTagIsReportedOnItsLine() {
        val issues = SignatureValidator.validate("<div>\n  <b>bold\n</div>")
        val mismatch = issues.single { it.code == Code.MISMATCHED_CLOSE }
        assertEquals(2, mismatch.line)
        assertEquals("b|div", mismatch.detail)
        assertEquals(Severity.ERROR, mismatch.severity)
    }

    @Test fun anOpenTagAtTheEndIsUnclosed() {
        val issue = SignatureValidator.validate("<div>hi").single()
        assertEquals(Code.UNCLOSED_TAG, issue.code)
        assertEquals("div", issue.detail)
    }

    @Test fun aClosingTagWithNoOpenerIsStray() {
        assertEquals(listOf(Code.STRAY_CLOSE), codes("hi</b>"))
    }

    @Test fun optionalEndTagsAndVoidElementsAreNotFindings() {
        assertEquals(emptyList<Code>(), codes("<ul><li>a<li>b</ul><p>one<p>two<br><hr>"))
    }

    @Test fun anUnterminatedTagOrCommentIsAnErrorAndNeverThrows() {
        assertTrue(Code.UNTERMINATED_TAG in codes("<a href=\"x"))
        assertTrue(Code.UNTERMINATED_TAG in codes("<div class"))
        assertTrue(Code.UNTERMINATED_COMMENT in codes("hi <!-- oops"))
        assertEquals(emptyList<Code>(), codes("1 < 2 and 3 > 2"))
    }

    @Test fun imagesAreChecked() {
        val c = codes("<img src=\"https://a.b/c.png\"><img src=\"data:image/png;base64,AAA\" alt=\"x\">")
        assertEquals(listOf(Code.EXTERNAL_IMAGE, Code.MISSING_ALT, Code.DATA_IMAGE), c)
    }

    @Test fun styleBlocksClassesAndHandlersAreFlagged() {
        val c = codes("<style>p{color:red}</style><p class=\"a\" onclick=\"x()\">t</p>")
        assertTrue(Code.STYLE_BLOCK in c)
        assertTrue(Code.CLASS_ATTRIBUTE in c)
        assertTrue(Code.STRIPPED_ATTRIBUTE in c)
    }

    @Test fun elementsTheSanitiserDropsAreFlaggedAndTheirBodyIsNotValidatedAsMarkup() {
        val c = codes("<script>if (a<b) {}</script><iframe src=\"x\"></iframe>")
        assertEquals(listOf(Code.STRIPPED_ELEMENT, Code.STRIPPED_ELEMENT), c)
    }

    @Test fun sizeWarnsThenBlocks() {
        assertEquals(emptyList<Code>(), codes("a".repeat(SignatureValidator.SIZE_WARN_BYTES)))
        val warn = SignatureValidator.validate("a".repeat(SignatureValidator.SIZE_WARN_BYTES + 1))
        assertEquals(listOf(Code.SIZE_WARNING), warn.map { it.code })
        assertFalse(SignatureValidator.blocksSave(warn))
        val big = SignatureValidator.validate("a".repeat(SignatureValidator.SIZE_LIMIT_BYTES + 1))
        assertTrue(SignatureValidator.blocksSave(big))
    }

    @Test fun theGutterGetsTheWorstSeverityPerLine() {
        val issues = SignatureValidator.validate("<img src=\"https://x.y/z.png\" alt=\"\">\n<b>x")
        val m = SignatureValidator.markers(issues)
        assertEquals(Severity.WARNING, m[1])
        assertEquals(Severity.ERROR, m[2])
    }

    @Test fun neverThrowsOnGarbage() {
        listOf("<", "<<<>>>", "</", "<a =", "<a b='", "<!--", "<!DOCTYPE", "&&;<>", "<\u0000>").forEach {
            SignatureValidator.validate(it)
        }
    }
}
