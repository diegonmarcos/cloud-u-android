package app.sterna.ui.message

import app.sterna.core.data.mail.MailtoUnsubscribe
import app.sterna.core.data.mail.UnsubscribeAction
import app.sterna.core.data.mail.UnsubscribeHeader
import app.sterna.core.data.mail.UnsubscribeMailPreview
import app.sterna.core.data.mail.UnsubscribeOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The confirmation of a `mailto:` unsubscribe must show the TEXT it is about to send, not only
 */
class UnsubscribeConfirmationShowsMailTextTest {

    private val resignation = UnsubscribeHeader.parse(
        "<mailto:hr@exemple.org?subject=Je%20d%C3%A9missionne&body=Effectif%20ce%20jour.>",
        null,
    )!!

    /** The refuter's fixture, all the way to what the dialog hands the string resource. */
    @Test fun `the pending mail confirmation carries the subject and body that will be sent`() {
        val pending = PendingUnsubscribe(UnsubscribeAction.MAIL, resignation)

        assertEquals("hr@exemple.org", pending.target)
        assertEquals(
            UnsubscribeMailPreview(subject = "Je démissionne", body = "Effectif ce jour."),
            pending.mailPreview,
        )
    }

    /**
     * Nothing in the URI: the fixed English subject and an empty body are still shown, because
     * they are still what leaves. "Nothing to show" would be a third, untrue answer.
     */
    @Test fun `a bare mailto still shows the subject that will be sent`() {
        val pending = PendingUnsubscribe(
            UnsubscribeAction.MAIL,
            UnsubscribeOptions(mailto = MailtoUnsubscribe("leave@list.example.com")),
        )

        assertEquals(UnsubscribeMailPreview(subject = "Unsubscribe", body = ""), pending.mailPreview)
    }

    /** A POST or a page load sends no text of ours, so there is none to preview. */
    @Test fun `the other two gestures have no mail text to show`() {
        assertNull(
            PendingUnsubscribe(
                UnsubscribeAction.ONE_CLICK,
                UnsubscribeOptions(oneClickUrl = "https://l.example.com/u/abc"),
            ).mailPreview,
        )
        assertNull(
            PendingUnsubscribe(
                UnsubscribeAction.OPEN_PAGE,
                UnsubscribeOptions(pageUrl = "https://l.example.com/u/abc"),
            ).mailPreview,
        )
    }

    /**
     * SOURCE LINT. Pins the two ARGUMENTS and their order: subject first, body second. A swap
     */
    @Test fun `the dialog shows the preview, subject first and body second`() {
        val source = File(repoRoot, "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt").readText()

        val call = PREVIEW_CALL.find(source)
        assertTrue(
            "the mail confirmation no longer shows message_unsubscribe_confirm_mail_preview",
            call != null,
        )
        assertEquals(
            "the previewed subject and body, in that order",
            listOf("preview.subject", "preview.body"),
            call!!.groupValues.drop(1),
        )
    }

    /**
     * SOURCE LINT. The preview comes from the CAPTURED pending gesture, on the mail branch only:
     */
    @Test fun `the preview is read from the captured pending gesture, on the mail branch`() {
        val source = File(repoRoot, "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt").readText()

        assertTrue(
            "the dialog must read pending.mailPreview and only show it for UnsubscribeAction.MAIL",
            PREVIEW_BINDING.containsMatchIn(source),
        )
    }

    /** The string itself, in all nine languages: two placeholders, or a text goes unshown. */
    @Test fun `the preview string keeps both placeholders in every language`() {
        val files = listOf(File(res, "values/strings.xml")) + (res.listFiles() ?: emptyArray())
            .filter { it.isDirectory && it.name.startsWith("values-") }
            .map { File(it, "strings.xml") }
            .filter { it.isFile }
            .sortedBy { it.parentFile.name }
        assertTrue("expected nine languages, found ${files.size}", files.size == 9)

        val broken = files.filter { file ->
            val text = STRING.find(file.readText())
                ?.takeIf { it.groupValues[1] == "message_unsubscribe_confirm_mail_preview" }
                ?.groupValues?.get(2)
            text == null || !text.contains("%1\$s") || !text.contains("%2\$s")
        }.map { it.parentFile.name }

        assertEquals("a language whose preview drops the subject or the body", emptyList<String>(), broken)
    }

    private companion object {
        val STRING = Regex(
            """<string\s+name="(message_unsubscribe_confirm_mail_preview)"[^>]*>(.*?)</string>""",
            RegexOption.DOT_MATCHES_ALL,
        )

        val PREVIEW_CALL = Regex(
            """stringResource\(\s*R\.string\.message_unsubscribe_confirm_mail_preview\s*,""" +
                """\s*([A-Za-z0-9_.]+)\s*,\s*([A-Za-z0-9_.]+)\s*,?\s*\)""",
        )

        val PREVIEW_BINDING = Regex(
            """pending\.mailPreview\s*\?\.takeIf\s*\{\s*action\s*==\s*UnsubscribeAction\.MAIL\s*\}""",
        )

        val repoRoot: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "app/src/main/res/values/strings.xml").isFile }
                ?: error("cannot locate the checkout from ${File("").absolutePath}")
        }

        val res: File by lazy { File(repoRoot, "app/src/main/res") }
    }
}
