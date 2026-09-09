package app.sterna.widget

import app.sterna.R
import app.sterna.appLocale
import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.db.RecentEmailRow
import app.sterna.core.data.mail.RecentInbox
import app.sterna.core.data.settings.NotificationContent
import app.sterna.push.MailNotificationText
import app.sterna.ui.components.MonogramSlot
import app.sterna.ui.components.initialOf
import app.sterna.ui.components.monogramSlot
import app.sterna.util.MailDates
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * ONE row of the latest-messages widget, as the launcher will draw it: six fields, all decided.
 */
internal data class RecentWidgetRow(
    val primary: String,
    val secondary: String?,
    val date: String,
    val unread: Boolean,
    val accountColor: Int?,
    val monogram: RecentRowMonogram?,
)

/** The badge one row wears: the letter, and WHICH of the palette's eighteen colours paints it —
 *  never the colour itself. A widget row is projected once and drawn many times, possibly after a
 *  theme change, so a resolved colour would be the palette of the moment the mail arrived. */
internal data class RecentRowMonogram(val initial: String, val slot: MonogramSlot)

/** What the CELL is, before a single row is considered — three states, and the third is easy to get
 *  wrong: `recentUnifiedInbox` answers false to `configured` for no account, a blob that failed to
 *  decode, and an inbox id not known yet, the state every restored backup starts in. In none of
 *  them is "your inbox is empty" a true sentence. */
internal sealed interface RecentWidgetCell {

    data object Rows : RecentWidgetCell

    /** Inboxes are known and hold nothing. The ONLY state that may say the mailbox is empty. */
    data object EmptyInbox : RecentWidgetCell

    /** Nothing may be affirmed about mail. Not "no account": see the three causes above. */
    data object NothingToSay : RecentWidgetCell
}

/** One line of the list, both halves: what it DRAWS and what it OPENS. Two fields and not one
 *  flat row — merged, an identifier on the drawn type would be one `setTextViewText` away from the
 *  home screen. */
internal data class RecentWidgetItem(val drawn: RecentWidgetRow, val tap: RecentRowTap)

/** Everything one draw needs: the cell's state, and the rows it will list (empty unless [RecentWidgetCell.Rows]). */
internal data class RecentWidgetSnapshot(val cell: RecentWidgetCell, val rows: List<RecentWidgetItem>)

/**
 * The latest-messages widget's whole decision, kept apart from Android so it can be EXECUTED in a
 */
internal object RecentMailWidgetContent {

    /** The three states, from what the read answered. [hasRows] and not the rows themselves: the
     *  decision is about the SHAPE of the cell and must not be able to look at a subject. */
    fun cell(configured: Boolean, hasRows: Boolean): RecentWidgetCell = when {
        // First arm, deliberately: a read that knows no inbox says nothing. Folded into "no
        // rows", three restored accounts read as an empty mailbox.
        !configured -> RecentWidgetCell.NothingToSay
        hasRows -> RecentWidgetCell.Rows
        else -> RecentWidgetCell.EmptyInbox
    }

    /** The single line drawn INSTEAD of the list, or null when the list is what the cell shows.
     * [RecentWidgetCell.NothingToSay] resolves to the app's own name, never to the empty-inbox
     *  line: that is the whole difference between the two silent-looking states. */
    fun notice(cell: RecentWidgetCell): Int? = when (cell) {
        RecentWidgetCell.Rows -> null
        RecentWidgetCell.EmptyInbox -> R.string.widget_latest_empty
        RecentWidgetCell.NothingToSay -> R.string.app_name
    }

    /**
     * The dot each account's rows wear, by account id — and an EMPTY map whenever no dot should be
     */
    fun accountColors(accounts: List<StoredAccount>): Map<String, Int> =
        if (!AllInboxesView.existsFor(accounts)) emptyMap()
        else accounts.mapNotNull { account -> account.color?.let { account.id to it } }.toMap()

