package app.sterna.ui.compose

import app.sterna.core.data.text.Block
import app.sterna.core.data.text.BlockKind
import app.sterna.core.data.text.Inline
import app.sterna.core.data.text.Link
import app.sterna.core.data.text.RichBody
import app.sterna.core.data.text.Span
import app.sterna.core.data.text.toPlainText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The signature is ordinary text in the body (inserted when compose opens), never appended at send
 */
class ComposeSignatureTest {

    private val sig = "Alex Rivera\nAcme"

    // --- Insertion ------------------------------------------------------------------------------

    @Test fun newMessageOpensWithADelimitedSignatureBlock() {
        assertEquals(
            "\n\n-- \nAlex Rivera\nAcme",
            bodyWithSignature(quoted = "", signature = sig, delimiter = true),
        )
    }

    @Test fun noSignatureLeavesTheBodyExactlyAsItWas() {
        assertEquals("", signatureBlock("", delimiter = true))
        assertEquals("", signatureBlock("   \n ", delimiter = true))
        assertEquals("", bodyWithSignature(quoted = "", signature = "", delimiter = true))
        assertEquals(
            "\n\nOn …, Alice wrote:\n> hi",
            bodyWithSignature("\n\nOn …, Alice wrote:\n> hi", "", delimiter = true),
        )
    }

    @Test fun replyPutsTheSignatureAboveTheQuoteByDefault() {
        val quote = "\n\nOn …, Alice wrote:\n> hi"
        val body = bodyWithSignature(quote, sig, delimiter = true)
        assertTrue(body.indexOf("-- ") < body.indexOf("Alice wrote"))
        assertEquals("\n\n-- \nAlex Rivera\nAcme\n\nOn …, Alice wrote:\n> hi", body)
    }

    @Test fun replyCanPutTheSignatureBelowTheQuote() {
        val quote = "\n\nOn …, Alice wrote:\n> hi"
        val body = bodyWithSignature(quote, sig, signatureBelowQuote = true, delimiter = true)
        assertTrue(body.indexOf("Alice wrote") < body.indexOf("-- "))
        assertEquals("\n\nOn …, Alice wrote:\n> hi\n\n-- \nAlex Rivera\nAcme", body)
    }

    @Test fun theSignatureIsTrimmedButKeepsItsInnerLineBreaks() {
        assertEquals("\n\n-- \nAlex\nAcme", bodyWithSignature("", "  \nAlex\nAcme\n  ", delimiter = true))
    }

    // --- From change (D5) -----------------------------------------------------------------------
    // Three cases, and only three: the identity being left had a signature and its block is still

    @Test fun changingIdentitySwapsAnUntouchedSignature() {
        val body = "Hi Bob," + bodyWithSignature("", sig, delimiter = true)
        assertEquals(
            "Hi Bob,\n\n-- \nAlex (work)",
            replaceSignatureBlock(body, sig, "Alex (work)", delimiter = true),
        )
    }

    @Test fun changingIdentityLeavesAnEditedSignatureAlone() {
        val edited = "Hi Bob,\n\n-- \nAlex Rivera\nAcme — mobile only"
        assertNull(replaceSignatureBlock(edited, sig, "Alex (work)", delimiter = true))
    }

    @Test fun changingIdentityLeavesADeletedSignatureDeleted() {
        assertNull(replaceSignatureBlock("Hi Bob,", sig, "Alex (work)", delimiter = true))
    }

    @Test fun switchingToASignaturelessIdentityRemovesTheBlock() {
        val body = "Hi Bob," + bodyWithSignature("", sig, delimiter = true)
        assertEquals("Hi Bob,", replaceSignatureBlock(body, sig, "", delimiter = true))
    }

    @Test fun theSwapNeverInvents_aBlankOutgoingSignatureIsTheInsertCase() {
        // replaceSignatureBlock is the swap half only: with nothing to match it declines, and the
        // caller reaches for insertSignatureBlock instead (tested below).
        assertNull(replaceSignatureBlock("Hi Bob,", "", "Alex (work)", delimiter = true))
    }

    // --- From change, third case: the identity being left had NO signature ----------------------

