package app.sterna.widget

import app.sterna.widget.DeclarationSource.file
import app.sterna.widget.DeclarationSource.functionBody
import app.sterna.widget.DeclarationSource.kotlinLines
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and the same disclaimer as
 */
class RecentMailWidgetWiringLintTest {

    /**
     * The four facts that decide what a row may say, what it wears and which mailbox it came from,
     */
    @Test fun `the widget read fetches the settings, the lock and the accounts, and hands them to the rule`() {
        assertEquals(
            "RecentMailWidgetDraw.read must read the notification-content setting, the " +
                "sender-initials switch, the app lock and the account list from the app's own " +
                "stores and pass all four into " +
                "RecentMailWidgetContent. A constant in either of the first two silently " +
                "ungoverns the home screen: the reader has set a privacy setting, or switched the " +
                "lock on, and the cell keeps naming senders. A constant map in the third leaves " +
                "an interleaved list with no dot at all, and a SECOND read of the account list " +
                "lets the colours and the rows come from two different moments. ⛔ And " +
                "`listMonogram = true` written here is the initials switch (#144) ungoverning " +
                "the home screen: the reader took the initials off her message list and gets " +
                "them back on her launcher, with no switch anywhere that turns them off.",
            listOf(
                "suspend fun read(app: Application): RecentWidgetDrawing {",
                "val container = app.container",
                "val content = container.settingsRepository.notificationContent.first()",
                "val listMonogram = container.settingsRepository.listMonogram.first()",
                "val appLockEnabled = container.accountStore.appLockEnabled()",
                "val accounts = container.accountStore.accounts()",
                "val inbox = container.mailRepository.recentUnifiedInbox(ROW_LIMIT)",
                "val snapshot = RecentMailWidgetContent.snapshot(",
                "inbox = inbox,",
                "content = content,",
                "appLockEnabled = appLockEnabled,",
                "listMonogram = listMonogram,",
                "unknownSender = app.getString(R.string.message_unknown_sender),",
                "noSubject = app.getString(R.string.message_no_subject),",
                "generic = app.getString(R.string.widget_latest_hidden),",
                "accountColors = RecentMailWidgetContent.accountColors(accounts),",
                ")",
                "val colours = WidgetThemeReader.colours(app)",
                "val drawing = RecentWidgetDrawing(snapshot, colours)",
                "held = drawing",
                "return drawing",
            ),
            functionBody(DRAW, "suspend fun read(app: Application)"),
        )
    }

    /**
     * The line the cell shows INSTEAD of a list comes from the rule that knows the three states
     */
    @Test fun `the cell's notice comes from the rule, and is never blank`() {
        assertEquals(
            "RecentMailWidgetDraw.draw must take its notice from RecentMailWidgetContent.notice " +
                "and fall back to the app's own name. A literal here re-decides the three cell " +
                "states in a place no test can execute, and an empty fallback puts a blank " +
                "rectangle on the home screen whenever the row list is empty.",
            listOf(NOTICE),
            kotlinLines(DRAW).filter { it.startsWith("val notice =") },
        )
    }

    /**
     * THE SECOND WIDGET MUST NOT TOUCH THE FIRST ONE'S PRESENCE, and the whole bodies are pinned
     */
    @Test fun `this widget's presence callbacks write ITS OWN state, not the counter's`() {
        assertEquals(
            "RecentMailWidgetProvider.onEnabled/onDisabled must write RecentMailWidgetPresence. " +
                "UnreadWidgetPresence here switches off the COUNTER's push when the last " +
                "latest-messages cell goes, and nothing on either cell would say why.",
            listOf(
                listOf(
                    "override fun onEnabled(context: Context) {",
                    "RecentMailWidgetPresence.set(true)",
                ),
                listOf(
                    "override fun onDisabled(context: Context) {",
                    "RecentMailWidgetPresence.set(false)",
                ),
            ),
            listOf(
                functionBody(PROVIDER, "override fun onEnabled(context: Context)"),
                functionBody(PROVIDER, "override fun onDisabled(context: Context)"),
            ),
        )
    }

