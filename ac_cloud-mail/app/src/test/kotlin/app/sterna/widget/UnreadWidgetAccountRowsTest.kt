package app.sterna.widget

import app.sterna.core.data.account.StoredAccount
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the ENLARGED cell says, RUN — not read as source, and never recomputed here. Every
 */
class UnreadWidgetAccountRowsTest {

    private fun account(id: String, accountName: String = "", username: String = "$id@example.test") =
        StoredAccount(
            id = id,
            server = "https://mail.example.test",
            username = username,
            accountName = accountName,
            inboxId = "inbox-$id",
        )

    /**
     * Three accounts, three DIFFERENT figures, none of them the total and none of them equal to
     */
    @Test fun `three accounts each get their own line, in the app's order, with their own figure`() {
        val accounts = listOf(
            account("a", accountName = "Work"),
            account("b", accountName = "Family"),
            account("c", accountName = "Lists"),
        )
        assertEquals(
            "each account must get ONE line carrying its OWN unread figure, in the order the app " +
                "lists accounts in (the drawer's order). Not the total on every line, not a sort " +
                "by figure — a widget whose lines jump about as mail is read is unreadable.",
            listOf(
                UnreadAccountRow("Work", 5),
                UnreadAccountRow("Family", 2),
                UnreadAccountRow("Lists", 9),
            ),
            UnreadWidgetContent.byAccount(
                accounts,
                mapOf("a" to 5, "b" to 2, "c" to 9),
                appLockEnabled = false,
            ),
        )
    }

    /** The order is the ACCOUNTS' order, and the map's iteration order changes nothing. */
    @Test fun `the lines follow the accounts, not the order the figures arrived in`() {
        assertEquals(
            "the breakdown is a Map and its iteration order is nobody's decision. The lines must " +
                "follow accountStore.accounts(), the order the drawer already shows.",
            listOf(UnreadAccountRow("Work", 5), UnreadAccountRow("Family", 2)),
            UnreadWidgetContent.byAccount(
                listOf(account("a", accountName = "Work"), account("b", accountName = "Family")),
                mapOf("b" to 2, "a" to 5),
                appLockEnabled = false,
            ),
        )
    }

    /**
     * App lock on → the total, at every size, whatever the breakdown says.
     */
    @Test fun `an app lock leaves nothing to ventilate, however many accounts there are`() {
        assertEquals(
            "with the app lock on the cell must draw the TOTAL, so this rule must return no lines " +
                "at all. A locked app hides senders and subjects; an account label that is really " +
                "an address would sit on the home screen permanently, with no unlock in the way.",
            emptyList<UnreadAccountRow>(),
            UnreadWidgetContent.byAccount(
                listOf(account("a", accountName = "Work"), account("b"), account("c")),
                mapOf("a" to 5, "b" to 2, "c" to 9),
                appLockEnabled = true,
            ),
        )
    }

    /**
     * One account is not a grouping. The drawer offers "All inboxes" only from two accounts up
     */
    @Test fun `a single account is never ventilated`() {
        assertEquals(
            "with one account there is nothing to break down: the cell must keep the layout it " +
                "has, which is that account's own inbox name and its figure.",
            emptyList<UnreadAccountRow>(),
            UnreadWidgetContent.byAccount(
                listOf(account("solo", accountName = "Work")),
                mapOf("solo" to 5),
                appLockEnabled = false,
            ),
        )
    }

    /**
     * Two accounts, ONE known inbox — and the answer is the total, not a single line.
     */
    @Test fun `two accounts with only one known inbox fall back to the total`() {
        assertEquals(
            "fewer than two lines is not a breakdown. One line would repeat the total under one " +
                "account's name and say nothing at all about the account that is missing.",
            emptyList<UnreadAccountRow>(),
            UnreadWidgetContent.byAccount(
                listOf(account("a", accountName = "Work"), account("b", accountName = "Family")),
                mapOf("a" to 5),
                appLockEnabled = false,
            ),
        )
    }

    /**
     * And the absent account gets NO line, not a line at zero — asserted where there ARE two
     */
    @Test fun `an account absent from the breakdown gets no line at all, never a zero`() {
        assertEquals(
            "an account with no known inbox must be left out of the list entirely. A row reading " +
                "0 would tell the user that account has no unread mail, which is not what an " +
                "absent entry means.",
            listOf(UnreadAccountRow("Work", 5), UnreadAccountRow("Lists", 9)),
            UnreadWidgetContent.byAccount(
                listOf(
                    account("a", accountName = "Work"),
                    account("b", accountName = "Family"),
                    account("c", accountName = "Lists"),
                ),
                mapOf("a" to 5, "c" to 9),
                appLockEnabled = false,
            ),
        )
    }

    /**
     * The name is `StoredAccount.label()` — the word the drawer shows — and that is what makes the
     * app-lock guard above necessary: with no `accountName`, `label()` IS the mail address.
     */
    @Test fun `the line's name is the account's label, which falls back to its address`() {
        assertEquals(
            "each line must be named by StoredAccount.label(): the account's own name when it has " +
                "one, and its username — the mail address — when it does not. Anything else puts " +
                "a word on the home screen that the drawer does not use for that account.",
            listOf(UnreadAccountRow("Work", 5), UnreadAccountRow("coline@example.test", 2)),
            UnreadWidgetContent.byAccount(
                listOf(
                    account("a", accountName = "Work"),
                    account("b", accountName = "", username = "coline@example.test"),
                ),
                mapOf("a" to 5, "b" to 2),
                appLockEnabled = false,
            ),
        )
    }

    /** A figure of zero for an account that HAS an inbox is a real answer, and it is drawn. */
    @Test fun `an account whose inbox is counted and empty keeps its line`() {
        assertEquals(
            "0 read from the breakdown is a counted answer — that account's inbox has nothing " +
                "unread — and it is not the same thing as an account nobody could count. Dropping " +
                "it would make an account disappear from the cell whenever it was caught up.",
            listOf(UnreadAccountRow("Work", 5), UnreadAccountRow("Family", 0)),
            UnreadWidgetContent.byAccount(
                listOf(account("a", accountName = "Work"), account("b", accountName = "Family")),
                mapOf("a" to 5, "b" to 0),
                appLockEnabled = false,
            ),
        )
    }
}