    @Test fun leavingASignaturelessIdentityInsertsTheNewOneAtTheEndOfANewMessage() {
        assertEquals(
            "Hi Bob,\n\n-- \nAlex (work)",
            insertSignatureBlock("Hi Bob,", "Alex (work)", delimiter = true),
        )
    }

    @Test fun onAnEmptyNewMessageTheInsertMatchesWhatThePrefillWouldHaveWritten() {
        assertEquals(
            bodyWithSignature("", sig, delimiter = true),
            insertSignatureBlock("", sig, delimiter = true),
        )
    }

    @Test fun onAReplyTheSignatureGoesAboveTheQuote_underTheAnswerBeingWritten() {
        // The layout a reply opens with: answer, signature, quote. Inserting must reproduce it,
        // not drop the signature at the very top above what the user has already typed.
        val quote = "\n\nOn …, Alice wrote:\n> hi"
        val body = "Hi Bob,$quote"
        assertEquals(
            "Hi Bob,\n\n-- \nAlex Rivera\nAcme\n\nOn …, Alice wrote:\n> hi",
            insertSignatureBlock(body, sig, quote, signatureBelowQuote = false, delimiter = true),
        )
    }

    @Test fun onAReplyTheBelowQuoteSettingPutsItAtTheEnd() {
        val quote = "\n\nOn …, Alice wrote:\n> hi"
        assertEquals(
            "Hi Bob,$quote\n\n-- \nAlex Rivera\nAcme",
            insertSignatureBlock("Hi Bob,$quote", sig, quote, signatureBelowQuote = true, delimiter = true),
        )
    }

    @Test fun anUntouchedReplyEndsUpExactlyAsThePrefillWouldHaveBuiltIt() {
        val quote = "\n\nOn …, Alice wrote:\n> hi"
        assertEquals(
            bodyWithSignature(quote, sig, delimiter = true),
            insertSignatureBlock(quote, sig, quote, delimiter = true),
        )
        assertEquals(
            bodyWithSignature(quote, sig, signatureBelowQuote = true, delimiter = true),
            insertSignatureBlock(quote, sig, quote, signatureBelowQuote = true, delimiter = true),
        )
    }

    @Test fun aQuoteTheUserHasEditedAwayFallsBackToTheEnd() {
        // The tail is no longer the quote we opened with: rather than guess a spot inside the
        // user's text, the block goes at the end where it is visible and movable.
        val quote = "\n\nOn …, Alice wrote:\n> hi"
        assertEquals(
            "Hi Bob, (quote deleted)\n\n-- \nAlex Rivera\nAcme",
            insertSignatureBlock("Hi Bob, (quote deleted)", sig, quote, delimiter = true),
        )
    }

    @Test fun insertingABlankSignatureIsANoOp() {
        assertEquals("Hi Bob,", insertSignatureBlock("Hi Bob,", "", delimiter = true))
        assertEquals("Hi Bob,", insertSignatureBlock("Hi Bob,", "   ", delimiter = true))
    }

    @Test fun onlyTheSignatureBlockIsSwapped_notAQuotedCopyOfIt() {
        // A reply quoting a previous message that ended with the same signature: the LAST block
        // (the live one, at the bottom) is the one swapped; the quoted copy above stays as quoted.
        val body = "Hi\n\n> \n> -- \n> Alex Rivera\n> Acme" + bodyWithSignature("", sig, delimiter = true)
        val swapped = replaceSignatureBlock(body, sig, "Alex (work)", delimiter = true)!!
        assertTrue(swapped.contains("> -- \n> Alex Rivera"))
        assertEquals("Hi\n\n> \n> -- \n> Alex Rivera\n> Acme\n\n-- \nAlex (work)", swapped)
    }

    // --- Send time: html alternative + HTML signature substitution -------------------------------

    @Test fun anHtmlAlternativeIsAlwaysProducedEvenWithNoSignature() {
        // The format=flowed guard: a lone text/plain body gets reflowed to one line by some servers
        // (Stalwart). The explicit <br> survives that, so the html alternative is never skipped.
        assertEquals(
            "line one<br>line two",
            htmlBodyWithSignature(RichBody.plain("line one\nline two"), "", "", delimiter = true),
        )
    }

