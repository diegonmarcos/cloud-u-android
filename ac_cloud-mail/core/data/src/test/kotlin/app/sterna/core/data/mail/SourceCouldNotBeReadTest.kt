package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [sourceCouldNotBeRead], EXECUTED — the verdict standing between an IMAP source that never came
 */
class SourceCouldNotBeReadTest {

    /** THE case: `ImapClient.bodyItem` answers "" when the FETCH carried no body item. */
    @Test fun anEmptySourceCouldNotBeRead() {
        assertTrue(
            "an empty source is a message that did not come back, never an empty draft",
            sourceCouldNotBeRead(""),
        )
    }

    /**
     * The case that forbids `isEmpty()`, and it is a DELIBERATE over-refusal, not an observed
     */
    @Test fun aSourceOfNothingButALineEndingCouldNotBeRead() {
        assertTrue(
            "a bare CRLF names no header and no address: unknown, refuse",
            sourceCouldNotBeRead("\r\n"),
        )
    }

    /** The same deliberate over-refusal, in the shapes whitespace actually takes. */
    @Test fun aSourceOfNothingButWhitespaceCouldNotBeRead() {
        for (raw in listOf("   \r\n\t ", " ", "\n", "\t", "\r\n\r\n")) {
            assertTrue("raw = \"$raw\" carries no message at all", sourceCouldNotBeRead(raw))
        }
    }

    /** A real message: headers and a body. Nothing here may be refused. */
    @Test fun aRealSourceCanBeRead() {
        assertFalse(
            sourceCouldNotBeRead(
                "Subject: lunch\r\n" +
                    "From: ann@example.org\r\n" +
                    "To: bo@example.org\r\n" +
                    "\r\n" +
                    "one o'clock?\r\n",
            ),
        )
    }

    /**
     * THE control that forbids refusing too much: a draft with an empty text body is ordinary
     */
    @Test fun headersAloneWithAnEmptyBodyCanBeRead() {
        assertFalse(
            "headers with an empty text body are a legitimate draft, not an unknown",
            sourceCouldNotBeRead("Subject: hi\r\nFrom: a@b.c\r\n\r\n"),
        )
    }

    /** Every stored message carries at least a header block, so one header alone is enough. */
    @Test fun oneHeaderAloneCanBeRead() {
        assertFalse(sourceCouldNotBeRead("Subject: hi"))
    }

    /** Not a test that only ever refuses: the two sides, side by side. */
    @Test fun theVerdictSeparatesTheTwoSides() {
        assertTrue(sourceCouldNotBeRead(""))
        assertFalse(sourceCouldNotBeRead("From: a@b.c\r\n\r\nhi"))
    }
}

/**
 * THIS TEST READS SOURCE TEXT — the last resort, exactly as [DraftReceiptWiringTest] does:
 */
class SourceRefusalWiringTest {

    private fun bodyOf(function: String): String =
        DaoQuerySource.mailFunctionBody("MailRepository", function)

    /** The code lines of [body] naming [needle] — comments dropped, so prose can neither satisfy
     *  a rule nor break one. Whole lines: the assertions compare them, never search inside them. */
    private fun codeLinesNaming(body: String, needle: String): List<String> =
        body.lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { needle in it }

    @Test fun theOpenAsksTheDecisionAboutTheRawSourceItJustFetched() {
        assertEquals(
            "the argument is the SERVER's raw source — `body.text` would refuse legitimate " +
                "drafts with an empty body and let an unreadable source through",
            listOf("if (sourceCouldNotBeRead(raw)) {"),
            codeLinesNaming(bodyOf("openEmailImap"), "sourceCouldNotBeRead("),
        )
    }

    @Test fun theRefusalComesBeforeTheSourceIsParsed() {
        val lines = bodyOf("openEmailImap").lines().map { it.trim() }
        val refusal = lines.indexOf("if (sourceCouldNotBeRead(raw)) {")
        val parse = lines.indexOf("val body = MimeParser.parseBody(raw)")
        assertTrue("the refusal must be in openEmailImap at all", refusal >= 0)
        assertTrue("the parse must still be in openEmailImap: $lines", parse >= 0)
        assertTrue(
            "the refusal must come BEFORE the parse (refusal at $refusal, parse at $parse): " +
                "parsing first produces the very empty body the blank composer then shows",
            refusal < parse,
        )
    }

    /**
     * The line right UNDER the guard, and nothing else. A "does openEmailImap contain a
     */
    @Test fun theRefusalIsRaisedAsTheUnavailableMessageTheReadersAlreadyTranslate() {
        val lines = bodyOf("openEmailImap").lines().map { it.trim() }
        val refusal = lines.indexOf("if (sourceCouldNotBeRead(raw)) {")
        assertTrue("the refusal must be in openEmailImap at all", refusal >= 0)
        assertTrue(
            "the guard's own throw must be MessageUnavailableException, not another kind: " +
                "found \"${lines.getOrNull(refusal + 1)}\"",
            lines.getOrNull(refusal + 1)?.startsWith("throw MessageUnavailableException(") == true,
        )
    }

    /**
     * THE hole every trimmed-line assertion is blind to: wrapping the guard in
     */
    @Test fun theRefusalSitsInTheSameBlockAsTheParse() {
        val lines = bodyOf("openEmailImap").lines()
        val guard = lines.first { it.trim() == "if (sourceCouldNotBeRead(raw)) {" }
        val parse = lines.first { it.trim() == "val body = MimeParser.parseBody(raw)" }
        assertEquals(
            "same indentation means same block: nesting the guard inside `if (markRead) { … }` " +
                "would spare the COMPOSER path — fetchEmail passes markRead = false — and reopen " +
                "the whole defect while every trimmed-line assertion stayed green",
            parse.takeWhile { it == ' ' },
            guard.takeWhile { it == ' ' },
        )
    }

    /**
     * And the fetch line itself, whole. Nothing else pinned it, so an `.ifBlank { "" }` — or
     */
    @Test fun theSourceIsTakenFromTheServerAndHandedOnUntouched() {
        assertEquals(
            "the guard judges what the SERVER answered: any `.ifBlank { … }` or fallback on this " +
                "line turns the unknown into a value before the refusal can see it",
            listOf("val raw = imap.fetchSource(credentials, mailboxId, uid)"),
            codeLinesNaming(bodyOf("openEmailImap"), "imap.fetchSource("),
        )
    }

    /**
     * The size guard is a different answer ("too large" is not "unknown") and stays where it is,
     * after the parse: it reads `body.tooLarge`, which does not exist before `parseBody`.
     */
    @Test fun theSizeGuardIsUntouchedAndStillAsksTheParsedBody() {
        assertEquals(
            listOf("if (body.tooLarge) {"),
            codeLinesNaming(bodyOf("openEmailImap"), "tooLarge"),
        )
    }
}
