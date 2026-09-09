package app.sterna.push

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Volet B of "a banner nobody can act on": the sign-out has to take the account's banners with it.
 */
class SignedOutAccountLosesItsBannersTest {

    // The group key as Android reports it on a live notification. It is `GROUP_PREFIX + accountId`
    // (private in Notifications, and its literal is pinned below), i.e. what
    // `.setGroup(GROUP_PREFIX + accountId)` wrote when the notification was posted.
    private fun group(accountId: String) = "mail:$accountId"

    @Test fun `the sign-out takes the account's children AND its summary`() {
        val active = listOf(
            101 to group("alex"), // a per-message banner
            102 to group("alex"), // another one
            103 to group("alex"), // and the group summary — same group, that is the point
        )
        assertEquals(
            "every banner of the account being signed out has to go. One left behind is one that " +
                "will stand for ever: nothing else ever revisits an account with no credentials",
            listOf(101, 102, 103),
            Notifications.accountGroupIds(active, "alex"),
        )
    }

    @Test fun `the summary is taken too, not spared the way activeChildIds spares it`() {
        // The summary carries the same group and is only told apart by FLAG_GROUP_SUMMARY, which
        // `activeChildIds` filters on. Filtering on it HERE would leave "3 new messages —
        // Alex (work)" on screen after the account is gone, expanding to the InboxStyle lines of
        // messages whose children were just cancelled.
        val summaryOnly = listOf(900 to group("alex"))
        assertEquals(
            "the group summary survived the sign-out: the shade still shows a count and a header " +
                "for an account that no longer exists, over lines whose messages are gone",
            listOf(900),
            Notifications.accountGroupIds(summaryOnly, "alex"),
        )
    }

    @Test fun `signing one account out leaves the other account's banners alone`() {
        val active = listOf(
            101 to group("alex"),
            201 to group("jordan"),
            202 to group("jordan"),
        )
        assertEquals(
            "signing out of one account wiped the OTHER account's shade: mail that is still " +
                "unread, on an account that lost nothing, silently vanished from the phone",
            listOf(101),
            Notifications.accountGroupIds(active, "alex"),
        )
    }

    @Test fun `an ungrouped banner is never touched, and Message not sent is ungrouped`() {
        // `notifySendFailed` posts with no group at all (the only two `.setGroup(` calls in the
        // app are pinned below). It is the banner that CARRIES the text the user typed — the
        // hand-back is the only place that text still exists. Cancelling it destroys it.
        val active = listOf(101 to group("alex"), 555 to null)
        assertEquals(
            "a sign-out destroyed the 'Message not sent' banner, and with it the only surviving " +
                "copy of the text the user typed into the notification",
            listOf(101),
            Notifications.accountGroupIds(active, "alex"),
        )
    }

    @Test fun `an account id that is a prefix of another does not take its banners`() {
        val active = listOf(1 to group("a"), 2 to group("ab"), 3 to group("a-b"))
        assertEquals(
            "signing out of account 'a' also cleared 'ab' and 'a-b': ids are opaque strings and " +
                "one being the start of another means nothing",
            listOf(1),
            Notifications.accountGroupIds(active, "a"),
        )
        assertEquals(
            "…and the same the other way round: the longer id must not sweep the shorter one's",
            listOf(2),
            Notifications.accountGroupIds(active, "ab"),
        )
    }

    @Test fun `nothing to cancel is an empty answer, not everything`() {
        assertEquals(
            "an account with no live banner cancelled something anyway",
            emptyList<Int>(),
            Notifications.accountGroupIds(listOf(1 to group("jordan"), 2 to null), "alex"),
        )
        assertEquals(
            "an empty shade cancelled something anyway",
            emptyList<Int>(),
            Notifications.accountGroupIds(emptyList(), "alex"),
        )
    }

    /**
     * SOURCE LINT — the call site. `AccountsViewModel` is an `AndroidViewModel` and this module
     */
    @Test fun `sign-out cancels the banners of exactly the accounts it removed`() {
        val body = bodyOf(ACCOUNTS_VM, "signOut")
        assertEquals(
            "a signed-out account keeps its banners for ever: `NewMailNotifier.clear` beside this " +
                "line only drops the SharedPreferences baselines, it cancels nothing on screen, " +
                "and no background pass will ever look at an account with no credentials again",
            listOf("removed.forEach { Notifications.cancelAccount(app, it) }"),
            codeLinesNaming(body, "cancelAccount("),
        )
        assertEquals(
            "…and EVERY list this function walks, enumerated whole. `removed` is what " +
                "removeCascading actually took out (with its `.ifEmpty { toRemove }` fallback); " +
                "`store.accounts()` or `toRemove` in its place cancels banners for accounts that " +
                "lost nothing, and reads as deliberate",
            listOf(
                "store.allCredentials().filter { it.id in toRemove }.forEach {",
                "removed.forEach { NewMailNotifier.clear(app, it) }",
                "removed.forEach { Notifications.cancelAccount(app, it) }",
                "removed.forEach {",
            ),
            codeLinesNaming(body, ".forEach {"),
        )
    }

    @Test fun `no other path in the app cancels an account's banners`() {
        assertEquals(
            "cancelAccount belongs to the sign-out and to nothing else. Enumerated LINE by line " +
                "and not by file: a second call added to AccountsViewModel.kt — the very file " +
                "that already holds the legitimate one — would leave a by-file list untouched, " +
                "and on a live account it wipes unread mail off the shade. Which is also why it " +
                "is not called from `NewMailNotifier.clear`, whose three other one-argument " +
                "callers (setNotificationsEnabled, setPushAllAccounts, onAccountPruned) run " +
                "against accounts that are perfectly alive or that nothing else here owns",
            listOf(
                "AccountsViewModel.kt: removed.forEach { Notifications.cancelAccount(app, it) }",
                "Notifications.kt: fun cancelAccount(context: Context, accountId: String) {",
            ),
            SHIPPED_SOURCES.asSequence()
                .flatMap { File(root, it).walkTopDown() }
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { file -> codeLinesNaming(file.readText(), "cancelAccount(").map { "${file.name}: $it" } }
                .toList().sorted(),
        )
    }