    @Test fun htmlSignatureIsSubstitutedForTheIntactBlock() {
        val body = "Hi Bob," + bodyWithSignature("", sig, delimiter = true)
        assertEquals(
            "Hi Bob,<br><br>-- <br><b>Alex Rivera</b><br>Acme",
            htmlBodyWithSignature(RichBody.plain(body), sig, "<b>Alex Rivera</b><br>Acme", delimiter = true),
        )
    }

    @Test fun anEditedSignatureWinsOverTheStoredHtml() {
        val edited = "Hi Bob,\n\n-- \nAlex Rivera\nAcme — mobile only"
        assertEquals(
            "Hi Bob,<br><br>-- <br>Alex Rivera<br>Acme — mobile only",
            htmlBodyWithSignature(RichBody.plain(edited), sig, "<b>Alex Rivera</b>", delimiter = true),
        )
    }

    @Test fun aDeletedSignatureIsNotResurrectedInHtml() {
        assertEquals("Hi Bob,", htmlBodyWithSignature(RichBody.plain("Hi Bob,"), sig, "<b>Alex</b>", delimiter = true))
    }

    @Test fun theQuoteBelowTheSignatureSurvivesTheSubstitution() {
        // Signature above the quote (the default): what follows the block must come through.
        val body = bodyWithSignature("\n\nOn …, Alice wrote:\n> hi", sig, delimiter = true)
        assertEquals(
            "<br><br>-- <br><b>Alex</b><br><br>On …, Alice wrote:<br>&gt; hi",
            htmlBodyWithSignature(RichBody.plain(body), sig, "<b>Alex</b>", delimiter = true),
        )
    }

    @Test fun aSignatureRunOnByTheUserIsNotSubstituted() {
        // Text appended to the signature's last line means the user edited it: no substitution,
        // and the plain text they typed is what the recipient sees in both alternatives.
        val edited = "Hi" + bodyWithSignature("", sig, delimiter = true) + " (mobile)"
        assertEquals(
            "Hi<br><br>-- <br>Alex Rivera<br>Acme (mobile)",
            htmlBodyWithSignature(RichBody.plain(edited), sig, "<b>Alex</b>", delimiter = true),
        )
    }

    @Test fun theBodyIsStillEscapedAroundTheSubstitutedSignature() {
        val body = "1 < 2 & 3 > 2" + bodyWithSignature("", sig, delimiter = true)
        assertEquals(
            "1 &lt; 2 &amp; 3 &gt; 2<br><br>-- <br><b>Alex</b>",
            htmlBodyWithSignature(RichBody.plain(body), sig, "<b>Alex</b>", delimiter = true),
        )
    }

    @Test fun aPlainTextSignatureIsJustEscapedLikeTheRestOfTheBody() {
        val body = "Hi" + bodyWithSignature("", "Alex & co", delimiter = true)
        assertEquals(
            "Hi<br><br>-- <br>Alex &amp; co",
            htmlBodyWithSignature(RichBody.plain(body), "Alex & co", "", delimiter = true),
        )
    }

    // --- Send time: the styling (#131) meets the HTML signature ---------------------------------
    // The text alternative is the body's text; the html alternative carries the styling. The
    // imported HTML signature is still substituted for its block, but a range the user laid over
    // that block — even one character of it — IS an edit: their text wins, escaped and styled.

    @Test fun theStylingReachesTheHtmlAlternativeAndTheTextAroundItIsStillEscaped() {
        val body = RichBody("1 < 2 & bold", mapOf(Inline.BOLD to listOf(Span(8, 12))))
        assertEquals(
            "1 &lt; 2 &amp; <b>bold</b>",
            htmlBodyWithSignature(body, sig, "", delimiter = true),
        )
    }

    @Test fun boldAboveAnIntactBlockKeepsTheHtmlSignatureSubstitution() {
        val text = "Hi Bob," + bodyWithSignature("", sig, delimiter = true)
        val body = RichBody(text, mapOf(Inline.BOLD to listOf(Span(3, 6))))
        assertEquals(
            "Hi <b>Bob</b>,<br><br>-- <br><b>Alex Rivera</b><br>Acme",
            htmlBodyWithSignature(body, sig, "<b>Alex Rivera</b><br>Acme", delimiter = true),
        )
    }

