package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageTextTest {
    @Test fun reflowJoinsSoftWrapsAndKeepsParagraphs() {
        // Thunderbird-style format=flowed: intra-paragraph lines end with a space (soft break),
        // paragraphs are separated by a blank line.
        val flowed = "First paragraph wrapped softly here \n" +
            "and continues after a soft break.\n" +
            "\n" +
            "Second paragraph after a blank line."
        val expected = "First paragraph wrapped softly here and continues after a soft break.\n" +
            "\n" +
            "Second paragraph after a blank line."
        assertEquals(expected, reflowFormatFlowed(flowed))
    }

    @Test fun reflowLeavesNonFlowedTextUntouched() {
        // No trailing spaces -> nothing to join; hard line breaks must be preserved.
        val plain = "Line one\nLine two\n\nNext block"
        assertEquals(plain, reflowFormatFlowed(plain))
    }

    @Test fun reflowUndoesSpaceStuffingAndKeepsSignatureBreak() {
        // A leading space is space-stuffing (removed). "-- " is a hard break despite its space.
        val input = " From the start\n-- \nSignature line"
        assertEquals("From the start\n-- \nSignature line", reflowFormatFlowed(input))
    }

    @Test fun emojiInTextIsWrappedForCounterInversion() {
        // Colour glyphs must carry the counter-filter; the surrounding text must not.
        assertEquals(
            "<p>Hi <span class=\"s-emo\">\uD83D\uDE00</span> there</p>",
            wrapEmoji("<p>Hi \uD83D\uDE00 there</p>"),
        )
    }

    @Test fun emojiInsideTagsAndUrlsIsLeftAlone() {
        // Wrapping inside a tag, an attribute or a URL would corrupt the message.
        val html = "<img alt=\"\uD83D\uDE00\" src=\"https://x/\uD83D\uDE00.png\" title='a>b'>" +
            "<style>.a::after{content:\"\uD83D\uDE00\"}</style><!-- \uD83D\uDE00 -->"
        assertEquals(html, wrapEmoji(html))
    }

    @Test fun zwjSequenceAndSkinToneStayInOneSpan() {
        // A family is ONE glyph: splitting it into spans would break it into separate people.
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"
        val wave = "\uD83D\uDC4B\uD83C\uDFFD"
        assertEquals("<span class=\"s-emo\">$family</span>", wrapEmoji(family))
        assertEquals("<span class=\"s-emo\">$wave</span>", wrapEmoji(wave))
    }

    @Test fun textGlyphsAreNotWrappedButForcedColourIs() {
        // Monochrome text symbols are painted in the body colour and must keep inverting with it;
        // a VS16 (or a keycap) means the author asked for the colour emoji.
        assertEquals("\u2713 \u00a9 \u2192", wrapEmoji("\u2713 \u00a9 \u2192"))
        assertEquals("<span class=\"s-emo\">\u2714\ufe0f</span>", wrapEmoji("\u2714\ufe0f"))
        assertEquals("<span class=\"s-emo\">1\ufe0f\u20e3</span>", wrapEmoji("1\ufe0f\u20e3"))
        assertEquals("\u2714\ufe0e", wrapEmoji("\u2714\ufe0e"))
    }

    @Test fun variationSelectorAfterANonEmojiBaseIsIgnored() {
        // A stray VS16 right after a character reference must NOT make the `;` an emoji base:
        // wrapping there would split the entity, so `&hearts;` would render as literal text and
        // the numeric tree reference would show a stray semicolon. Same for any plain ASCII.
        assertEquals("&hearts;\ufe0f", wrapEmoji("&hearts;\ufe0f"))
        assertEquals("&#127876;\ufe0f", wrapEmoji("&#127876;\ufe0f"))
        assertEquals("a;\ufe0f", wrapEmoji("a;\ufe0f"))
        assertEquals("p\ufe0f", wrapEmoji("p\ufe0f"))
        assertEquals(" \ufe0f", wrapEmoji(" \ufe0f"))
    }

    @Test fun plainTextBodiesAreUntouched() {
        // Plain text renders with the theme colours (no page invert), so nothing to counter-filter.
        val text = "Bonjour \uD83D\uDE00\nA + B < C"
        assertEquals(text, reflowFormatFlowed(text))
    }
}
