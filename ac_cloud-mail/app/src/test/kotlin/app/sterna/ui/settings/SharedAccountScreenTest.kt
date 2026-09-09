package app.sterna.ui.settings

import app.sterna.core.data.account.StoredAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The delegated-account settings screen (issue #31): the two pure rules that decide WHICH account
 */
class SharedAccountScreenTest {

    private fun login() = StoredAccount(
        id = "login-uuid",
        server = "https://mail.example.org",
        username = "alex.rivera@example.org",
        jmapAccountId = "s",
    )

    private fun delegated(id: String = "jordan-uuid", name: String = "jordan.lee@example.org") =
        StoredAccount(
            id = id,
            server = "https://mail.example.org",
            // A sub-account carries the LOGIN's address as username; its own is the account name.
            username = "alex.rivera@example.org",
            accountName = name,
            loginId = "login-uuid",
            jmapAccountId = "u",
        )

    @Test fun `a delegated account resolves to itself`() {
        val accounts = listOf(login(), delegated())

        assertSame(accounts[1], sharedAccountFor("jordan-uuid", accounts))
    }

    @Test fun `a login resolves to nothing, so this screen can never write on its row`() {
        // The guard of the whole batch: the two writes below are made with the id this route was
        // opened with. Were a login to resolve here, its colour and its notifications would be set
        // from a screen that says "Shared account", and the drawer's shortcut would have a target
        // it must not have.
        assertNull(sharedAccountFor("login-uuid", listOf(login(), delegated())))
    }

    @Test fun `an unknown id resolves to nothing`() {
        // A revoked share is removed on the next discovery pass while the screen is still open.
        assertNull(sharedAccountFor("gone-uuid", listOf(login(), delegated())))
    }

    /**
     * THE GUARD OF THE BATCH, read as whole statements. The tests above execute [sharedAccountFor];
     */
    @Test fun `the screen resolves its account through the shared-account guard`() {
        assertEquals(
            listOf(
                "val accounts by viewModel.accounts.collectAsStateWithLifecycle()",
                "val account = sharedAccountFor(accountId, accounts)",
                "if (account == null) {",
            ),
            statementsAfter(SCREEN, GUARD_MARKER, 3),
        )
    }

    /**
     * What each row OPENS on: the value the account already holds, never a constant. A switch
     */
    @Test fun `both rows open on the value the account already holds`() {
        assertEquals(
            listOf(
                "var colorArgb by remember(accountId) { mutableStateOf(account.color) }",
                "var notificationsEnabled by remember(accountId) " +
                    "{ mutableStateOf(account.notificationsEnabled) }",
            ),
            statementsAfter(SCREEN, INITIAL_STATE_MARKER, 2),
        )
    }

    /**
     * Read in ABSENCE over the SCREEN'S BODY — the composable's signature to the end of the file.
     */
    @Test fun `the screen holds none of the writes that belong to a login`() {
        val found = FORBIDDEN.filter { it in screenBody() }

        assertTrue(
            "SharedAccountScreen's body must call none of these, found: $found",
            found.isEmpty(),
        )
    }

    /**
     * The colour section, statement by statement — whole lines, never a substring: a `contains` is
     */
    @Test fun `the colour swatches write the shared account's own id`() {
        assertEquals(
            listOf(
                "Row(",
                "modifier = Modifier",
                ".fillMaxWidth()",
                ".horizontalScroll(rememberScrollState())",
                ".padding(horizontal = 16.dp, vertical = 8.dp),",
                "horizontalArrangement = Arrangement.spacedBy(12.dp),",
                ") {",
                "ColourSwatch(color = null, selected = colorArgb == null) {",
                "colorArgb = null; viewModel.setColor(accountId, null); onAccountsChanged()",
                "}",
                "AccountPalette.colors.forEach { swatch ->",
                "val argb = swatch.toArgb()",
                "ColourSwatch(color = swatch, selected = colorArgb == argb) {",
                "colorArgb = argb; viewModel.setColor(accountId, argb); onAccountsChanged()",
            ),
            statementsAfter(SCREEN, COLOUR_MARKER, 14),
        )
    }

    /**
     * The notifications section, same reading. Pins the toggle's write AND the whole condition of
     */
    @Test fun `the notifications toggle writes the shared account's own id and asks for all three`() {
        assertEquals(
            listOf(
                "SettingSwitch(",
                "title = stringResource(R.string.settings_account_notifications_title),",
                "subtitle = stringResource(R.string.settings_account_notifications_subtitle),",
                "checked = notificationsEnabled,",
                "onCheckedChange = {",
                "notificationsEnabled = it",
                "viewModel.setNotificationsEnabled(accountId, it)",
                "},",
                ")",
                "if (PushController.shouldShowUnwatchedNote(account.isLinked, " +
                    "viewModel.isWatched(accountId), notificationsEnabled)) {",
            ),
            statementsAfter(SCREEN, NOTIFICATIONS_MARKER, 10),
        )
    }

    // ---- the shortcut into the settings graph (volet 2) ----

    @Test fun `a delegated account opens the short shared-mailbox screen`() {
        assertEquals("sharedAccount/jordan-uuid", settingsRouteFor("jordan-uuid", listOf(login(), delegated())))
    }

    @Test fun `a login opens the full account screen`() {
        assertEquals("account/login-uuid", settingsRouteFor("login-uuid", listOf(login(), delegated())))
    }

    @Test fun `a delegated account whose login is gone keeps its own screen`() {
        // isShared only asks for a non-blank loginId, so an orphaned share is still a share. It
        // must NOT fall back on the login editor: that screen writes a credential under
        // [StoredAccount.loginKey], i.e. under an id that names nothing here.
        val orphan = delegated().copy(loginId = "gone-uuid")

        assertEquals("sharedAccount/jordan-uuid", settingsRouteFor("jordan-uuid", listOf(orphan)))
    }

    @Test fun `an id the list does not hold still opens something`() {
        // A dead shortcut is worse than one that opens the account screen and finds it empty.
        val route = settingsRouteFor("gone-uuid", listOf(login(), delegated()))

        assertEquals("account/gone-uuid", route)
    }

    /**
     * SOURCE LINT over the TWO entries into the settings graph, read as whole statements.
     */
    @Test fun `both entries into the settings graph ask settingsRouteFor`() {
        assertEquals(
            listOf(
                "val startDestination = remember(initialAccountId) {",
                "if (initialAccountId != null) {",
                "settingsRouteFor(initialAccountId, accountsViewModel.accounts.value)",
                "} else {",
                "\"hub\"",
                "}",
                "}",
            ),
            statementsAfter(SETTINGS, START_DESTINATION_MARKER, 7),
        )
        // The route is COMPUTED above and only handed over here: what the NavHost receives must be
        // that frozen value, not a fresh expression re-evaluated on every recomposition (the graph
        // is keyed on it, and a share revoked while settings are open would rebuild it in place).
        assertEquals(
            listOf("startDestination = startDestination,"),
            statementsAfter(SETTINGS, NAV_HOST_MARKER, 1),
        )
        assertEquals(
            listOf(
                "viewModel = accountsViewModel,",
                "onBack = { entry.navigateOnce { nav.popBackStack() } },",
                "onOpenAccount = { id -> entry.navigateOnce " +
                    "{ nav.navigate(settingsRouteFor(id, accountsViewModel.accounts.value)) } },",
            ),
            statementsAfter(SETTINGS, ACCOUNTS_SCREEN_MARKER, 3),
        )
    }

    /**
     * SOURCE LINT tying each ROUTE STRING to the composable that serves it, and to the argument it
     */
    @Test fun `the shared route opens the shared screen with the id it carries`() {
        assertEquals(
            listOf(
                "composable(\"sharedAccount/{id}\") { entry ->",
                "val id = entry.arguments?.getString(\"id\").orEmpty()",
                "SharedAccountScreen(",
                "accountId = id,",
                "viewModel = accountsViewModel,",
            ),
            statementsAfter(SETTINGS, SHARED_ROUTE_MARKER, 5),
        )
    }

    /**
     * The BELT on the other route, pinned in BOTH branches (same reading, same limits as above):
     */
    @Test fun `the account route keeps a shared mailbox off the login editor`() {
        assertEquals(
            listOf(
                "composable(\"account/{id}\") { entry ->",
                "val id = entry.arguments?.getString(\"id\").orEmpty()",
                "val accounts by accountsViewModel.accounts.collectAsStateWithLifecycle()",
                "if (sharedAccountFor(id, accounts) != null) {",
                "SharedAccountScreen(",
                "accountId = id,",
                "viewModel = accountsViewModel,",
                "onBack = { entry.navigateOnce { if (!nav.popBackStack()) onBack() } },",
                "onAccountsChanged = onAccountsChanged,",
                ")",
                "} else {",
                "AccountDetailScreen(",
                "accountId = id,",
                "viewModel = accountsViewModel,",
            ),
            statementsAfter(SETTINGS, ACCOUNT_ROUTE_MARKER, 14),
        )
    }

    /**
     * The drawer's account chip, same reading: it hands over the id of the account being LOOKED
     * AT, never a login's. Same limits as the lint above — this is text, not behaviour.
     */
    @Test fun `the drawer chip opens the account on screen, shared or not`() {
        assertEquals(
            listOf(
                ".clickable {",
                "onOpenAccountSettings(currentAccountId)",
                "scope.launch { drawerState.close() }",
                "},",
            ),
            statementsAfter(INBOX, ACCOUNT_CHIP_MARKER, 4),
        )
    }

    // ---- the accounts list (volet 3) ----

    /**
     * SOURCE LINT over the accounts list, read as whole statements: the list builds its rows with
     */
    @Test fun `the accounts list draws the rows the ordering rule gives it`() {
        assertEquals(
            listOf(
                "items(StoredAccount.accountsScreenRows(accounts), key = { it.account.id }) { row ->",
                "val account = row.account",
                "AccountRow(",
                "seed = if (account.isShared) account.label() else account.username,",
                "label = account.label(),",
                "email = if (account.isShared) stringResource(R.string.account_shared) " +
                    "else account.username,",
                "isCurrent = account.id == currentId,",
                "color = accountColorOf(account.color),",
                "indented = row.underLogin,",
            ),
            statementsAfter(SETTINGS, ACCOUNTS_LIST_MARKER, 9),
        )
    }

    /**
     * SOURCE LINT over the INDENT ITSELF, in [AccountRow]'s own file — the other half of the lint
     */
    @Test fun `a shared mailbox's row is drawn further from the left edge than its login`() {
        assertEquals(
            listOf(
                ".padding(",
                "start = if (indented) 32.dp else 16.dp,",
            ),
            statementsAfter(COMPONENTS, INDENT_MARKER, 2),
        )
    }

    /**
     * The first [count] statements after [marker]: trimmed lines, comments and blanks dropped.
     */
    private fun statementsAfter(file: String, marker: String, count: Int): List<String> {
        val lines = source(file).lines()
        val hits = lines.withIndex().filter { it.value.trim() == marker }.map { it.index }
        assertEquals("expected exactly one '$marker' in $file, found $hits", 1, hits.size)
        return lines.drop(hits.single() + 1)
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
            .take(count)
    }

    /**
     * The composable's own text: from its signature to the end of the file, so the absence lint
     */
    private fun screenBody(): String {
        val lines = source(SCREEN).lines()
        val hits = lines.withIndex().filter { it.value.trim() == SCREEN_SIGNATURE }.map { it.index }
        assertEquals("expected exactly one '$SCREEN_SIGNATURE' in $SCREEN, found $hits", 1, hits.size)
        return lines.drop(hits.single()).joinToString("\n")
    }

    private fun source(relative: String): String = File(repoRoot, relative).readText()

    private companion object {
        const val SCREEN = "app/src/main/kotlin/app/sterna/ui/settings/SharedAccountScreen.kt"
        const val SETTINGS = "app/src/main/kotlin/app/sterna/ui/settings/SettingsScreen.kt"
        const val INBOX = "app/src/main/kotlin/app/sterna/ui/inbox/InboxScreen.kt"
        const val COMPONENTS = "app/src/main/kotlin/app/sterna/ui/settings/SettingsComponents.kt"

        /** The composable the absence lint reads, and nothing above it. */
        const val SCREEN_SIGNATURE = "internal fun SharedAccountScreen("

        const val GUARD_MARKER =
            "// PINNED BY SharedAccountScreenTest \u2014 the guard, statement by statement"
        const val INITIAL_STATE_MARKER =
            "// PINNED BY SharedAccountScreenTest \u2014 what each row opens on"

        const val COLOUR_MARKER =
            "SettingsSection(stringResource(R.string.settings_account_colour_section)) {"
        const val NOTIFICATIONS_MARKER =
            "SettingsSection(stringResource(R.string.settings_account_notifications_section)) {"

        const val INDENT_MARKER =
            "// PINNED BY SharedAccountScreenTest \u2014 the indent, statement by statement"

        const val ACCOUNTS_LIST_MARKER =
            "// PINNED BY SharedAccountScreenTest \u2014 the accounts list, statement by statement"

        /** The line the NavHost's own arguments start after — the first entry is right below it. */
        const val NAV_HOST_MARKER = "navController = nav,"

        const val START_DESTINATION_MARKER =
            "// PINNED BY SharedAccountScreenTest \u2014 the start destination, statement by statement"
        const val ACCOUNT_ROUTE_MARKER =
            "// PINNED BY SharedAccountScreenTest \u2014 the account route, statement by statement"
        const val SHARED_ROUTE_MARKER =
            "// PINNED BY SharedAccountScreenTest \u2014 the shared route, statement by statement"
        const val ACCOUNTS_SCREEN_MARKER = "AccountsScreen("
        const val ACCOUNT_CHIP_MARKER = ".graphicsLayer { translationX = accountOffset.value }"

        /**
         * Doors a SCREEN can open, and what the user loses through each one — half a line apiece.
         */
        val FORBIDDEN = listOf(
            // Writes server, credential and identities under the LOGIN, for every mailbox grouped there.
            "viewModel.save(",
            // Drops this row: the colour and the notification choice made here go with it (#31).
            "viewModel.signOut(",
            // Re-runs the device flow and rewrites the token the whole login group signs in with.
            "viewModel.startAccountOAuth(",
            // Flips this row to BASIC and clears its OAuth material: the mailbox stops connecting.
            "viewModel.switchAccountToAppPassword(",
            // Writes a secret, and a sub-account's secret is the login's (`loginKey()`).
            "updatePassword",
            // The Save button's enablement: its presence means this screen grew a Save button.
            "canSaveAccount",
            // The unsaved-changes exit: nothing is pending here, so it would ask about nothing.
            "SaveChangesDialog",
            // The caption promising a save scope this screen does not have.
            "settings_save_scope",
            // The one shape by which the isShared guard can be short-circuited onto a login's row.
            "accounts.firstOrNull",
        )

        val repoRoot: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "app/src/main/res/values/strings.xml").isFile }
                ?: error("cannot locate the checkout from ${File("").absolutePath}")
        }
    }
}