    @Test fun aRangeReachingOneCharacterIntoTheBlockIsAnEdit_noSubstitution() {
        // "Hi Bob," is 7 characters; the block starts at 7 with its blank line. A range ending at
        // 8 covers the comma AND the first character of the block: the block is no longer intact,
        // so the user's text leaves escaped and styled, and the stored HTML stays out.
        val text = "Hi Bob," + bodyWithSignature("", sig, delimiter = true)
        val body = RichBody(text, mapOf(Inline.BOLD to listOf(Span(6, 8))))
        assertEquals(
            "Hi Bob<b>,<br></b><br>-- <br>Alex Rivera<br>Acme",
            htmlBodyWithSignature(body, sig, "<b>Alex Rivera</b><br>Acme", delimiter = true),
        )
    }

    @Test fun aRangeInsideTheBlockIsAnEditToo_whateverTheFamily() {
        // "Hi Bob,\n\n-- \n" is 13 characters: "Alex" sits at [13, 17). Italic, not bold — every
        // family counts.
        val text = "Hi Bob," + bodyWithSignature("", sig, delimiter = true)
        val body = RichBody(text, mapOf(Inline.ITALIC to listOf(Span(13, 17))))
        assertEquals(
            "Hi Bob,<br><br>-- <br><i>Alex</i> Rivera<br>Acme",
            htmlBodyWithSignature(body, sig, "<b>Alex Rivera</b><br>Acme", delimiter = true),
        )
    }

    @Test fun aLinkInsideTheBlockIsAnEditToo_noSubstitution() {
        // A link is the one kind of styling the substitution would DESTROY rather than merely
        // ignore: the stored HTML replaces those characters wholesale, so the address would not be
        // in the message at all while the words stayed on screen. "Hi Bob,\n\n-- \n" is 13
        // characters, so "Alex" sits at [13, 17).
        val text = "Hi Bob," + bodyWithSignature("", sig, delimiter = true)
        val body = RichBody(text, emptyMap(), emptyList(), listOf(Link(Span(13, 17), "https://alex.example")))
        assertEquals(
            "Hi Bob,<br><br>-- <br><a href=\"https://alex.example\">Alex</a> Rivera<br>Acme",
            htmlBodyWithSignature(body, sig, "<b>Alex Rivera</b><br>Acme", delimiter = true),
        )
    }

    @Test fun aLinkAboveAnIntactBlockKeepsTheHtmlSignatureSubstitution() {
        val text = "Hi Bob," + bodyWithSignature("", sig, delimiter = true)
        val body = RichBody(text, emptyMap(), emptyList(), listOf(Link(Span(3, 6), "https://bob.example")))
        assertEquals(
            "Hi <a href=\"https://bob.example\">Bob</a>,<br><br>-- <br><b>Alex Rivera</b><br>Acme",
            htmlBodyWithSignature(body, sig, "<b>Alex Rivera</b><br>Acme", delimiter = true),
        )
    }

    @Test fun aRangeEndingExactlyWhereTheBlockStartsLeavesItIntact() {
        // Half-open spans: [0, 7) touches the block without covering any of it.
        val text = "Hi Bob," + bodyWithSignature("", sig, delimiter = true)
        val body = RichBody(text, mapOf(Inline.BOLD to listOf(Span(0, 7))))
        assertEquals(
            "<b>Hi Bob,</b><br><br>-- <br><b>Alex Rivera</b><br>Acme",
            htmlBodyWithSignature(body, sig, "<b>Alex Rivera</b><br>Acme", delimiter = true),
        )
    }

    // --- One body, a list AND a link, through the two halves the send is made of (#131) ---------

