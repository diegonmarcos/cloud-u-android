package app.sterna.ui.message

import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.core.jmap.model.EmailBodyValue
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [buildHtmlDocument] EXECUTED, and the document it emits read as text: the two reserved blanks
 */
class ReaderSpacerDocumentTest {

    private val noContent = "NO-CONTENT-SENTINEL"

    private fun email(): Email = Email(
        id = "1",
        htmlBody = listOf(EmailBodyPart(partId = "h", type = "text/html")),
        bodyValues = mapOf("h" to EmailBodyValue(value = "<p>BODY-SENTINEL</p>")),
    )

    @Test fun `the two spacers carry the vh length they were given, unit included`() {
        val doc = buildHtmlDocument(
            email(),
            topSpacerCss = bodySpacerCss(538, 2037),
            bottomSpacerCss = bodySpacerCss(250, 2037),
            noContent = noContent,
        )

        assertTrue(
            "the TOP spacer must be emitted verbatim as the vh length it was handed. The " +
                "collapsing header is opaque and covers it; a length in any other unit is " +
                "measured against the page's own scale, and the header ends up sitting on the " +
                "message instead of on blank space (#171).\nDocument was:\n$doc",
            """<div aria-hidden="true" style="height:26.4114vh"></div>""" in doc,
        )
        assertTrue(
            "the BOTTOM spacer (class s-end) must be emitted verbatim too — it is what keeps the " +
                "Reply/Forward bar off the last line of the message.\nDocument was:\n$doc",
            """<div aria-hidden="true" class="s-end" style="height:12.2730vh"></div>""" in doc,
        )
        assertTrue(
            "the top spacer must come before the body and the s-end spacer after it",
            doc.indexOf("height:26.4114vh") < doc.indexOf("BODY-SENTINEL") &&
                doc.indexOf("BODY-SENTINEL") < doc.indexOf("s-end\" style="),
        )
    }

    @Test fun `the dark template reserves the same two blanks`() {
        // The reader has TWO templates — the dark one inverts the whole page with a CSS filter and
        // is a different document (#149, the "white band in dark theme" bug). Both blanks are built
        // before that branch, so both templates get them; nothing else pins that, and a blank lost
        // on one side would put the opaque header on the message for every reader in dark mode.
        val doc = buildHtmlDocument(
            email(),
            theme = EmailTheme("#101418", "#e3e3e3", "#a8c7fa", dark = true),
            topSpacerCss = bodySpacerCss(538, 2037),
            bottomSpacerCss = bodySpacerCss(250, 2037),
            noContent = noContent,
        )

        assertTrue("the dark template must invert the page — otherwise this is not the dark document " +
            "and the assertions below say nothing about it.\nDocument was:\n$doc", "invert(1)" in doc)
        assertTrue(
            "both blanks must reach the dark document with their vh unit intact.\nDocument was:\n$doc",
            """<div aria-hidden="true" style="height:26.4114vh"></div>""" in doc &&
                """<div aria-hidden="true" class="s-end" style="height:12.2730vh"></div>""" in doc,
        )
    }

    @Test fun `an unmeasured reader reserves nothing at all`() {
        // The defaults are what every other caller of buildHtmlDocument gets. "0" is a valid CSS
        // length; an empty string would leave `height:` and drop the declaration.
        val doc = buildHtmlDocument(email(), noContent = noContent)

        assertTrue(
            "with nothing measured the document must reserve a zero-height blank, not an " +
                "unparsable one.\nDocument was:\n$doc",
            """<div aria-hidden="true" style="height:0"></div>""" in doc &&
                """<div aria-hidden="true" class="s-end" style="height:0"></div>""" in doc,
        )
    }
}
