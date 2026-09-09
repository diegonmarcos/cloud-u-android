package app.sterna.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — it reads `ComposeScreen.kt` as text, proves nothing about
 */
class DiscardDialogWiringTest {

    @Test fun `the dialog is handed the two live values it decides on`() {
        // The auditors' two one-line mutations, both green until this rule existed:
        //  - `editingDraft = false` at the call site annuls #35 outright — the pure function is
        val block = discardBlock().replace(Regex("""\s+"""), " ")
        assertTrue(
            "the dialog must take its Save-draft rule from draftSaveAllowed(pgpMode) — the same " +
                "value the toolbar hides its Save action on. Block was:\n$block",
            "val mayKeepDraft = draftSaveAllowed(pgpMode)" in block,
        )
        assertTrue(
            "the dialog must decide its wording with " +
                "discardWording(editingOutbox, mayKeepDraft, editingDraft = savedDraftBehind). " +
                "`savedDraftBehind` is the live answer to \"is there a draft on the server behind " +
                "this screen\"; `draftId != null` is not — an undone send reopens with no draftId " +
                "in the route while its draft is still in Drafts. Block was:\n$block",
            "val wording = discardWording(editingOutbox, mayKeepDraft, editingDraft = savedDraftBehind)" in block,
        )
    }

    @Test fun `the discard button says what leaving actually does`() {
        val block = discardBlock().replace(Regex("""\s+"""), " ")
        assertTrue(
            "the discard button must be labelled from wording.keepsMessage: 'Discard changes' " +
                "wherever a copy survives (the outbox row, the draft in Drafts), 'Discard' only " +
                "where the message exists nowhere else. Keying it on fromOutbox left the draft " +
                "case saying 'Discard' beside a title and a body that both said 'changes'. " +
                "Block was:\n$block",
            "if (wording.keepsMessage) { R.string.compose_discard_changes } else " +
                "{ R.string.compose_discard_discard }" in block,
        )
    }

    @Test fun `each wording draws its own title, and the draft has its own key`() {
        val block = discardBlock().replace(Regex("""\s+"""), " ")
        assertTrue(
            "both draft cases must be titled compose_discard_title_changes. Reusing " +
                "compose_discard_title_outbox would say the right words for the wrong reason (that " +
                "key names the outbox case), and compose_discard_title puts back the sentence " +
                "#35/#127 is about: a draft that is still in Drafts, announced as discarded. The " +
                "encrypted one belongs here too — closing the padlock does not destroy the copy on " +
                "the server. Block was:\n$block",
            "wording == DiscardWording.DRAFT || wording == DiscardWording.ENCRYPTED_DRAFT -> " +
                "R.string.compose_discard_title_changes" in block,
        )
        assertTrue(
            "the encrypted-draft case must draw compose_discard_message_encrypted_draft: it still " +
                "owes the user the reason the Save button is gone, but it may not end on 'leaving " +
                "discards the message'. Block was:\n$block",
            "DiscardWording.ENCRYPTED_DRAFT -> R.string.compose_discard_message_encrypted_draft" in block,
        )
        assertTrue(
            "the plain encrypted case must keep its own body — the one that DOES announce the " +
                "destruction, because there the message really exists nowhere else. Block was:\n$block",
            "DiscardWording.ENCRYPTED -> R.string.compose_discard_message_encrypted" in block,
        )
        assertTrue(
            "the outbox case must keep compose_discard_title_outbox (#70). Block was:\n$block",
            "R.string.compose_discard_title_outbox" in block,
        )
        assertTrue(
            "compose_discard_title — the one title that claims the message is destroyed — must " +
                "still be reachable, for a message that exists nowhere else. Block was:\n$block",
            Regex("""else -> R\.string\.compose_discard_title\b""").containsMatchIn(block),
        )
    }

    @Test fun `the buttons are the ones discardChoices answers, and nothing else`() {
        val block = discardBlock()
        assertTrue(
            "the dialog's buttons must be drawn from discardChoices(mayKeepDraft, canSaveDraft) — " +
                "the decision DiscardChoicesTest runs. `canSaveDraft` is the toolbar's own " +
                "draftHasContent value: with anything else there, the dialog offers a tappable " +
                "'Save draft' over an emptied composer, and that tap DELETES the draft on the " +
                "server (#35). Block was:\n$block",
            "discardChoices(mayKeepDraft, canSaveDraft)" in block,
        )
        assertEquals(
            "exactly three TextButtons, one per DiscardChoice: an extra one is a button no test " +
                "here decides. Block was:\n$block",
            3, Regex("""TextButton\(""").findAll(block).count(),
        )
        assertTrue(
            "Save draft must be reachable ONLY from the SAVE_DRAFT arm: that arm is the only thing " +
                "standing between an encrypted message and a plaintext copy on the server, and " +
                "discardChoices never answers SAVE_DRAFT while encrypting (1.4.3). " +
                "Block was:\n$block",
            Regex(
                """DiscardChoice\.SAVE_DRAFT\s*->[\s\S]{0,200}?viewModel\.saveDraft\(""",
            ).containsMatchIn(block) &&
                Regex("""viewModel\.saveDraft\(""").findAll(block).count() == 1,
        )
        assertTrue(
            "the CANCEL arm must only close the dialog — it is the way BACK to the message, so it " +
                "may neither save nor discard. The close goes through the ViewModel: the flag is " +
                "its, so that the dialog survives the rotation that recreates MainActivity — see " +
                "[DialogFlagsSurviveRotationTest]. Block was:\n$block",
            Regex(
                """DiscardChoice\.CANCEL\s*->\s*TextButton\(onClick\s*=\s*\{\s*viewModel\.clearDiscard\(\)\s*}\s*,\s*enabled\s*=\s*enabled\s*\)""",
            ).containsMatchIn(block),
        )
    }

    // -- reading the source ---------------------------------------------------------------------

    /** The `if (showDiscard) { … }` block of `ComposeScreen.kt`, braces balanced, comments cut. */
    private fun discardBlock(): String {
        val code = codeLines(COMPOSE_SCREEN).joinToString("\n")
        val at = code.indexOf(GUARD)
        check(at >= 0) { "ComposeScreen.kt no longer contains '$GUARD' — did the dialog move?" }
        val start = code.indexOf('{', at) + 1
        var depth = 1
        var i = start
        while (i < code.length && depth > 0) {
            when (code[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        }
        return code.substring(start, (i - 1).coerceAtLeast(start))
    }

    /** The lines of [file] that are code, with comments taken off — load-bearing here: the comments
     *  inside this block name the very resources and calls the rules above forbid. */
    private fun codeLines(file: File): List<String> = file.readLines().mapNotNull { line ->
        val code = line.trimStart()
        if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) null
        else withoutTrailingComment(line).takeIf { it.isNotBlank() }
    }

    /** [line] up to its first `//` outside a double-quoted string; `\` escapes the next character. */
    private fun withoutTrailingComment(line: String): String {
        var inString = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inString && c == '\\' -> i++
                c == '"' -> inString = !inString
                !inString && c == '/' && line.getOrNull(i + 1) == '/' -> return line.substring(0, i).trimEnd()
            }
            i++
        }
        return line.trimEnd()
    }

    companion object {
        private const val GUARD = "if (showDiscard) {"
        private const val COMPOSE_SCREEN_PATH = "app/src/main/kotlin/app/sterna/ui/compose/ComposeScreen.kt"

        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, COMPOSE_SCREEN_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the source as text and needs a working directory inside the checkout",
                )
        }

        private val COMPOSE_SCREEN: File by lazy { File(root, COMPOSE_SCREEN_PATH) }
    }
}