    /**
     * `bodiesForSend` is a private method of an `AndroidViewModel` and cannot be run here; the two
     */
    @Test fun aBodyWithAListAndALinkCrossesBothAlternativesWhole() {
        val body = RichBody(
            "shopping\nmilk\nthe site",
            mapOf(Inline.BOLD to listOf(Span(0, 8))),
            listOf(Block(BlockKind.NUMBER, 1..2)),
            listOf(Link(Span(18, 22), "https://shop.example")),
        )

        assertEquals(
            "the html alternative carries the list as <ol>/<li> and the link as an <a> inside its " +
                "item — no signature is imported here, so the whole body is escaped and styled",
            "<b>shopping</b><br><ol><li>milk</li><li>the " +
                "<a href=\"https://shop.example\">site</a></li></ol>",
            htmlBodyWithSignature(body, signature = "", signatureHtml = "", delimiter = true),
        )
        assertEquals(
            "…and the text alternative carries the MARKER and the ADDRESS. ⛔ `body.text` here is " +
                "\"shopping\\nmilk\\nthe site\": no numbering, no URL, and a reader with no HTML " +
                "gets a message that names nothing.",
            "shopping\n1. milk\n2. the site <https://shop.example>",
            toPlainText(body),
        )
    }

    // --- Draft / undo: the body already carries its signature ------------------------------------

    @Test fun aReopenedBodyIsSentBackUnchanged() {
        // A reopened draft (or an undone send) comes back with the signature already inside it.
        // Nothing appends a second one: the html alternative is just the same body, escaped.
        val saved = "Hi Bob," + bodyWithSignature("", sig, delimiter = true)
        assertEquals(
            "Hi Bob,<br><br>-- <br>Alex Rivera<br>Acme",
            htmlBodyWithSignature(RichBody.plain(saved), sig, "", delimiter = true),
        )
        assertEquals(1, Regex("-- ").findAll(saved).count())
    }

    // --- The delimiter switched OFF (#90) --------------------------------------------------------
    // The point of the setting: the signature field then holds EXACTLY what goes into the message,
    // so whoever wants "__", a rule of dashes, or no separator at all types it there. Nothing else
    // about the signature changes — the blank line that detaches it from the message above stays.

    @Test fun withoutTheDelimiterTheBlockIsTheBlankLineAndTheSignatureAlone() {
        assertEquals("\n\nAlex Rivera\nAcme", signatureBlock(sig, delimiter = false))
        assertEquals(
            "\n\nAlex Rivera\nAcme",
            bodyWithSignature(quoted = "", signature = sig, delimiter = false),
        )
        assertFalse(signatureBlock(sig, delimiter = false).contains("-- "))
    }

    @Test fun withoutTheDelimiterABlankSignatureStillAddsNothing() {
        assertEquals("", signatureBlock("", delimiter = false))
        assertEquals("", signatureBlock("   \n ", delimiter = false))
        assertEquals("", bodyWithSignature(quoted = "", signature = "", delimiter = false))
    }

    @Test fun withoutTheDelimiterASeparatorTypedIntoTheFieldIsTheOnlyOne() {
        // The whole reason for the switch: "__" (or anything else) typed in the signature field
        // reaches the message untouched, and the app adds no line of its own on top of it.
        val own = "__\nAlex Rivera"
        assertEquals("\n\n__\nAlex Rivera", signatureBlock(own, delimiter = false))
        assertEquals("\n\n-- \n__\nAlex Rivera", signatureBlock(own, delimiter = true))
    }

    @Test fun withoutTheDelimiterTheReplyLayoutIsUnchanged() {
        val quote = "\n\nOn …, Alice wrote:\n> hi"
        assertEquals(
            "\n\nAlex Rivera\nAcme\n\nOn …, Alice wrote:\n> hi",
            bodyWithSignature(quote, sig, delimiter = false),
        )
        assertEquals(
            "\n\nOn …, Alice wrote:\n> hi\n\nAlex Rivera\nAcme",
            bodyWithSignature(quote, sig, signatureBelowQuote = true, delimiter = false),
        )
    }

    @Test fun withoutTheDelimiterTheFromSwapStillWorks() {
        val body = "Hi Bob," + bodyWithSignature("", sig, delimiter = false)
        assertEquals(
            "Hi Bob,\n\nAlex (work)",
            replaceSignatureBlock(body, sig, "Alex (work)", delimiter = false),
        )
    }

    @Test fun withoutTheDelimiterAnEditedSignatureIsStillLeftAlone() {
        val edited = "Hi Bob,\n\nAlex Rivera\nAcme — mobile only"
        assertNull(replaceSignatureBlock(edited, sig, "Alex (work)", delimiter = false))
    }

