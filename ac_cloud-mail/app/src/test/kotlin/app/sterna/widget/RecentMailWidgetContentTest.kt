package app.sterna.widget

import app.sterna.R
import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.db.RecentEmailRow
import app.sterna.core.data.mail.RecentInbox
import app.sterna.core.data.settings.NotificationContent
import app.sterna.ui.components.MonogramSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * What the latest-messages widget draws, RUN — not read as source, and not recomputed here. Every
 */
class RecentMailWidgetContentTest {

    // The caller's stand-ins, deliberately NOT the app's own strings: they are passed in, and this
    // file proves they are what comes back out.
    private val unknownSender = "«nobody named»"
    private val noSubject = "«nothing titled»"
    private val generic = "«hidden»"

    // A fixed instant and a fixed reference day, so the stamp is a literal and not a clock.
    private val utc = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 8, 18)

    // The two account colours, as the reader picked them in the account editor. Opaque ARGB, and
    // deliberately not the app's own accent: what comes back out must be what went in.
    private val alphaColor = 0xFF17414C.toInt()
    private val gammaColor = 0xFFB3541E.toInt()

    // The badges the fixtures below wear, as LITERALS. Not `monogramSlot("marie@example.test")`:
    // calling the shipped rule to build the expectation is a copy of it, and the two would agree
    // however the rule was rewritten. These three pairs were computed once, off this suite, and are
    // now facts this file holds the widget to.
    private val marieBadge = RecentRowMonogram("M", MonogramSlot(family = 2, tone = 3))
    private val marcBadge = RecentRowMonogram("M", MonogramSlot(family = 0, tone = 4))
    private val marieAtWorkBadge = RecentRowMonogram("M", MonogramSlot(family = 0, tone = 0))
    private val dmitriBadge = RecentRowMonogram("Д", MonogramSlot(family = 2, tone = 0))
    private val nobodyBadge = RecentRowMonogram("N", MonogramSlot(family = 1, tone = 5))
    // Same message, another display name: the badge's LETTER follows the name and its colour does
    // not, because the colour is drawn from the address.
    private val tomaszBadge = RecentRowMonogram("T", MonogramSlot(family = 2, tone = 3))

    private fun account(id: String, color: Int?) = StoredAccount(
        id = id,
        server = "https://mail.example.test",
        username = "$id@example.test",
        inboxId = "inbox-$id",
        inboxName = "Inbox",
        color = color,
    )

    private fun mail(
        id: String = "msg-77",
        accountId: String = "acct-alpha",
        subject: String? = "Quarterly report",
        fromName: String? = "Marie Dupont",
        fromEmail: String? = "marie@example.test",
        seen: Boolean = false,
        receivedAt: String? = "2026-08-18T09:12:00Z",
    ) = RecentEmailRow(
        id = id,
        accountId = accountId,
        mailboxId = "mbox-beta",
        subject = subject,
        fromName = fromName,
        fromEmail = fromEmail,
        seen = seen,
        sortKey = 1_000L,
        receivedAt = receivedAt,
    )

    private fun drawn(
        row: RecentEmailRow = mail(),
        content: NotificationContent = NotificationContent.SENDER_AND_SUBJECT,
        appLockEnabled: Boolean = false,
        listMonogram: Boolean = true,
        accountColors: Map<String, Int> = emptyMap(),
    ) = RecentMailWidgetContent.row(
        row = row,
        content = content,
        appLockEnabled = appLockEnabled,
        listMonogram = listMonogram,
        unknownSender = unknownSender,
        noSubject = noSubject,
        generic = generic,
        accountColors = accountColors,
        zone = utc,
        today = today,
        locale = Locale.UK,
    )

    // ---- the four positions of the notification-content setting ----

    /**
     * BODY_PREVIEW draws NO body here. The rule deliberately gives it the same two lines as
     */
    @Test fun `body preview draws the sender and the subject, and no body`() {
        assertEquals(
            "the widget row rule gives BODY_PREVIEW the sender and the subject — the collapsed " +
                "pair of a notification, never a third line of body.",
            RecentWidgetRow(
                primary = "Marie Dupont", secondary = "Quarterly report", date = "09:12",
                unread = true, accountColor = null, monogram = marieBadge,
            ),
            drawn(content = NotificationContent.BODY_PREVIEW),
        )
    }

    @Test fun `sender and subject draws both`() {
        assertEquals(
            RecentWidgetRow(
                primary = "Marie Dupont", secondary = "Quarterly report", date = "09:12",
                unread = true, accountColor = null, monogram = marieBadge,
            ),
            drawn(content = NotificationContent.SENDER_AND_SUBJECT),
        )
    }

    @Test fun `sender only replaces the subject with the caller's stand-in`() {
        assertEquals(
            "SENDER_ONLY must put the generic line where the subject was — not an empty line, and " +
                "above all not the subject.",
            RecentWidgetRow(
                primary = "Marie Dupont", secondary = generic, date = "09:12",
                unread = true, accountColor = null, monogram = marieBadge,
            ),
            drawn(content = NotificationContent.SENDER_ONLY),
        )
    }

    @Test fun `none names nobody and draws no second line`() {
        assertEquals(
            "NONE is the position where a row identifies no one: the generic line alone, and null " +
                "under it — null and not blank, so the launcher draws nothing rather than a hole.",
            RecentWidgetRow(
                primary = generic, secondary = null, date = "09:12",
                unread = true, accountColor = null, monogram = null,
            ),
            drawn(content = NotificationContent.NONE),
        )
    }

    // ---- the app lock ----

    /**
     * The lock folds every position onto NONE. SECURITY.md promises it keeps someone who picks
     * up the unlocked phone from reading the mail, and a home screen is read by exactly that person.
     */
    @Test fun `the app lock silences even the most talkative position`() {
        assertEquals(
            "with the app lock enabled a row must say what NONE says, whatever the setting is.",
            RecentWidgetRow(
                primary = generic, secondary = null, date = "09:12",
                unread = true, accountColor = null, monogram = null,
            ),
            drawn(content = NotificationContent.BODY_PREVIEW, appLockEnabled = true),
        )
    }

    /**
     * NOTHING of the message survives the lock — and the sender and the subject are not the only
     */
    @Test fun `under the lock no drawn field carries the sender, the subject, or any identifier`() {
        val row = drawn(content = NotificationContent.BODY_PREVIEW, appLockEnabled = true)
        val needles = listOf(
            "Marie Dupont", "marie@example.test", "Quarterly report",
            "acct-alpha", "mbox-beta", "msg-77",
        )
        assertEquals(
            "these must not appear anywhere in what a locked row draws: $row",
            emptyList<String>(),
            needles.filter { it in row.toString() },
        )
    }

    /**
     * The same, one level up: a whole snapshot, locked, DRAWING nobody's name.
     */
    @Test fun `under the lock a whole snapshot draws nobody`() {
        val snapshot = RecentMailWidgetContent.snapshot(
            inbox = RecentInbox(configured = true, rows = listOf(mail(), mail(fromName = "Tomasz Nowak"))),
            content = NotificationContent.SENDER_AND_SUBJECT,
            appLockEnabled = true,
            listMonogram = true,
            unknownSender = unknownSender,
            noSubject = noSubject,
            generic = generic,
            accountColors = emptyMap(),
            zone = utc,
            today = today,
            locale = Locale.UK,
        )
        val drawn = snapshot.rows.map { it.drawn }
        val needles = listOf("Marie Dupont", "Tomasz Nowak", "marie@example.test", "Quarterly report", "acct-alpha")
        assertEquals(
            "a locked cell lists dates and unread marks and nothing else: $drawn",
            emptyList<String>(),
            needles.filter { it in drawn.toString() },
        )
    }

    /**
     * THE FRONTIER THE TAP PANE HAD TO CROSS WITHOUT BREAKING: a line now CARRIES the three
     */
    @Test fun `at every position of the setting, and under the lock, no DRAWN field carries an identifier`() {
        val identifiers = listOf("msg-77", "acct-alpha", "mbox-beta")
        val positions = NotificationContent.entries
        val drawnFindings = positions.associateWith { content ->
            listOf(true, false).flatMap { locked ->
                val item = RecentMailWidgetContent.item(
                    row = mail(),
                    content = content,
                    appLockEnabled = locked,
                    listMonogram = true,
                    unknownSender = unknownSender,
                    noSubject = noSubject,
                    generic = generic,
                    accountColors = emptyMap(),
                    zone = utc,
                    today = today,
                    locale = Locale.UK,
                )
                identifiers.filter { it in item.drawn.toString() }
            }
        }
        assertEquals(
            "the message id, the account id and the folder id travel in an Intent's extras and " +
                "must reach no drawn field. One of them in RecentWidgetRow is one setTextViewText " +
                "away from an unlocked home screen naming a mailbox.",
            positions.associateWith { emptyList<String>() },
            drawnFindings,
        )
        assertEquals(
            "⭐ and they must still be carried: a line that draws nothing AND opens nothing would " +
                "satisfy the rule above by doing the widget's job badly.",
            positions.associateWith { RecentRowTap("msg-77", "acct-alpha", "mbox-beta") },
            positions.associateWith { content ->
                RecentMailWidgetContent.item(
                    row = mail(),
                    content = content,
                    appLockEnabled = true,
                    listMonogram = true,
                    unknownSender = unknownSender,
                    noSubject = noSubject,
                    generic = generic,
                    accountColors = emptyMap(),
                    zone = utc,
                    today = today,
                    locale = Locale.UK,
                ).tap
            },
        )
    }

    // ---- the columns are nullable, and may be blank ----

    @Test fun `a message with no display name is drawn by its address`() {
        assertEquals(
            "fromName is a nullable column; the list inside the app falls back to the address and " +
                "so does the widget.",
            "marie@example.test",
            drawn(row = mail(fromName = null)).primary,
        )
    }

    @Test fun `a display name stored blank counts as absent`() {
        assertEquals(
            "a server that stored an empty display name would otherwise draw a row whose first " +
                "line is one space.",
            "marie@example.test",
            drawn(row = mail(fromName = "   ")).primary,
        )
    }

    @Test fun `with neither name nor address the caller's stand-in is drawn`() {
        assertEquals(
            unknownSender,
            drawn(row = mail(fromName = null, fromEmail = null)).primary,
        )
    }

    @Test fun `a name and an address both blank count as absent too`() {
        assertEquals(
            unknownSender,
            drawn(row = mail(fromName = " ", fromEmail = "\t")).primary,
        )
    }

    @Test fun `a message with no subject wears the caller's stand-in`() {
        assertEquals(noSubject, drawn(row = mail(subject = null)).secondary)
    }

    @Test fun `a subject stored blank counts as absent`() {
        assertEquals(noSubject, drawn(row = mail(subject = "  ")).secondary)
    }

    // ---- the date and the unread mark, which the setting does not govern ----

    @Test fun `a message from an earlier day of this year is stamped with its day and month`() {
        assertEquals(
            "the widget dates a row with MailDates.formatListDate — the list's own stamp, so the " +
                "home screen and the app never disagree about what today looks like.",
            "4 Jul",
            drawn(row = mail(receivedAt = "2026-07-04T09:12:00Z")).date,
        )
    }

    @Test fun `a read message is not marked unread`() {
        assertEquals(false, drawn(row = mail(seen = true)).unread)
    }

    @Test fun `an unreadable timestamp costs the stamp and nothing else`() {
        assertEquals(
            RecentWidgetRow(
                primary = "Marie Dupont", secondary = "Quarterly report", date = "",
                unread = true, accountColor = null, monogram = marieBadge,
            ),
            drawn(row = mail(receivedAt = null)),
        )
    }

    // ---- the badge a line wears ----

    /**
     * WHICH LINES MAY WEAR A BADGE, and it is not this file's opinion: it is the third field of
     */
    @Test fun `the three talkative positions wear a badge, and the silent one wears none`() {
        assertEquals(
            "a badge is an initial, and an initial is a letter of somebody's name. The three " +
                "positions that already print the sender may carry one; NONE prints the generic " +
                "line precisely so that nobody is named, and a letter there would name them.",
            mapOf(
                NotificationContent.BODY_PREVIEW to marieBadge,
                NotificationContent.SENDER_AND_SUBJECT to marieBadge,
                NotificationContent.SENDER_ONLY to marieBadge,
                NotificationContent.NONE to null,
            ),
            NotificationContent.entries.associateWith { drawn(content = it).monogram },
        )
    }

    /**
     * AND THE LOCK TAKES IT AWAY, FROM EVERY POSITION. SECURITY.md promises a locked home screen
     */
    @Test fun `the app lock leaves no badge at all, at any position`() {
        assertEquals(
            "with the app lock enabled a row must carry NO badge, whatever the setting says. An " +
                "initial is a letter of the sender's name and the lock's whole promise is that a " +
                "home-screen row names nobody.",
            NotificationContent.entries.associateWith { null },
            NotificationContent.entries.associateWith {
                drawn(content = it, appLockEnabled = true).monogram
            },
        )
    }

    /**
     * AND THE INITIALS SWITCH TAKES IT AWAY TOO — the setting #144 added to the message list
     */
    @Test fun `the initials switch removes the badge from the most talkative row, and nothing else`() {
        assertEquals(
            "with the initials switch OFF the badge goes and every word of the row stays: the " +
                "sender, the subject, the stamp and the unread weight are governed by the " +
                "notification-content setting and the app lock, and by nothing this switch says.",
            RecentWidgetRow(
                primary = "Marie Dupont", secondary = "Quarterly report", date = "09:12",
                unread = true, accountColor = null, monogram = null,
            ),
            drawn(content = NotificationContent.BODY_PREVIEW, listMonogram = false),
        )
        assertEquals(
            "and with it ON the same row wears the badge — the two answers must differ, or the " +
                "rule above is satisfied by a widget that never draws one.",
            RecentWidgetRow(
                primary = "Marie Dupont", secondary = "Quarterly report", date = "09:12",
                unread = true, accountColor = null, monogram = marieBadge,
            ),
            drawn(content = NotificationContent.BODY_PREVIEW, listMonogram = true),
        )
    }

    /** Off is off at every position of the content setting, not only at the loud one. */
    @Test fun `with the initials switched off no position of the setting draws a badge`() {
        assertEquals(
            "the initials switch is read once per row and applies at all four positions.",
            NotificationContent.entries.associateWith { null },
            NotificationContent.entries.associateWith {
                drawn(content = it, listMonogram = false).monogram
            },
        )
    }

    @Test fun `a message with no display name takes its initial from the address`() {
        assertEquals(
            "fromName is a nullable column; the letter then comes from the address, exactly as " +
                "the first line does — never a question mark for a message that has an address.",
            marieBadge,
            drawn(row = mail(fromName = null)).monogram,
        )
    }

    @Test fun `a display name stored blank leaves the initial to the address`() {
        assertEquals(marieBadge, drawn(row = mail(fromName = "   ")).monogram)
    }

    /**
     * With neither name nor address the badge falls back to the caller's stand-in, on BOTH halves:
     */
    @Test fun `with neither name nor address the badge comes from the caller's stand-in`() {
        assertEquals(
            nobodyBadge,
            drawn(row = mail(fromName = null, fromEmail = null)).monogram,
        )
    }

    /**
     * A NON-LATIN SENDER KEEPS THEIR OWN ALPHABET. `initialOf` upper-cases whatever the first
     * letter-or-digit is, so Cyrillic д becomes Д and not a transliteration and not "?".
     */
    @Test fun `a cyrillic sender wears a cyrillic capital`() {
        assertEquals(
            "the badge takes the first letter of the name as it is written, upper-cased in its " +
                "own script. A row whose sender writes in Cyrillic must not be reduced to \"?\".",
            dmitriBadge,
            drawn(row = mail(fromName = "Дмитрий Иванов", fromEmail = "dmitri@example.test")).monogram,
        )
    }

    /**
     * THE POINT OF COLOURING A BADGE AT ALL. Two correspondents whose names start with the same
     */
    @Test fun `two senders with the same initial are told apart by the colour, not by the letter`() {
        val marie = drawn(row = mail(fromName = "Marie Dupont", fromEmail = "marie@example.test")).monogram
        val marc = drawn(row = mail(fromName = "Marc Petit", fromEmail = "marc@example.test")).monogram
        assertEquals(
            "two senders whose names begin with the same letter must wear the SAME letter — the " +
                "badge is the initial, not a disambiguator.",
            listOf("M", "M"),
            listOf(marie?.initial, marc?.initial),
        )
        assertEquals(
            "⭐ and they must wear DIFFERENT slots: the letter cannot tell them apart, so the " +
                "colour has to. One slot for both is a column of identical badges, which is what " +
                "the badge was added to avoid.",
            listOf(marieBadge.slot, marcBadge.slot),
            listOf(marie?.slot, marc?.slot),
        )
    }

    /**
     * THE SEED IS THE ADDRESS, NOT THE DISPLAYED NAME, and this is the assertion that pins it.
     */
    @Test fun `the badge's colour follows the address, and never the displayed name`() {
        val home = drawn(row = mail(fromName = "Marie Dupont", fromEmail = "marie@example.test")).monogram
        val work = drawn(row = mail(fromName = "Marie Dupont", fromEmail = "marie.dupont@work.test")).monogram
        assertEquals(
            "same displayed name, two addresses: the slots must DIFFER. Equal here means the seed " +
                "is the name, and the same person would then be one colour in the widget and " +
                "another in the app's own list, which is the report that opened this work.",
            listOf(marieBadge.slot, marieAtWorkBadge.slot),
            listOf(home?.slot, work?.slot),
        )
        val renamed = drawn(row = mail(fromName = "Tomasz Nowak", fromEmail = "marie@example.test")).monogram
        assertEquals(
            "and the mirror image: one address, two displayed names, ONE slot. A server that " +
                "starts sending a display name must not repaint a correspondent the reader had " +
                "learnt to recognise.",
            listOf(marieBadge.slot, "T"),
            listOf(renamed?.slot, renamed?.initial),
        )
    }

    /**
     * AND THE SEED DOES NOT TRAVEL. [RecentWidgetRow] is the shape the launcher draws; the
     */
    @Test fun `the address the badge was seeded from is not carried on the drawn row`() {
        val row = drawn(content = NotificationContent.SENDER_ONLY, row = mail())
        assertEquals(
            "the drawn row must not carry the seed. Only the letter and the slot cross: $row",
            emptyList<String>(),
            listOf("marie@example.test").filter { it in row.toString() },
        )
        assertEquals(
            "⭐ and the badge must still be there — a row that carried nothing would satisfy the " +
                "rule above by drawing no badge at all.",
            marieBadge,
            row.monogram,
        )
    }

    // ---- the three states of the cell ----

    @Test fun `known inboxes with messages show the list`() {
        assertEquals(RecentWidgetCell.Rows, RecentMailWidgetContent.cell(configured = true, hasRows = true))
    }

    @Test fun `known inboxes holding nothing are the only empty inbox`() {
        assertEquals(
            "this is the ONE state that may say the mailbox is empty: the scopes were read, the " +
                "query ran, and it answered nothing.",
            RecentWidgetCell.EmptyInbox,
            RecentMailWidgetContent.cell(configured = true, hasRows = false),
        )
    }

    /**
     * THE ARM THIS FILE EXISTS FOR. `configured = false` is "no answer", not "no message" and
     */
    @Test fun `a read that knew no inbox affirms nothing`() {
        assertEquals(
            "configured = false must be its own state. EmptyInbox here is the home screen telling " +
                "a reader whose accounts are still syncing that they have no mail.",
            RecentWidgetCell.NothingToSay,
            RecentMailWidgetContent.cell(configured = false, hasRows = false),
        )
    }

    /**
     * And what that state DRAWS is pinned on the claim, not on a wording: the app's own name, which
     * says nothing about anybody's mail — never the empty-inbox line.
     */
    @Test fun `the state that affirms nothing draws the app's name, never the empty line`() {
        assertEquals(
            "the silent state names the app and stops there.",
            R.string.app_name,
            RecentMailWidgetContent.notice(RecentWidgetCell.NothingToSay),
        )
        assertNotEquals(
            "⛔ and it is NOT the empty-inbox line, which is a claim about the reader's mailbox.",
            R.string.widget_latest_empty,
            RecentMailWidgetContent.notice(RecentWidgetCell.NothingToSay),
        )
    }

    @Test fun `the empty inbox draws the empty line, and a list draws none`() {
        assertEquals(R.string.widget_latest_empty, RecentMailWidgetContent.notice(RecentWidgetCell.EmptyInbox))
        assertEquals(null, RecentMailWidgetContent.notice(RecentWidgetCell.Rows))
    }

    // ---- the whole read, projected ----

    @Test fun `a snapshot lists the rows it was handed, in order`() {
        val snapshot = RecentMailWidgetContent.snapshot(
            inbox = RecentInbox(
                configured = true,
                rows = listOf(mail(), mail(subject = "Keys", fromName = "Tomasz Nowak", seen = true)),
            ),
            content = NotificationContent.SENDER_AND_SUBJECT,
            appLockEnabled = false,
            listMonogram = true,
            unknownSender = unknownSender,
            noSubject = noSubject,
            generic = generic,
            accountColors = emptyMap(),
            zone = utc,
            today = today,
            locale = Locale.UK,
        )
        assertEquals(
            RecentWidgetSnapshot(
                cell = RecentWidgetCell.Rows,
                rows = listOf(
                    RecentWidgetItem(
                        drawn = RecentWidgetRow(
                            "Marie Dupont", "Quarterly report", "09:12", unread = true, accountColor = null,
                            monogram = marieBadge,
                        ),
                        tap = RecentRowTap("msg-77", "acct-alpha", "mbox-beta"),
                    ),
                    RecentWidgetItem(
                        drawn = RecentWidgetRow(
                            "Tomasz Nowak", "Keys", "09:12", unread = false, accountColor = null,
                            monogram = tomaszBadge,
                        ),
                        tap = RecentRowTap("msg-77", "acct-alpha", "mbox-beta"),
                    ),
                ),
            ),
            snapshot,
        )
    }

    @Test fun `a snapshot that knew no inbox lists nothing at all`() {
        val snapshot = RecentMailWidgetContent.snapshot(
            inbox = RecentInbox(configured = false, rows = listOf(mail())),
            content = NotificationContent.SENDER_AND_SUBJECT,
            appLockEnabled = false,
            listMonogram = true,
            unknownSender = unknownSender,
            noSubject = noSubject,
            generic = generic,
            accountColors = emptyMap(),
            zone = utc,
            today = today,
            locale = Locale.UK,
        )
        assertEquals(
            "nothing is affirmed and nothing is listed — a row drawn under this state would be a " +
                "row from a read that had no scope to read.",
            RecentWidgetSnapshot(RecentWidgetCell.NothingToSay, emptyList()),
            snapshot,
        )
    }

    // ---- which account a line came from ----

    /**
     * THE PANE'S OWN DECISION, EXECUTED. The list interleaves several accounts by date and, until
     */
    @Test fun `two accounts with colours each get their own`() {
        assertEquals(
            "each account's rows must wear the colour the reader picked for it, keyed by account " +
                "id. One shared colour, or a key that is not the account id, and the dot stops " +
                "telling the two mailboxes apart while still looking like a working feature.",
            mapOf("acct-alpha" to alphaColor, "acct-gamma" to gammaColor),
            RecentMailWidgetContent.accountColors(
                listOf(account("acct-alpha", alphaColor), account("acct-gamma", gammaColor)),
            ),
        )
    }

    /**
     * ONE ACCOUNT, NO DOT AT ALL — the bound is [AllInboxesView], the same one the counter's
     */
    @Test fun `a single account is given no colour, however carefully it was chosen`() {
        assertEquals(
            "with one account the widget must hand back NO colours: a dot in front of every row " +
                "of a one-mailbox list distinguishes nothing, and the drawer does not offer " +
                "\"All inboxes\" at that count either.",
            emptyMap<String, Int>(),
            RecentMailWidgetContent.accountColors(listOf(account("acct-alpha", alphaColor))),
        )
    }

    /**
     * AND "AUTO" IS NOT A COLOUR. `StoredAccount.color` is null on the first position of the
     */
    @Test fun `an account left on auto is left out, and does not take its neighbour's colour`() {
        assertEquals(
            "an account whose colour is null must be ABSENT from the map — not defaulted, and " +
                "above all not given the other account's colour, which would mark two mailboxes " +
                "as one.",
            mapOf("acct-gamma" to gammaColor),
            RecentMailWidgetContent.accountColors(
                listOf(account("acct-alpha", null), account("acct-gamma", gammaColor)),
            ),
        )
    }

    /**
     * AND EACH LINE TAKES ITS OWN ACCOUNT'S COLOUR. This is the mutation the map alone cannot
     */
    @Test fun `each line of a snapshot wears its own account's colour`() {
        val snapshot = RecentMailWidgetContent.snapshot(
            inbox = RecentInbox(
                configured = true,
                rows = listOf(
                    mail(id = "msg-1", accountId = "acct-alpha"),
                    mail(id = "msg-2", accountId = "acct-gamma", fromName = "Tomasz Nowak"),
                ),
            ),
            content = NotificationContent.SENDER_AND_SUBJECT,
            appLockEnabled = false,
            listMonogram = true,
            unknownSender = unknownSender,
            noSubject = noSubject,
            generic = generic,
            accountColors = mapOf("acct-alpha" to alphaColor, "acct-gamma" to gammaColor),
            zone = utc,
            today = today,
            locale = Locale.UK,
        )
        assertEquals(
            "the dot must follow the row's OWN accountId. The same colour on both lines is the " +
                "defect this widget exists to avoid: two mailboxes interleaved by date, and " +
                "nothing on screen saying which is which.",
            listOf(
                RecentWidgetItem(
                    drawn = RecentWidgetRow(
                        "Marie Dupont", "Quarterly report", "09:12", unread = true, accountColor = alphaColor,
                        monogram = marieBadge,
                    ),
                    tap = RecentRowTap("msg-1", "acct-alpha", "mbox-beta"),
                ),
                RecentWidgetItem(
                    drawn = RecentWidgetRow(
                        "Tomasz Nowak", "Quarterly report", "09:12", unread = true, accountColor = gammaColor,
                        monogram = tomaszBadge,
                    ),
                    tap = RecentRowTap("msg-2", "acct-gamma", "mbox-beta"),
                ),
            ),
            snapshot.rows,
        )
    }
}
