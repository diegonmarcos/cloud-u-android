package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * [DeliveredToInHeaderTest] executes the decision; this pins that the decision is still WIRED to
 */
class DeliveredToInHeaderCallSiteTest {

    /**
     * The decision is taken ONCE, at `MessageContent`'s call to `ConversationBody`, and it is
     */
    @Test fun `the reader decides the arrival line once, with the account's own addresses`() {
        assertEquals(
            "the header's arrival line must be decided by deliveredToInHeader(deliveredTo, " +
                "identityAddresses, ownMessage). ⛔ `accountAddresses` for the second argument is " +
                "the defect, not a synonym: it folds in the LOGIN, so an account authenticating as " +
                "sc12345@mail.hebergeur.tld and receiving at moi@mondomaine.tld counts two and " +
                "carries this line on every message. `emptyList()` disables the guard entirely, " +
                "and `false` for the third puts an arrival line on Sent and Drafts. Lines found:",
            listOf(
                "deliveredToLine = deliveredToInHeader(deliveredTo, identityAddresses, ownMessage),",
                "internal fun deliveredToInHeader(deliveredTo: String?, identityAddresses: " +
                    "List<String>, ownMessage: Boolean): String? {",
            ),
            codeLines(screen().readText()).filter { "deliveredToInHeader" in it },
        )
    }

    /**
     * And the decided line still travels — parameter by parameter — down to a `Text` in the
     */
    @Test fun `the decision reaches the header and is painted under the date`() {
        assertEquals(
            "the decided line must reach MessageHeader through ConversationBody and be painted by " +
                "a Text of the header's Column placed AFTER the date; nothing else in the " +
                "repository notices its removal, nor its move. Lines found:",
            listOf(
                "deliveredToLine = deliveredToInHeader(deliveredTo, identityAddresses, ownMessage),",
                "deliveredToLine: String? = null,",
                "deliveredToLine = deliveredToLine,",
                "deliveredToLine: String? = null,",
                "text = formatFull(msg.header.receivedAt),",
                "if (deliveredToLine != null) {",
                "text = stringResource(R.string.message_received_at, deliveredToLine),",
            ),
            codeLines(screen().readText()).filter {
                "deliveredToLine" in it || "formatFull(msg.header.receivedAt)" in it
            },
        )
    }

    /**
     * D6 — the flow that FEEDS all of the above, pinned WHOLE. The reader is a pager across
     */
    @Test fun `the identity addresses are refilled, from the identities alone, for every message`() {
        assertEquals(
            "MessageViewModel must expose identityAddresses and refill it per message with " +
                "store.identities(accountId).map { it.email } — identities ONLY. ⛔ Not " +
                "ownAddresses(), which folds in the login and is what accountAddresses is for. " +
                "Lines found:",
            listOf(
                "private val _identityAddresses = MutableStateFlow<List<String>>(emptyList())",
                "val identityAddresses = _identityAddresses.asStateFlow()",
                "_identityAddresses.value = store.identities(accountId).map { it.email }",
            ),
            codeLines(viewModel().readText()).filter { "_identityAddresses" in it },
        )
    }

    /**
     * The LABEL and its address are ONE translated string with a format argument, on the shape
     */
    @Test fun `the English label names the address through a format argument`() {
        assertEquals(
            "the base string must exist, keep its %1${'$'}s, and read as shipped",
            listOf("""<string name="message_received_at">Received at %1${'$'}s</string>"""),
            baseStrings().readText().lines().map { it.trim() }
                .filter { "message_received_at" in it },
        )
    }

    /** [text]'s lines, trimmed, comment-only lines dropped. */
    private fun codeLines(text: String): List<String> = text.lines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private fun screen(): File =
        locate("app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt")

    private fun viewModel(): File =
        locate("app/src/main/kotlin/app/sterna/ui/message/MessageViewModel.kt")

    private fun baseStrings(): File = locate("app/src/main/res/values/strings.xml")

    /** [relative] resolved from the test's working directory, walking up. */
    private fun locate(relative: String): File {
        val fromModule = relative.substringAfter("app/")
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            File(dir, relative).takeIf { it.isFile }?.let { return it }
            File(dir, fromModule).takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        error("Cannot find $relative from ${System.getProperty("user.dir")}")
    }
}
