package app.sterna.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — it reads two source files as text and proves nothing about
 */
class NotificationsGateWiringTest {

    // -- 1. the view model publishes "not read yet" -----------------------------------------------

    @Test fun `the five settings are read as one, and start out null`() {
        assertBlock(
            SETTINGS_VIEW_MODEL,
            listOf(
                "val notificationSettings: StateFlow<NotificationSettingsState?> = notificationSettingsState(",
                "deliveryMode = settings.deliveryMode,",
                "notificationContent = settings.notificationContent,",
                "quietHoursEnabled = settings.quietHoursEnabled,",
                "quietHoursStart = settings.quietHoursStart,",
                "quietHoursEnd = settings.quietHoursEnd,",
                ").stateIn(",
                "scope = viewModelScope,",
                "started = SharingStarted.WhileSubscribed(5_000),",
                "initialValue = null,",
                ")",
            ),
            "the notifications screen's state",
        )
    }

    /** The negative screen — a `contains`, deliberately: it looks for what must be absent. */
    @Test fun `none of the five is published on its own any more`() {
        val offenders = codeLines(SETTINGS_VIEW_MODEL).filter { line ->
            FIVE.any { line.startsWith("val $it ") || line.startsWith("val $it:") }
        }
        assertEquals(
            "each of these five used to be its own stateIn seeded with the repository's default, " +
                "which is the whole defect: a screen composed from five defaults, corrected a " +
                "frame later. One of them published separately is one the screen can read past " +
                "the gate. Found:\n" + offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    // -- 2. the screen waits, and fills the viewport while it waits ------------------------------

    @Test fun `the screen draws a spinner until the stored values are known`() {
        assertBlockIn(
            notificationsScreen(),
            listOf(
                "val pushAll by viewModel.pushAllAccounts.collectAsStateWithLifecycle()",
                "val notifSettings by viewModel.notificationSettings.collectAsStateWithLifecycle()",
                "val scroll = rememberScrollState()",
                "DetailScaffold(title = stringResource(R.string.settings_notifications_screen_title), onBack = onBack) { padding ->",
                "val state = notifSettings",
                "if (state == null) {",
                "Box(Modifier.fillMaxSize().padding(padding)) {",
                "LoadingRing(Modifier.align(Alignment.Center))",
                "}",
                "} else {",
                "Column(",
                "Modifier.fillMaxSize().padding(padding).verticalScroll(scroll),",
                ") {",
                "SettingsSection(stringResource(R.string.settings_delivery_section)) {",
            ),
            "the notifications screen's gate",
            SETTINGS_SCREEN,
        )
    }

    @Test fun `the scroll state is remembered whatever branch is taken`() {
        val body = notificationsScreen()
        assertEquals(
            "NotificationsScreen must call rememberScrollState() exactly once, on its own line, " +
                "as 'val scroll = rememberScrollState()'. Remembered state is keyed by position " +
                "in the composition; called inside one arm of the if, its key moves with the " +
                "branch the screen happens to open on.",
            listOf("val scroll = rememberScrollState()"),
            body.filter { "rememberScrollState()" in it },
        )
        val remembered = body.indexOfFirst { it == "val scroll = rememberScrollState()" }
        val branch = body.indexOfFirst { it == "if (state == null) {" }
        assertTrue(
            "and it must be called ABOVE the branch, not inside it (remembered at line " +
                "${remembered + 1} of the function, branch at ${branch + 1})",
            remembered in 0 until branch,
        )
    }

    /** The negative screen — a `contains`, deliberately. */
    @Test fun `the screen has no second way to read the five settings`() {
        val offenders = notificationsScreen().filter { line ->
            FIVE.any { "viewModel.$it" in line }
        }
        assertEquals(
            "every one of the five must be read through 'state.', behind the gate. A second read " +
                "straight off the view model is a value drawn before it is known. Found:\n" +
                offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    // -- 3. the rows the gate exists to protect, wired to the value they name ---------------------

    /**
     * The gate above is worth nothing if the row that says "Neither sender nor subject" writes
     */
    @Test fun `each notification-content row shows and writes its own value`() {
        assertBlockIn(
            notificationsScreen(),
            listOf(
                "SettingsSection(stringResource(R.string.settings_notif_content_section)) {",
                "DeliveryModeOption(",
                "title = stringResource(R.string.settings_notif_content_preview),",
                "subtitle = stringResource(R.string.settings_notif_content_preview_desc),",
                "selected = state.notificationContent == NotificationContent.BODY_PREVIEW,",
                "onClick = { viewModel.setNotificationContent(NotificationContent.BODY_PREVIEW) },",
                ")",
                "DeliveryModeOption(",
                "title = stringResource(R.string.settings_notif_content_full),",
                "subtitle = stringResource(R.string.settings_notif_content_full_desc),",
                "selected = state.notificationContent == NotificationContent.SENDER_AND_SUBJECT,",
                "onClick = { viewModel.setNotificationContent(NotificationContent.SENDER_AND_SUBJECT) },",
                ")",
                "DeliveryModeOption(",
                "title = stringResource(R.string.settings_notif_content_sender),",
                "subtitle = stringResource(R.string.settings_notif_content_sender_desc),",
                "selected = state.notificationContent == NotificationContent.SENDER_ONLY,",
                "onClick = { viewModel.setNotificationContent(NotificationContent.SENDER_ONLY) },",
                ")",
                "DeliveryModeOption(",
                "title = stringResource(R.string.settings_notif_content_none),",
                "subtitle = stringResource(R.string.settings_notif_content_none_desc),",
                "selected = state.notificationContent == NotificationContent.NONE,",
                "onClick = { viewModel.setNotificationContent(NotificationContent.NONE) },",
                ")",
                "}",
            ),
            "the notification-content rows",
            SETTINGS_SCREEN,
        )
    }

    @Test fun `each delivery row shows and writes its own value`() {
        assertBlockIn(
            notificationsScreen(),
            listOf(
                "SettingsSection(stringResource(R.string.settings_delivery_section)) {",
                "DeliveryModeOption(",
                "title = stringResource(R.string.settings_delivery_instant),",
                "subtitle = stringResource(R.string.settings_delivery_instant_desc),",
                "selected = state.deliveryMode == DeliveryMode.INSTANT,",
                "onClick = { viewModel.setDeliveryMode(DeliveryMode.INSTANT) },",
                ")",
                "DeliveryModeOption(",
                "title = stringResource(R.string.settings_delivery_saver),",
                "subtitle = stringResource(R.string.settings_delivery_saver_desc),",
                "selected = state.deliveryMode == DeliveryMode.BATTERY_SAVER,",
                "onClick = { viewModel.setDeliveryMode(DeliveryMode.BATTERY_SAVER) },",
                ")",
                "}",
            ),
            "the delivery rows",
            SETTINGS_SCREEN,
        )
    }

    // -- 4. quiet hours, start and end, not crossed ----------------------------------------------

    @Test fun `the two time pickers keep their own end of the night`() {
        assertBlockIn(
            notificationsScreen(),
            listOf(
                "TimePickerRow(",
                "label = stringResource(R.string.settings_quiet_hours_start),",
                "minutes = state.quietHoursStart,",
                "onChange = viewModel::setQuietHoursStart,",
                ")",
                "TimePickerRow(",
                "label = stringResource(R.string.settings_quiet_hours_end),",
                "minutes = state.quietHoursEnd,",
                "onChange = viewModel::setQuietHoursEnd,",
                ")",
            ),
            "the quiet-hours pickers",
            SETTINGS_SCREEN,
        )
    }

    // -- reading the sources ---------------------------------------------------------------------

    /** The body of `private fun NotificationsScreen(...)`, closed by counting braces. */
    private fun notificationsScreen(): List<String> {
        val lines = codeLines(SETTINGS_SCREEN)
        val opener = "private fun NotificationsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {"
        val at = lines.indexOfFirst { it == opener }
        check(at >= 0) {
            "no '$opener' in SettingsScreen.kt — the screen moved or was reshaped, and this lint " +
                "must be taught the new shape rather than left green over a function it never read"
        }
        var depth = 1
        val body = mutableListOf<String>()
        for (line in lines.drop(at + 1)) {
            depth += line.count { it == '{' } - line.count { it == '}' }
            if (depth <= 0) return body
            body += line
        }
        error("NotificationsScreen is never closed in SettingsScreen.kt")
    }

    private fun assertBlock(file: File, expected: List<String>, what: String) =
        assertBlockIn(codeLines(file), expected, what, file)

    /** Locates [expected]'s first line in [lines] and compares the block that follows it, whole. */
    private fun assertBlockIn(lines: List<String>, expected: List<String>, what: String, file: File) {
        val at = lines.indexOfFirst { it == expected.first() }
        check(at >= 0) { "no '${expected.first()}' in ${file.name} — $what is gone" }
        val found = lines.subList(at, minOf(at + expected.size, lines.size))
        val mismatches = expected.indices.mapNotNull { i ->
            val actual = found.getOrNull(i)
            if (actual == expected[i]) null
            else "line ${i + 1} of $what: expected '${expected[i]}' but found '$actual'"
        }
        assertEquals(
            "$what is pinned WHOLE, line by line and in order. Nothing in this repo executes " +
                "these lines. Mismatches:\n" + mismatches.joinToString("\n"),
            emptyList<String>(),
            mismatches,
        )
    }

    /** [file]'s lines, trimmed, comment-only lines dropped so no rule can be satisfied by prose. */
    private fun codeLines(file: File): List<String> = file.readLines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private companion object {
        val FIVE = listOf(
            "deliveryMode",
            "notificationContent",
            "quietHoursEnabled",
            "quietHoursStart",
            "quietHoursEnd",
        )

        private const val SETTINGS_VIEW_MODEL_PATH =
            "app/src/main/kotlin/app/sterna/ui/settings/SettingsViewModel.kt"

        /** Repo root, walked up from the module's working directory. */
        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, SETTINGS_VIEW_MODEL_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this lint reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        val SETTINGS_VIEW_MODEL: File by lazy { File(root, SETTINGS_VIEW_MODEL_PATH) }
        val SETTINGS_SCREEN: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/settings/SettingsScreen.kt")
        }
    }
}
