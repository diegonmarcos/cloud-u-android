package app.sterna.widget

import app.sterna.core.data.db.RecentEmailRow

/**
 * WHAT ONE ROW ASKS THE APP TO OPEN: the three identifiers a tap carries, and nothing else.
 */
internal data class RecentRowTap(
    val emailId: String,
    val accountId: String?,
    val mailboxId: String?,
)

/**
 * The rule that derives [RecentRowTap] from a database row, kept apart from Android so it can be
 */
internal object RecentMailWidgetTap {

    /**
     * BLANK IS ABSENT, on the two that may be. `MainActivity.parseEmailOpen` already reads both
     */
    fun of(row: RecentEmailRow): RecentRowTap = RecentRowTap(
        emailId = row.id,
        accountId = row.accountId.takeIf { it.isNotBlank() },
        mailboxId = row.mailboxId.takeIf { it.isNotBlank() },
    )
}
