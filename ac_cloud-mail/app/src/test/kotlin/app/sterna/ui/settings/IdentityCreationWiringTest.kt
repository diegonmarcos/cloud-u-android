package app.sterna.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and same disclaimer as
 */
class IdentityCreationWiringTest {

    @Test fun `save reads the account BEFORE it writes anything`() {
        assertEquals(
            "AccountsViewModel.save no longer opens by reading the account as it was. That read is " +
                "the whole of #172: `identitiesToCreate` compares what the account HELD with what " +
                "this Save is writing, so taken after `store.setIdentities` it compares the new " +
                "list with itself, finds nothing added, and no identity is ever created on the " +
                "server again — a silent return to the shipped defect. The read is pinned to the " +
                "opening brace of save(): it must stay its FIRST statement, and the blank-page " +
                "reset of the refusal list must stay just under it.",
            listOf(
                "smtpSecurity: ConnectionSecurity? = null,",
                ") {",
                "val before = store.account(id)",
                "_identitiesNotCreated.value = emptyList()",
                "store.updateAccount(",
            ),
            block(ACCOUNTS_VIEW_MODEL, "smtpSecurity: ConnectionSecurity? = null,", 5),
        )
        val lines = codeLines(ACCOUNTS_VIEW_MODEL)
        val read = only(lines, "val before = store.account(id)")
        val writeIdentities = only(lines, "if (identities != null) store.setIdentities(id, identities)")
        assertTrue(
            "the account is read at line $read, AFTER the identities are written at line " +
                "$writeIdentities: `before` now holds the list this gesture just stored, so every " +
                "identity counts as already known and none is sent to the server.",
            read < writeIdentities,
        )
    }

    @Test fun `save ends by asking the server for the identities it just added`() {
        assertEquals(
            "the tail of AccountsViewModel.save changed shape. It must call " +
                "createIdentitiesOnServer with the account AS IT WAS (`before`) and the list being " +
                "saved, after `refresh()` — drop that line and an alias added here lives on the " +
                "phone alone, and the server refuses every send from it (#172).",
            listOf(
                "refresh()",
                "createIdentitiesOnServer(id, before, identities)",
                "}",
            ),
            blockEndingAt(ACCOUNTS_VIEW_MODEL, "createIdentitiesOnServer(id, before, identities)", 1, 3),
        )
    }

    @Test fun `nothing is asked of the network unless this Save could create something`() {
        assertEquals(
            "AccountsViewModel.createIdentitiesOnServer no longer opens with the OFFLINE " +
                "pre-filter. `mayCreateIdentities` answers, with no request at all, whether this " +
                "Save could create anything if the server held nothing — the super-set of the " +
                "candidates, so it cannot miss a case. Without it, every Save would open a JMAP " +
                "session just to read the identity list, including on IMAP accounts, where this " +
                "whole path must stay inert. And it must come BEFORE `store.credentials` and " +
                "before `appScope.launch`: past that line a Save that adds nothing has already " +
                "paid for a session.",
            listOf(
                "private fun createIdentitiesOnServer(",
                "id: String,",
                "before: StoredAccount?,",
                "identities: List<StoredIdentity>?,",
                ") {",
                "if (identities == null || before == null) return",
                "if (!mayCreateIdentities(before, identities)) return",
                "val credentials = store.credentials(id) ?: return",
                "appScope.launch {",
            ),
            block(ACCOUNTS_VIEW_MODEL, "private fun createIdentitiesOnServer(", 9),
        )
        assertEquals(
            "the identity creation must run on the APP scope. `viewModelScope` here belongs to the " +
                "NavBackStackEntry of the settings destination and dies with the back gesture, " +
                "halfway through the JMAP round trip, before the server has answered.",
            listOf("private val appScope = application.container.appScope"),
            block(ACCOUNTS_VIEW_MODEL, "private val appScope =", 1),
        )
    }