    @Test fun withoutTheDelimiterTheInsertMatchesWhatThePrefillWouldHaveWritten() {
        val quote = "\n\nOn …, Alice wrote:\n> hi"
        assertEquals(
            bodyWithSignature("", sig, delimiter = false),
            insertSignatureBlock("", sig, delimiter = false),
        )
        assertEquals(
            bodyWithSignature(quote, sig, delimiter = false),
            insertSignatureBlock(quote, sig, quote, delimiter = false),
        )
        assertEquals(
            bodyWithSignature(quote, sig, signatureBelowQuote = true, delimiter = false),
            insertSignatureBlock(quote, sig, quote, signatureBelowQuote = true, delimiter = false),
        )
    }

    @Test fun withoutTheDelimiterTheHtmlAlternativeCarriesNoDelimiterEither() {
        val body = "Hi Bob," + bodyWithSignature("", sig, delimiter = false)
        assertEquals(
            "Hi Bob,<br><br><b>Alex Rivera</b><br>Acme",
            htmlBodyWithSignature(RichBody.plain(body), sig, "<b>Alex Rivera</b><br>Acme", delimiter = false),
        )
    }

    @Test fun withoutTheDelimiterTheQuoteBelowTheSignatureStillSurvives() {
        val body = bodyWithSignature("\n\nOn …, Alice wrote:\n> hi", sig, delimiter = false)
        assertEquals(
            "<br><br><b>Alex</b><br><br>On …, Alice wrote:<br>&gt; hi",
            htmlBodyWithSignature(RichBody.plain(body), sig, "<b>Alex</b>", delimiter = false),
        )
    }

    // --- The trap: a body written in one shape, read back in the other (#90 §3) -------------------
    // The block is found "to the character", and two features hang off that lookup: the From swap

    @Test fun aBodyWrittenWithTheDelimiterIsStillFoundOnceTheSettingIsOff() {
        val written = "Hi Bob," + bodyWithSignature("", sig, delimiter = true)
        // Recognised, and rewritten in the shape the setting asks for today.
        assertEquals(
            "Hi Bob,\n\nAlex (work)",
            replaceSignatureBlock(written, sig, "Alex (work)", delimiter = false),
        )
        // The html alternative mirrors the BODY, not the setting: the two alternatives of one
        // message have to say the same thing, and this body still carries its "-- " line.
        assertEquals(
            "Hi Bob,<br><br>-- <br><b>Alex</b>",
            htmlBodyWithSignature(RichBody.plain(written), sig, "<b>Alex</b>", delimiter = false),
        )
    }

    @Test fun aBodyWrittenWithoutTheDelimiterIsStillFoundOnceTheSettingIsOn() {
        val written = "Hi Bob," + bodyWithSignature("", sig, delimiter = false)
        assertEquals(
            "Hi Bob,\n\n-- \nAlex (work)",
            replaceSignatureBlock(written, sig, "Alex (work)", delimiter = true),
        )
        assertEquals(
            "Hi Bob,<br><br><b>Alex</b>",
            htmlBodyWithSignature(RichBody.plain(written), sig, "<b>Alex</b>", delimiter = true),
        )
    }

    @Test fun theSettingsOwnShapeWinsWhenABodyCouldBeReadEitherWay() {
        // A body holding BOTH shapes: the live block is the one the composer would write today, so
        // that is the one swapped — the other stays as the user's text.
        val both = "Hi\n\nAlex Rivera\nAcme" + bodyWithSignature("", sig, delimiter = true)
        assertEquals(
            "Hi\n\nAlex Rivera\nAcme\n\n-- \nAlex (work)",
            replaceSignatureBlock(both, sig, "Alex (work)", delimiter = true),
        )
    }

    @Test fun theEditedSignatureRuleHoldsInBothShapes() {
        // Neither shape is a way in for a signature the user has run on: "(mobile)" appended to the
        // last line means edited, and an edited block is never swapped nor substituted.
        val editedWith = "Hi" + bodyWithSignature("", sig, delimiter = true) + " (mobile)"
        val editedWithout = "Hi" + bodyWithSignature("", sig, delimiter = false) + " (mobile)"
        assertNull(replaceSignatureBlock(editedWith, sig, "Alex (work)", delimiter = false))
        assertNull(replaceSignatureBlock(editedWithout, sig, "Alex (work)", delimiter = true))
    }
}
