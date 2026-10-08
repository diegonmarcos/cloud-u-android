package app.sterna.ui.compose

import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The suggested reply handed to Reply all: who it goes to, where it lands in the body, and that the
 * composer never sends it by itself.
 */
class SuggestedReplyReplyAllTest {

    private val original = Email(
        id = "1",
        from = listOf(EmailAddress("Ana", "ana@example.com")),
        to = listOf(EmailAddress(null, "me@example.com"), EmailAddress(null, "bob@example.com")),
        cc = listOf(EmailAddress(null, "carol@example.com"), EmailAddress(null, "alias@example.com")),
    )

    @Test fun `reply-all carries every recipient minus the account's own addresses`() {
        val to = replyAllRecipients(original, setOf("me@example.com", "alias@example.com"))
        assertEquals("ana@example.com, bob@example.com, carol@example.com", to)
    }

    @Test fun `the suggestion is the first thing in the body, above the quoted original`() {
        val quoted = "\n\nOn Monday, Ana wrote:\n> hello"
        val body = withSuggestedReply("Thanks Ana, I will confirm today.", quoted)
        assertTrue("it starts with the suggestion, so a caret at 0 is on it", body.startsWith("Thanks Ana, I will confirm today."))
        assertTrue("a blank line, then the attribution", body.endsWith("\n\nOn Monday, Ana wrote:\n> hello"))
        assertEquals("exactly one blank line between them", 1, Regex("confirm today\\.\\n\\nOn Monday").findAll(body).count())
    }

    @Test fun `a body that starts mid-line, or with a signature, still gets a blank line`() {
        assertEquals("Hi\n\n-- \nme", withSuggestedReply("Hi", "-- \nme"))
        assertEquals("Hi\n\nx", withSuggestedReply("Hi", "\nx"))
    }

    @Test fun `no suggestion changes nothing`() {
        assertEquals("body", withSuggestedReply(null, "body"))
        assertEquals("body", withSuggestedReply("   ", "body"))
    }

    @Test fun `the caret opens at the start for a reply`() {
        // initialBodyCaret is what the composer applies to a prefilled body: a reply (not a draft, no
        // mailto body) puts the caret at 0, i.e. before the suggestion, ready to edit it.
        assertEquals(0, initialBodyCaret(bodyLength = 120, focus = ComposeFocus.BODY, isDraft = false))
    }

    @Test fun `nothing on the hand-off path sends`() {
        val vm = source("app/src/main/kotlin/app/sterna/ui/compose/ComposeViewModel.kt")
        val handoff = vm.substring(vm.indexOf("val pending = getApplication<Application>().container.pendingReplySuggestion"))
            .substringBefore("fun prefillFailed()")
        assertFalse("hand-off must not send", Regex("send|enqueue|schedule", RegexOption.IGNORE_CASE).containsMatchIn(handoff))
        assertTrue("only a reply-all of that message takes it", "opening == ComposeOpening.REPLY_ALL" in handoff && "it.emailId == replyToId" in handoff)
        assertTrue("and it is cleared whoever reads it", "pendingReplySuggestion = null" in handoff)
        val screen = source("app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt")
        val launcher = screen.substring(screen.indexOf("LocalReplyAllWith provides")).substringBefore("Scaffold(")
        assertFalse("the reader's Reply all button only opens the composer", Regex("send|enqueue", RegexOption.IGNORE_CASE).containsMatchIn(launcher))
        assertTrue("it opens the ordinary reply-all", "onReply(\"replyAll\", emailId, accountId)" in launcher)
        val box = source("app/src/main/kotlin/app/sterna/ui/message/ResumeBox.kt")
        assertFalse(Regex("send|enqueue", RegexOption.IGNORE_CASE).containsMatchIn(box))
    }

    private fun source(path: String): String {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.firstOrNull { File(it, path).isFile }
            ?: error("cannot find $path")
        return File(root, path).readLines().filterNot { val s = it.trim(); s.startsWith("//") || s.startsWith("*") || s.startsWith("/*") }.joinToString("\n")
    }
}