    @Test fun `only the child and the summary carry the account group, and the prefix is mail`() {
        val source = NOTIFICATIONS.readText()
        assertEquals(
            "the group key the decision matches on is built from THIS literal; change it and the " +
                "groups this test feeds `accountGroupIds` stop being the ones the app posts",
            listOf("private const val GROUP_PREFIX = \"mail:\""),
            codeLinesNaming(source, "GROUP_PREFIX ="),
        )
        assertEquals(
            "exactly two notifications carry an account's group — the per-message child and the " +
                "group summary — which is why cancelling by group is allowed to be this blunt. A " +
                "third `.setGroup(GROUP_PREFIX + accountId)` (a send failure, a snooze) would be " +
                "swept away by a sign-out along with them",
            listOf(".setGroup(GROUP_PREFIX + accountId)", ".setGroup(GROUP_PREFIX + accountId)"),
            codeLinesNaming(source, ".setGroup("),
        )
    }

    /**
     * SOURCE LINT over two very short bodies, and here is why it is not a copy of the decision:
     */
    @Test fun `neither the decision nor its wiring may tell the summary apart from a child`() {
        val decision = bodyOf(NOTIFICATIONS, "accountGroupIds")
        val wiring = bodyOf(NOTIFICATIONS, "cancelAccount")
        assertEquals(
            "ONE filter, on the group, whole. A second condition here — `id != summaryId`, or the " +
                "FLAG_GROUP_SUMMARY test copied from activeChildIds — leaves the group summary " +
                "standing after the sign-out: a header and a count for an account that is gone",
            listOf("return active.filter { (_, notificationGroup) -> notificationGroup == group }.map { it.first }"),
            codeLinesNaming(decision, "filter"),
        )
        assertEquals(
            "…and the wiring hands over EVERY live notification, sifting nothing on the way in — " +
                "the same exclusion made here would be just as invisible to the tests above",
            listOf("val active = manager.activeNotifications.map { it.id to it.notification.group }"),
            codeLinesNaming(wiring, "activeNotifications"),
        )
        assertEquals(
            "no summary id and no summary flag anywhere on this path, in EITHER function. This is " +
                "the one place in the file that must not tell the two apart, and it is also why " +
                "there is no fourth copy of the summary id here",
            emptyList<String>(),
            codeLinesNaming(decision + wiring, "summary") +
                codeLinesNaming(decision + wiring, "FLAG_GROUP_SUMMARY") +
                codeLinesNaming(decision + wiring, "filterNot"),
        )
        assertEquals(
            "…and BOTH bodies WHOLE, every line of them — the pins above name a needle each and " +
                "leave the rest of the two functions unheld. What escaped: the arguments of the " +
                "only real call of the decision, and the loop that does the cancelling. " +
                "`accountGroupIds(active, \"\")` matches no group at all and turns the whole " +
                "sign-out into a no-op; a `.drop(1)` before the loop leaves one banner standing " +
                "for ever; a `manager.cancelAll()` empties the shade of every account on the " +
                "phone and takes the push service's own notification with it",
            listOf(
                "{",
                "val group = GROUP_PREFIX + accountId",
                "return active.filter { (_, notificationGroup) -> notificationGroup == group }.map { it.first }",
                "}",
                "{",
                "val manager = context.getSystemService(NotificationManager::class.java)",
                "val active = manager.activeNotifications.map { it.id to it.notification.group }",
                "accountGroupIds(active, accountId).forEach { manager.cancel(it) }",
                "}",
            ),
            codeLinesNaming(decision, "") + codeLinesNaming(wiring, ""),
        )
    }

    /**
     * The code lines of [body] naming [needle], comments and blank lines dropped. An empty
     */
    private fun codeLinesNaming(body: String, needle: String): List<String> =
        body.lines().map { it.trim() }
            .filterNot { it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { needle in it }

    /** The block body of `fun [name]` in [file] — braces included. */
    private fun bodyOf(file: File, name: String): String {
        val source = file.readText()
        val fn = Regex("""\bfun\s+$name\s*\(""").find(source)
            ?: error("${file.name} has no function named '$name' — did it get renamed?")
        val open = source.indexOf('{', fn.range.last)
        var depth = 0
        var i = open
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open, i + 1)
            }
            i++
        }
        error("Unbalanced braces in ${file.name}.$name")
    }

    companion object {
        private const val MAIN_SOURCES = "app/src/main/kotlin"

        /** Every source set that ships in an APK — `test` is the only one that does not. */
        private val SHIPPED_SOURCES = listOf(MAIN_SOURCES, "app/src/testApp/kotlin", "app/src/benchShared/kotlin")
        private const val NOTIFICATIONS_PATH = "$MAIN_SOURCES/app/sterna/push/Notifications.kt"
        private const val ACCOUNTS_VM_PATH = "$MAIN_SOURCES/app/sterna/ui/settings/AccountsViewModel.kt"

        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, NOTIFICATIONS_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        private val NOTIFICATIONS: File by lazy { File(root, NOTIFICATIONS_PATH) }
        private val ACCOUNTS_VM: File by lazy { File(root, ACCOUNTS_VM_PATH) }
    }
}