    /** One database row, projected onto what the launcher draws. [unknownSender] and [noSubject] are
     *  the app's stand-ins, applied HERE because the widget-row rule takes non-null `String`s while
     *  the columns are nullable. The date and the unread mark are not governed by the setting:
     *  neither names anyone. */
    fun row(
        row: RecentEmailRow,
        content: NotificationContent,
        appLockEnabled: Boolean,
        listMonogram: Boolean,
        unknownSender: String,
        noSubject: String,
        generic: String,
        accountColors: Map<String, Int>,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone),
        locale: Locale = appLocale,
    ): RecentWidgetRow {
        val drawn = MailNotificationText.widgetRow(
            content = content,
            appLockEnabled = appLockEnabled,
            sender = sender(row, unknownSender),
            subject = row.subject.orBlankTo(noSubject),
            generic = generic,
        )
        return RecentWidgetRow(
            primary = drawn.primary,
            secondary = drawn.secondary,
            date = MailDates.formatListDate(row.receivedAt, zone, today, locale),
            unread = !row.seen,
            // The badge is drawn only where the row ALREADY names its sender, and that answer
            // comes from the rule above — the only place that has seen the app lock fold the
            monogram = if (!listMonogram || !drawn.namesSender) null else RecentRowMonogram(
                initial = initialOf(row.fromName.orEmpty(), sender(row, unknownSender)),
                slot = monogramSlot(seed(row, unknownSender)),
            ),
            // THIS row's account, by THIS row's id: a colour taken from anywhere else paints the
            // whole list one colour, which reads like a working feature.
            accountColor = accountColors[row.accountId],
        )
    }

    /** One database row, projected onto BOTH halves of a line, so the line that opens a message can
     *  never be a different line from the one that named it. */
    fun item(
        row: RecentEmailRow,
        content: NotificationContent,
        appLockEnabled: Boolean,
        listMonogram: Boolean,
        unknownSender: String,
        noSubject: String,
        generic: String,
        accountColors: Map<String, Int>,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone),
        locale: Locale = appLocale,
    ): RecentWidgetItem = RecentWidgetItem(
        drawn = row(
            row, content, appLockEnabled, listMonogram, unknownSender, noSubject, generic, accountColors,
            zone, today, locale,
        ),
        tap = RecentMailWidgetTap.of(row),
    )

    fun snapshot(
        inbox: RecentInbox,
        content: NotificationContent,
        appLockEnabled: Boolean,
        listMonogram: Boolean,
        unknownSender: String,
        noSubject: String,
        generic: String,
        accountColors: Map<String, Int>,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone),
        locale: Locale = appLocale,
    ): RecentWidgetSnapshot {
        // Not named `cell`: a local of that name beside the function of that name is a trap.
        val shape = cell(inbox.configured, inbox.rows.isNotEmpty())
        val rows = if (shape == RecentWidgetCell.Rows) {
            inbox.rows.map {
                item(
                    it, content, appLockEnabled, listMonogram, unknownSender, noSubject, generic, accountColors,
                    zone, today, locale,
                )
            }
        } else {
            emptyList()
        }
        return RecentWidgetSnapshot(shape, rows)
    }

    /** The name a row wears: `fromName`, else `fromEmail`, else the app's stand-in — the message
     *  list's own three steps, so the two never call one message two things. BLANK counts as
     *  absent at every step. */
    private fun sender(row: RecentEmailRow, unknownSender: String): String =
        row.fromName?.takeIf { it.isNotBlank() }
            ?: row.fromEmail?.takeIf { it.isNotBlank() }
            ?: unknownSender

    /**
     * What a badge's COLOUR is derived from: the address, else the name shown, else the stand-in —
     */
    private fun seed(row: RecentEmailRow, unknownSender: String): String =
        row.fromEmail?.takeIf { it.isNotBlank() }
            ?: row.fromName?.takeIf { it.isNotBlank() }
            ?: unknownSender

    private fun String?.orBlankTo(fallback: String): String =
        this?.takeIf { it.isNotBlank() } ?: fallback
}
