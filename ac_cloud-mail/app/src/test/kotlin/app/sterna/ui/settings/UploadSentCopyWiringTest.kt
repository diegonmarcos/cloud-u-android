package app.sterna.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and disclaimer as [NotificationsGateWiringTest].
 */
class UploadSentCopyWiringTest {

    @Test fun `the switch is seeded from the stored setting`() {
        assertEquals(
            "the 'Upload sent messages' switch no longer starts from the account's stored value; " +
                "seeded from anything else it shows a state the account is not in.",
            listOf("var uploadSentCopy by remember(accountId) { mutableStateOf(account.uploadSentCopy) }"),
            block(SETTINGS_SCREEN, "var uploadSentCopy by remember", 1),
        )
    }

    @Test fun `the switch writes at the toggle, not at Save`() {
        assertEquals(
            "the 'Upload sent messages' switch changed shape. It must store the new value the " +
                "moment it is toggled: routed through markEdited()/save() instead, the choice is " +
                "lost by leaving the screen with the back gesture.",
            listOf(
                "SettingSwitch(",
                "title = stringResource(R.string.settings_upload_sent_title),",
                "subtitle = stringResource(R.string.settings_upload_sent_subtitle),",
                "checked = uploadSentCopy,",
                "onCheckedChange = {",
                "uploadSentCopy = it",
                "viewModel.setUploadSentCopy(accountId, it)",
                "},",
                ")",
            ),
            blockEndingAt(SETTINGS_SCREEN, "title = stringResource(R.string.settings_upload_sent_title),", 1, 9),
        )
    }

    @Test fun `the row sits in the IMAP block, after the SMTP fields`() {
        val lines = codeLines(SETTINGS_SCREEN)
        val smtpWarning = only(lines, "PlaintextSecurityWarning(smtpSecurity)")
        val row = only(lines, "title = stringResource(R.string.settings_upload_sent_title),")
        val sharedUsername = only(lines, "label = stringResource(R.string.settings_username_label),")
        assertTrue(
            "the switch must sit after the SMTP fields (line $smtpWarning) and before the fields " +
                "shared with JMAP accounts (line $sharedUsername); it is at line $row. Outside the " +
                "`if (isImap)` block it offers a setting that commands nothing on a JMAP account.",
            row in (smtpWarning + 1) until sharedUsername,
        )
    }

    @Test fun `the view model stores the value and refreshes`() {
        assertEquals(
            "AccountsViewModel.setUploadSentCopy no longer writes through the store, so the switch " +
                "moves on screen and the delivery keeps reading the old value.",
            listOf(
                "fun setUploadSentCopy(id: String, enabled: Boolean) {",
                "store.setUploadSentCopy(id, enabled)",
                "refresh()",
                "}",
            ),
            block(ACCOUNTS_VIEW_MODEL, "fun setUploadSentCopy(", 4),
        )
    }

    // ── instrument ──────────────────────────────────────────────────────────────────────────────

    /** [count] consecutive code lines from the ONE line starting with [prefix]. */
    private fun block(file: File, prefix: String, count: Int): List<String> {
        val lines = codeLines(file)
        val at = only(lines, prefix, exact = false)
        return lines.subList(at, minOf(at + count, lines.size))
    }

    /** [count] code lines starting [before] lines above the ONE line equal to [anchor]. */
    private fun blockEndingAt(file: File, anchor: String, before: Int, count: Int): List<String> {
        val lines = codeLines(file)
        val at = only(lines, anchor)
        return lines.subList(maxOf(at - before, 0), minOf(at - before + count, lines.size))
    }

    /** The index of the single matching code line; fails loudly on none or several. */
    private fun only(lines: List<String>, needle: String, exact: Boolean = true): Int {
        val hits = lines.indices.filter { if (exact) lines[it] == needle else lines[it].startsWith(needle) }
        return hits.singleOrNull()
            ?: error(
                "${hits.size} code lines match `$needle` — this lint reads the shipped source and " +
                    "must be taught the new shape rather than left green over something it never read",
            )
    }

    /** [file]'s lines, trimmed, comment-only lines dropped so no rule is satisfied by prose. */
    private fun codeLines(file: File): List<String> = file.readLines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private companion object {
        private const val SETTINGS_SCREEN_PATH =
            "app/src/main/kotlin/app/sterna/ui/settings/SettingsScreen.kt"

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, SETTINGS_SCREEN_PATH).isFile }
                ?: error("cannot locate the repo root from ${File("").absolutePath}")
        }

        val SETTINGS_SCREEN: File by lazy { File(root, SETTINGS_SCREEN_PATH) }
        val ACCOUNTS_VIEW_MODEL: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/settings/AccountsViewModel.kt")
        }
    }
}
