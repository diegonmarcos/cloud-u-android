package app.sterna.widget

import app.sterna.core.data.account.StoredAccount

/**
 * Where a tap on the cell lands. Two arms only, and neither is a screen the widget invents:
 */
internal enum class WidgetTapTarget { UnifiedInbox, AppAsIs }

/**
 * WHAT a tap asks the app for, kept apart from Android so it can be EXECUTED in a JVM test; the
 */
internal object UnreadWidgetTap {

    fun of(accounts: List<StoredAccount>): WidgetTapTarget =
        if (AllInboxesView.existsFor(accounts)) WidgetTapTarget.UnifiedInbox else WidgetTapTarget.AppAsIs
}