    @Test fun `the server's list is READ, and it is that list the decision is made on`() {
        assertEquals(
            "AccountsViewModel.createIdentitiesOnServer must READ the server's identities before " +
                "deciding, and hand THAT list to identitiesToCreate. This is V5 whole: " +
                "`StoredAccount.serverIdentities` is only ever written by `MailRepository.connect`, " +
                "which neither adding an account, nor Test connection, nor a sync runs — so on a " +
                "freshly added account the stored list is empty because nobody read it, and the " +
                "guard that (rightly) refuses to post against an unread list refused for good. " +
                "Passing the stored field, or emptyList(), or dropping the third argument, puts " +
                "the measured defect back: 'Saved' on screen, the row written locally, and not one " +
                "Identity/set on the wire. A failed read must stay a THROW that lands in the catch " +
                "below, never a runCatching that answers an empty list — 'unreadable' and 'the " +
                "server holds nothing' are opposite instructions.",
            listOf(
                "val server = try {",
                "mail.serverIdentities(credentials)",
                "} catch (e: Exception) {",
                "android.util.Log.w(",
                "\"SternaAccounts\",",
                "\"could not read the identities the server already holds; nothing was created\",",
                "e,",
                ")",
                "for (identity in identitiesToCreate(before, identities, emptyList())) {",
                "_identitiesNotCreated.update {",
                "it + IdentityNotCreated(id, identity.email.trim(), failureDetail(e.message).ifEmpty { e.javaClass.simpleName })",
                "}",
                "}",
                "return@launch",
                "}",
            ),
            block(ACCOUNTS_VIEW_MODEL, "val server = try {", 15),
        )
        assertEquals(
            "the loop over what must be created changed shape. Each line is load bearing: the " +
                "THIRD argument is the list just read from the server (drop it and the decision " +
                "goes back to the stored field, which is empty on exactly the accounts this fixes); " +
                "the `try` sits INSIDE the `for`, or one refused address stops every address " +
                "behind it; `name =` / `email =` are named on the createIdentity call, both being " +
                "String, so swapping them compiles and creates every identity with the display " +
                "name in the address field.",
            listOf(
                "for (identity in identitiesToCreate(before, identities, server)) {",
                "try {",
                "mail.createIdentity(credentials, name = identity.name, email = identity.email.trim())",
                "} catch (e: Exception) {",
            ),
            block(ACCOUNTS_VIEW_MODEL, "for (identity in identitiesToCreate(before, identities, server))", 4),
        )
    }

    @Test fun `a refusal becomes state the screen can show, not just a log line`() {
        assertEquals(
            "the catch around the create in AccountsViewModel.createIdentitiesOnServer no longer " +
                "feeds the failure state. Logging alone is what V3 exists to end: there is NO " +
                "retry (the row is already stored, so the next Save filters it out), so a refusal " +
                "the user is not told about leaves an address that will never reach the server and " +
                "no sign of it. The entry must carry the refused ADDRESS — one Save can add " +
                "several identities and a partly refused batch has to say which one — and the " +
                "server's own sentence. The WHOLE catch is pinned, braces included: pinning only " +
                "the report lets a condition be wrapped around it (`if (e is JmapException)`), " +
                "which would leave offline, dead DNS, broken TLS and timeouts mute — the very " +
                "cases with no retry behind them.",
            listOf(
                "mail.createIdentity(credentials, name = identity.name, email = identity.email.trim())",
                "} catch (e: Exception) {",
                "android.util.Log.w(",
                "\"SternaAccounts\",",
                "\"the server did not create the identity; it stays local and is not retried\",",
                "e,",
                ")",
                "_identitiesNotCreated.update {",
                "it + IdentityNotCreated(id, identity.email.trim(), failureDetail(e.message).ifEmpty { e.javaClass.simpleName })",
                "}",
                "}",
            ),
            blockEndingAt(
                ACCOUNTS_VIEW_MODEL,
                "mail.createIdentity(credentials, name = identity.name, email = identity.email.trim())",
                0,
                11,
            ),
        )
        assertEquals(
            "the failure list must stay a read-only StateFlow the screen collects; exposing the " +
                "MutableStateFlow, or dropping the address from the entry, takes away the only " +
                "thing that says WHICH address the server refused. And it must keep the " +
                "accountId: ONE AccountsViewModel serves every account detail screen, so an " +
                "unkeyed list draws account A's refusal on account B's screen, naming an address " +
                "that appears nowhere on it.",
            listOf(
                "data class IdentityNotCreated(val accountId: String, val email: String, val detail: String)",
                "private val _identitiesNotCreated = MutableStateFlow<List<IdentityNotCreated>>(emptyList())",
                "val identitiesNotCreated: StateFlow<List<IdentityNotCreated>> = _identitiesNotCreated.asStateFlow()",
            ),
            block(ACCOUNTS_VIEW_MODEL, "data class IdentityNotCreated(", 3),
        )
    }

