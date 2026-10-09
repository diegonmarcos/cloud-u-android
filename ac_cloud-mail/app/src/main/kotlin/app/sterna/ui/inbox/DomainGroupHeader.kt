package app.sterna.ui.inbox

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import app.sterna.R
import app.sterna.core.data.mail.InboxRow
import app.sterna.core.data.mail.SenderDomain
import app.sterna.ui.theme.MailMetrics
import app.sterna.util.MailDates

/**
 * What a "Group by Domain" heading says: the domain, how many messages the group holds, how many of
 * them are unread, and the newest one's date. The group's row IS its newest message (the grouping
 * SQL picks the latest of the group as the representative), so its date is the group's newest.
 */
internal data class DomainGroupHeading(val domain: String, val count: Int, val unread: Int, val newestIso: String?) {
    companion object {
        fun of(row: InboxRow) = DomainGroupHeading(
            domain = SenderDomain.label(SenderDomain.registrable(row.email.from.firstOrNull()?.email)),
            count = row.threadCount,
            unread = row.unreadCount,
            newestIso = row.email.receivedAt,
        )
    }
}

/**
 * One dense line above a domain group's row: the domain in bold, then its counts, the newest date at
 * the end. The rows under it are ordinary rows and keep the sender's name, so this line is the only
 * place the domain is named.
 */
@Composable
internal fun DomainGroupHeader(heading: DomainGroupHeading, modifier: Modifier = Modifier) {
    val newest = remember(heading.newestIso) { MailDates.formatListDate(heading.newestIso) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = MailMetrics.s12, end = MailMetrics.s12, top = MailMetrics.s6, bottom = MailMetrics.s2)
            .semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            heading.domain,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(MailMetrics.s8))
        Text(
            stringResource(R.string.list_domain_group_counts, heading.count, heading.unread),
            style = MaterialTheme.typography.labelSmall,
            color = muted,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        Text(newest, style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 1)
    }
}