    /**
     * TURNING THE LOCK ON MUST REACH THE HOME SCREEN, and so must tightening the content
     */
    @Test fun `switching the lock on, and tightening the content setting, redraw the cell at once`() {
        assertEquals(
            "SettingsViewModel must drop the held widget snapshot and redraw the latest-messages " +
                "widget when the app lock is switched on and when the notification-content " +
                "setting changes, and redraw the COUNTER on the lock as well. Without it the home " +
                "screen keeps showing senders, subjects and account addresses the reader has just " +
                "hidden, until mail happens to arrive.",
            listOf(
                listOf(
                    "fun setAppLock(value: Boolean) {",
                    "if (value && !canAuthenticate(getApplication())) {",
                    "_appLockUnavailable.value = true",
                    "_appLock.value = false",
                    "return",
                    "}",
                    "_appLockUnavailable.value = false",
                    "appLock.setEnabled(value)",
                    "_appLock.value = value",
                    REDRAW_LAUNCH,
                    FORGET,
                    REFRESH,
                    COUNTER_REFRESH,
                    "}",
                ),
                listOf(
                    "fun setNotificationContent(mode: NotificationContent) {",
                    "viewModelScope.launch {",
                    "settings.setNotificationContent(mode)",
                    FORGET,
                    REFRESH,
                    "}",
                ),
            ),
            listOf(
                functionBody(SETTINGS_VIEW_MODEL, "fun setAppLock(value: Boolean)"),
                functionBody(SETTINGS_VIEW_MODEL, "fun setNotificationContent(mode: NotificationContent)"),
            ),
        )
    }

    /**
     * AND SO MUST TAKING THE SENDER INITIALS OFF — the fourth setter that reaches this cell, and
     */
    @Test fun `switching the sender initials off redraws the cell, and wakes only the cell that draws them`() {
        assertEquals(
            "SettingsViewModel.setListMonogram must write the setting, await it, drop the held " +
                "widget snapshot and redraw the latest-messages widget, in that order. Without " +
                "it the reader takes the sender initials off, returns to her home screen, and " +
                "every row keeps its badge until mail happens to arrive — days on a quiet " +
                "mailbox, with no switch on that screen and no way to force it. ⛔ And no " +
                "UnreadWidgetDraw.refresh: the counter draws no badge, so waking it is a read of " +
                "the account store and of Room for a cell nothing changed.",
            listOf(
                "fun setListMonogram(enabled: Boolean) {",
                REDRAW_LAUNCH,
                "settings.setListMonogram(enabled)",
                FORGET,
                REFRESH,
                "}",
            ),
            functionBody(SETTINGS_VIEW_MODEL, "fun setListMonogram(enabled: Boolean)"),
        )
    }

    /**
     * RECOLOURING AN ACCOUNT MUST REACH THE HOME SCREEN TOO, and by the same two calls.
     */
    @Test fun `recolouring an account redraws the cell, so no row keeps a colour the app dropped`() {
        assertEquals(
            "AccountsViewModel.setColor must drop the held widget snapshot and redraw the " +
                "latest-messages widget, in that order. Without it the reader recolours an " +
                "account and the rows on her home screen keep the old dot until mail arrives — " +
                "days on a quiet mailbox — with nothing on the cell to explain it and no way to " +
                "force it. The arrival trigger does not cover this: UnifiedInbox.recentRows keys " +
                "on the inbox scopes, which a colour does not change. And with refresh() before " +
                "forget(), a slow read repaints the very colour that was replaced.",
            listOf(
                "fun setColor(id: String, color: Int?) {",
                "store.setColor(id, color)",
                "refresh()",
                REDRAW_LAUNCH,
                FORGET,
                REFRESH,
                "}",
            ),
            functionBody(ACCOUNTS_VIEW_MODEL, "fun setColor(id: String, color: Int?)"),
        )
    }

    private companion object {
        val DRAW = file("app/src/main/kotlin/app/sterna/widget/RecentMailWidgetDraw.kt")
        val PROVIDER = file("app/src/main/kotlin/app/sterna/widget/RecentMailWidgetProvider.kt")
        val SETTINGS_VIEW_MODEL = file("app/src/main/kotlin/app/sterna/ui/settings/SettingsViewModel.kt")
        val ACCOUNTS_VIEW_MODEL = file("app/src/main/kotlin/app/sterna/ui/settings/AccountsViewModel.kt")

        const val NOTICE =
            "val notice = RecentMailWidgetContent.notice(snapshot.cell)?.let { app.getString(it) } " +
                "?: app.getString(R.string.app_name)"

        const val REDRAW_LAUNCH = "viewModelScope.launch {"
        const val FORGET = "RecentMailWidgetDraw.forget()"
        const val REFRESH = "RecentMailWidgetDraw.refresh(getApplication())"

        /** The counter's own redraw, on the app lock only — it shows no sender and no subject. */
        const val COUNTER_REFRESH = "UnreadWidgetDraw.refresh(getApplication())"
    }
}