    @Test fun `BOTH refusal reports trim the sentence before the screen adds its full stop`() {
        val lines = codeLines(ACCOUNTS_VIEW_MODEL)
        assertEquals(
            "the two places that report an address the server did not take must hand the screen a " +
                "detail with its final punctuation removed. `settings_identity_not_created` ends " +
                "'…on the server: %2\$s.' — the full stop is in the template, in nine languages — " +
                "and a server sentence ('E-mail address not configured for this account.') brings " +
                "its own, which is the double stop measured on the S7 on 2026-08-31. Passing " +
                "`e.message` raw at either site puts it back, on the READ failure (offline) or on " +
                "the refusal. The `.ifEmpty` fallback is pinned with it: a message that was " +
                "nothing BUT punctuation trims to empty, and without the fallback the screen " +
                "writes 'on the server: .' naming nothing at all. What the trimming does, and " +
                "what it must leave alone, is executed in IdentityFailureDetailTest.",
            listOf(
                "it + IdentityNotCreated(id, identity.email.trim(), failureDetail(e.message).ifEmpty { e.javaClass.simpleName })",
                "it + IdentityNotCreated(id, identity.email.trim(), failureDetail(e.message).ifEmpty { e.javaClass.simpleName })",
            ),
            lines.filter { it.startsWith("it + IdentityNotCreated(") },
        )
    }

    @Test fun `the refusal is shown just above the Save button, for THIS account`() {
        assertEquals(
            "the refusal row on the account detail screen changed shape. It must name the address " +
                "and repeat what the server answered, in the error colour — a refusal the user " +
                "cannot read is the shipped defect with a log line added — and it must be FILTERED " +
                "on the screen's own accountId: one view model serves every account screen, so an " +
                "unfiltered list shows account A's refusal on account B, naming an address that " +
                "appears nowhere on it.",
            listOf(
                "identitiesNotCreated.filter { it.accountId == accountId }.forEach { failure ->",
                "Text(",
                "stringResource(R.string.settings_identity_not_created, failure.email, failure.detail),",
                "style = MaterialTheme.typography.bodySmall,",
                "color = MaterialTheme.colorScheme.error,",
                "modifier = Modifier.padding(vertical = 4.dp),",
                ")",
                "}",
            ),
            block(SETTINGS_SCREEN, "identitiesNotCreated.filter {", 8),
        )
        assertEquals(
            "the failure list must be COLLECTED, not read once. `= viewModel.identitiesNotCreated" +
                ".value` compiles and the screen then shows a refusal only if something else " +
                "happens to recompose it — the request is in flight when Save returns, so at that " +
                "instant the list is still empty.",
            listOf("val identitiesNotCreated by viewModel.identitiesNotCreated.collectAsStateWithLifecycle()"),
            block(SETTINGS_SCREEN, "val identitiesNotCreated by", 1),
        )
        val lines = codeLines(SETTINGS_SCREEN)
        val row = only(lines, "identitiesNotCreated.filter { it.accountId == accountId }.forEach { failure ->")
        val saveButton = only(lines, "enabled = canSave && dirty,")
        val unsaved = only(lines, "stringResource(R.string.settings_unsaved_changes),")
        assertTrue(
            "the refusal is drawn at line $row, BELOW the Save button (line $saveButton). The " +
                "gesture that asks the server is Save, and that button flips to 'Saved' the " +
                "instant it is pressed: the answer has to be where she is already looking. Under " +
                "the 'Add an identity' button it sat three sections higher, off screen — a " +
                "message nobody scrolls back to is not a message.",
            row < saveButton,
        )
        assertTrue(
            "the refusal is drawn at line $row, outside the Save button's own block (which opens " +
                "around the 'Unsaved changes' cue at line $unsaved): it belongs with the two " +
                "other things this screen says about the last Save, not in a section of its own.",
            row > unsaved,
        )
    }

    // ── instrument (copied from [ShowOnlySubscribedFoldersWiringTest]) ───────────────────────────

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
