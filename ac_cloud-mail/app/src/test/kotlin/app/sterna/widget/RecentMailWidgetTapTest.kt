package app.sterna.widget

import app.sterna.core.data.db.RecentEmailRow
import app.sterna.core.data.mail.RecentInbox
import app.sterna.core.data.settings.NotificationContent
import app.sterna.ui.components.MonogramSlot
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * WHAT A TAPPED ROW ASKS FOR, RUN — the three identifiers, as literals, never recomputed here.
 */
class RecentMailWidgetTapTest {

    private fun mail(
        id: String = "msg-77",
        accountId: String = "acct-alpha",
        mailboxId: String = "mbox-beta",
        subject: String? = "Quarterly report",
        fromName: String? = "Marie Dupont",
    ) = RecentEmailRow(
        id = id,
        accountId = accountId,
        mailboxId = mailboxId,
        subject = subject,
        fromName = fromName,
        fromEmail = "marie@example.test",
        seen = false,
        sortKey = 1_000L,
        receivedAt = "2026-08-18T09:12:00Z",
    )

    // ---- the three identifiers ----

    /**
     * ALL THREE. The message id alone opens a message; the account is what makes that id mean
     */
    @Test fun `a tap carries the message, its account and its folder`() {
        assertEquals(
            "a row must ask for the three extras the notification route already takes: the " +
                "message, its account (#31) and its folder (#91).",
            RecentRowTap(emailId = "msg-77", accountId = "acct-alpha", mailboxId = "mbox-beta"),
            RecentMailWidgetTap.of(mail()),
        )
    }

    /**
     * BLANK IS ABSENT, on both of the two that may be. `MainActivity.parseEmailOpen` already
     */
    @Test fun `a blank folder id is carried as absent, not as a blank`() {
        assertEquals(
            RecentRowTap(emailId = "msg-77", accountId = "acct-alpha", mailboxId = null),
            RecentMailWidgetTap.of(mail(mailboxId = "   ")),
        )
    }

    @Test fun `an empty folder id is carried as absent too`() {
        assertEquals(
            RecentRowTap(emailId = "msg-77", accountId = "acct-alpha", mailboxId = null),
            RecentMailWidgetTap.of(mail(mailboxId = "")),
        )
    }

    @Test fun `a blank account id is carried as absent`() {
        assertEquals(
            RecentRowTap(emailId = "msg-77", accountId = null, mailboxId = "mbox-beta"),
            RecentMailWidgetTap.of(mail(accountId = " ")),
        )
    }

    @Test fun `both blank leave the message id alone`() {
        assertEquals(
            "⛔ the message id is carried whatever else is missing: it is the one thing the app " +
                "needs to try, and a tap that asks for nothing is a row that swallows touches.",
            RecentRowTap(emailId = "msg-77", accountId = null, mailboxId = null),
            RecentMailWidgetTap.of(mail(accountId = "\t", mailboxId = "")),
        )
    }

    // ---- the tap belongs to the row it was drawn beside ----

    /**
     * EACH LINE'S OWN. The projection is where a tap and a drawn line are married, and an index
     */
    @Test fun `a snapshot pairs every line with its own message`() {
        val snapshot = RecentMailWidgetContent.snapshot(
            inbox = RecentInbox(
                configured = true,
                rows = listOf(
                    mail(id = "msg-1", accountId = "acct-alpha", mailboxId = "mbox-beta", fromName = "Marie Dupont"),
                    mail(id = "msg-2", accountId = "acct-gamma", mailboxId = "mbox-delta", fromName = "Tomasz Nowak"),
                ),
            ),
            content = NotificationContent.SENDER_AND_SUBJECT,
            appLockEnabled = false,
            listMonogram = true,
            unknownSender = "«nobody named»",
            noSubject = "«nothing titled»",
            generic = "«hidden»",
            accountColors = emptyMap(),
            zone = ZoneId.of("UTC"),
            today = LocalDate.of(2026, 8, 18),
            locale = Locale.UK,
        )
        assertEquals(
            "the first line must open the first message and the second the second — with each " +
                "one's own account and folder.",
            listOf(
                RecentWidgetItem(
                    drawn = RecentWidgetRow(
                        "Marie Dupont", "Quarterly report", "09:12", unread = true, accountColor = null,
                        monogram = RecentRowMonogram("M", MonogramSlot(family = 2, tone = 3)),
                    ),
                    tap = RecentRowTap("msg-1", "acct-alpha", "mbox-beta"),
                ),
                RecentWidgetItem(
                    drawn = RecentWidgetRow(
                        "Tomasz Nowak", "Quarterly report", "09:12", unread = true, accountColor = null,
                        monogram = RecentRowMonogram("T", MonogramSlot(family = 2, tone = 3)),
                    ),
                    tap = RecentRowTap("msg-2", "acct-gamma", "mbox-delta"),
                ),
            ),
            snapshot.rows,
        )
    }

    /**
     * AND THE LOCK DOES NOT TOUCH IT. What a tap opens is not what a cell says: the app is
     */
    @Test fun `the app lock silences the line, not what it opens`() {
        val snapshot = RecentMailWidgetContent.snapshot(
            inbox = RecentInbox(configured = true, rows = listOf(mail(id = "msg-9"))),
            content = NotificationContent.SENDER_AND_SUBJECT,
            appLockEnabled = true,
            listMonogram = true,
            unknownSender = "«nobody named»",
            noSubject = "«nothing titled»",
            generic = "«hidden»",
            accountColors = emptyMap(),
            zone = ZoneId.of("UTC"),
            today = LocalDate.of(2026, 8, 18),
            locale = Locale.UK,
        )
        assertEquals(
            listOf(RecentRowTap("msg-9", "acct-alpha", "mbox-beta")),
            snapshot.rows.map { it.tap },
        )
    }
}
