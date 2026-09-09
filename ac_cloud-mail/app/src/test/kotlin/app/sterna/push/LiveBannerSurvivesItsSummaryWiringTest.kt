package app.sterna.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Volet B of "a live banner must survive its own group summary".
 */
class LiveBannerSurvivesItsSummaryWiringTest {

    @Test fun `the children captured above are put back, and only after the cancel`() {
        val body = bodyOf(NOTIFICATIONS, "updateGroupSummary")
        assertEquals(
            "the cancel of the summary takes every live child of the group down with it, so each " +
                "child captured above must be posted straight back under its own id. Without this " +
                "line the surviving unread message loses its banner and is never announced again " +
                "— mail the server still holds, silently gone from the shade",
            listOf("children.forEach { child -> repostSilently(context, manager, child) }"),
            codeLinesNaming(body, "repostSilently("),
        )

        // THE pin of this file. A list of lines cannot see order, and the mutation that hurts
        // most keeps every line and only moves one: re-post first, cancel second. That is the
        // original defect again — the cascade fires last and wipes what was just put back — and
        // every other assertion here stays green through it.
        val lines = codeLinesNaming(body, "")
        val cancelAt = lines.indexOfFirst { it == "manager.cancel(summaryId)" }
        val repostAt = lines.indexOfFirst { "repostSilently(" in it }
        assertTrue(
            "the re-post must come AFTER the cancel (cancel at line $cancelAt, re-post at line " +
                "$repostAt of the body). Re-posting first lets the summary's cascade take the " +
                "fresh banners down again: the user sees the banner appear and vanish, and the " +
                "unread message is announced by nothing",
            cancelAt >= 0 && repostAt >= 0 && cancelAt < repostAt,
        )
        assertEquals(
            "…and the same two lines, whole and in this order — the pair pinned as a sequence so " +
                "that neither a second cancel nor a re-post moved above it can hide in between",
            listOf(
                "manager.cancel(summaryId)",
                "children.forEach { child -> repostSilently(context, manager, child) }",
            ),
            lines.filter { "manager.cancel(summaryId)" in it || "repostSilently(" in it },
        )
        assertEquals(
            "…and the body WHOLE, every line of it. The pins above name a needle each — the " +
                "re-post, its order, the one read of the shade — and leave the rest of the " +
                "function unheld, yet half of the gesture lives exactly there: the condition that " +
                "decides, the summary's id, the filter on this account's group, and the `return`. " +
                "Seven mutations walk past needle pins and each one costs the user something: " +
                "`if (summaryShownFor(expectedChildIds.size))` demolishes the summary on a real " +
                "burst and hangs one over a lone message; an `if (children.isEmpty()) return` " +
                "before the cancel strands a \"3 new messages\" header alone on the bar for ever; " +
                "`sbn.notification.group != group` re-posts ANOTHER account's banners and prints " +
                "its senders and subjects in this summary (#92 and #84 broken by one character); " +
                "`(\"summary:\" + accountId).hashCode() + 1` never cancels the real summary, which " +
                "then sits above a single banner; and dropping the `return` re-posts the children " +
                "and THEN publishes a summary that rings for mail already announced — the #56 " +
                "promise broken. And the two this fix makes falsifiable: deciding the guard on " +
                "`children.size` instead of `expectedChildIds.size` puts the verdict back on the " +
                "shade's stale answer, where a cancel already asked for still counts and a banner " +
                "just posted does not (Codeberg #134); and dropping `sbn.id !in cancelledChildIds` " +
                "from the filter re-posts the very banner the caller has just cancelled, so a " +
                "dismissed banner comes back and a message deleted elsewhere keeps its banner for " +
                "good. And the two the split of the two sets makes falsifiable: that same filter " +
                "turned back into `sbn.id in expectedChildIds` drops every live child the CALLER " +
                "does not know about — the banner a snooze wake-up posted into this group, the " +
                "one that arrived during the seconds NewMailNotifier spends between its read of " +
                "the shade and this call — and the summary's cascade then takes them down with " +
                "nobody left to put them back: unread mail, gone from the shade for good; and " +
                "`notifyGroupSummary(…, children.size, …)` publishes the size of a list the " +
                "shade's stale answer built, so two banners arriving into an empty group get a " +
                "\"0 new messages\" header sitting over them. And the one THIS fix makes " +
                "falsifiable: dropping `teardownRepairable(expectedChildIds, shownChildIds) &&` " +
                "lets a teardown counted over a banner posted moments ago cascade that banner " +
                "away, with no object anywhere to put it back — the message rang, its banner went " +
                "in seconds, and nothing ever announces it again. Only the whole body sees them",
            listOf(
                "{",
                "val manager = context.getSystemService(NotificationManager::class.java)",
                "val group = GROUP_PREFIX + accountId",
                "val summaryId = (\"summary:\" + accountId).hashCode()",
                "val active = manager.activeNotifications",
                "val children = active.filter { sbn ->",
                "sbn.id != summaryId && sbn.notification.group == group &&",
                "(sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0 &&",
                "sbn.id !in cancelledChildIds",
                "}.sortedByDescending { it.postTime }",
                "val shownChildIds = children.mapTo(mutableSetOf()) { it.id }",
                "val live = expectedChildIds + shownChildIds",
                "if (!summaryShownFor(live.size)) {",
                "if (teardownRepairable(expectedChildIds, shownChildIds) &&",
                "active.any { it.id == summaryId && it.notification.group == group }) {",
                "manager.cancel(summaryId)",
                "children.forEach { child -> repostSilently(context, manager, child) }",
                "}",
                "return",
                "}",
                "val lines = children.map { sbn ->",
                "val sender = sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()",
                "?: context.getString(R.string.notif_new_message)",
                "val subject = sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()",
                "?: context.getString(R.string.message_no_subject)",
                "context.getString(R.string.notif_group_line, sender, subject)",
                "}",
                "notifyGroupSummary(context, accountId, accountLabel, live.size, lines, silent)",
                "}",
            ),
            lines,
        )
    }

    @Test fun `the verdict comes from the caller's expected set, never from the shade`() {
        val body = bodyOf(NOTIFICATIONS, "updateGroupSummary")
        assertEquals(
            "the ONE line the cancelled set may touch is the filter, and what it removes is what " +
                "the caller has just cancelled — nothing wider. `sbn.id in expectedChildIds` " +
                "would read as the same guard and is not: it also drops every live child the " +
                "caller knows nothing about (a snooze wake-up posts into this same group, and " +
                "the diff pass takes seconds between its read of the shade and this call), and " +
                "the summary's cancel then cascades them away with nobody left to put them back",
            listOf("sbn.id !in cancelledChildIds"),
            codeLinesNaming(body, "cancelledChildIds"),
        )
        assertEquals(
            "…and the expected set is used for exactly TWO things, both pinned whole. It is " +
                "unioned with what is on screen — it carries a banner this pass has just posted " +
                "and the shade does not show yet, `notify` being asynchronous exactly like " +
                "`cancel`, so without it the teardown swallows a fresh arrival (Codeberg #134's " +
                "second half) — and it is put to `teardownRepairable`, which is what stops that " +
                "same fresh banner being counted INTO a teardown that cannot put it back",
            listOf(
                "val live = expectedChildIds + shownChildIds",
                "if (teardownRepairable(expectedChildIds, shownChildIds) &&",
            ),
            codeLinesNaming(body, "expectedChildIds"),
        )
        assertEquals(
            "…and `summaryShownFor` is asked exactly once, about that union and nothing else. On " +
                "`children.size` the verdict falls back on a system list that does not yet hold " +
                "a fresh post; on `expectedChildIds.size` alone it ignores the children already " +
                "up that this caller never listed",
            listOf("if (!summaryShownFor(live.size)) {"),
            codeLinesNaming(body, "summaryShownFor("),
        )
        assertEquals(
            "…and the number the user READS is that same union. On `children.size` the header " +
                "counts a filtered, stale list: two messages arriving into an empty group are " +
                "posted, the shade does not show them yet, and the summary goes up saying " +
                "\"0 new messages\" over the two banners it is summarising (notif_group_count is " +
                "a bare %1\$d in all nine languages)",
            listOf("notifyGroupSummary(context, accountId, accountLabel, live.size, lines, silent)"),
            codeLinesNaming(body, "notifyGroupSummary("),
        )
    }

    @Test fun `the teardown is refused unless every id it counted can be put back`() {
        val body = bodyOf(NOTIFICATIONS, "updateGroupSummary")
        assertEquals(
            "the guard, whole, WITH ITS ARGUMENTS — and the arguments are the point. That the " +
                "function is called proves nothing: `teardownRepairable(expectedChildIds, live)` " +
                "compiles, reads right, and is always true, because `live` contains " +
                "`expectedChildIds` by construction. Handed the wrong pair, the summary comes " +
                "down over a banner posted moments ago, the cascade takes it, and nothing on the " +
                "shade can put it back: the user hears the new mail, watches its banner go, and " +
                "is never told about that message again",
            listOf("if (teardownRepairable(expectedChildIds, shownChildIds) &&"),
            codeLinesNaming(body, "teardownRepairable("),
        )
        assertEquals(
            "…and what is on screen is named ONCE, from `children` — the list already stripped of " +
                "this account's summary, of other accounts' banners and of what the caller has " +
                "just cancelled. Built from `active` instead, the guard would see the summary and " +
                "the neighbouring account and call every teardown repairable; built from a second " +
                "read of `manager.activeNotifications`, it would see banners this pass has already " +
                "taken down and hold the summary up for ever",
            listOf("val shownChildIds = children.mapTo(mutableSetOf()) { it.id }"),
            codeLinesNaming(body, "val shownChildIds"),
        )
        assertEquals(
            "…and the union is that same named set, so the guard and the published count can " +
                "never be asked about two different shades. Re-deriving one of them inline is how " +
                "the two drift apart, and a guard that is true of a set nobody counted protects " +
                "nothing",
            listOf("val live = expectedChildIds + shownChildIds"),
            codeLinesNaming(body, "val live"),
        )
    }

    @Test fun `no argument of the summary refresh may be forgotten in silence`() {
        // `bodyOf` starts at the `{`, so the SIGNATURE is held by nothing above. The KDoc promises
        // "No default value, deliberately: a caller that forgets this argument must fail to
        // COMPILE" — that promise is one `= emptySet()` away from being worthless, and nothing
        // else in this suite would go red.
        val params = paramsOf(NOTIFICATIONS, "updateGroupSummary")
        assertEquals(
            "a defaulted parameter here goes missing in silence: the next caller written against " +
                "this function would compile without naming what it cancelled, hand over an " +
                "empty set, and tear summaries down — or re-post banners it had just taken " +
                "down — with the whole suite still green. Both sets, and the user settings beside " +
                "them, must be spelled out at every call site",
            emptyList<String>(),
            params.lines().map { it.trim() }.filter { "=" in it },
        )
        assertTrue(
            "…and the two sets must actually be there, or the pin above passes on a signature " +
                "that lost one: found <$params>",
            "expectedChildIds: Set<Int>," in params && "cancelledChildIds: Set<Int>," in params,
        )
    }

    @Test fun `the re-post only ever touches this account's own children`() {
        val body = bodyOf(NOTIFICATIONS, "updateGroupSummary")
        assertEquals(
            "the shade is read ONCE, into `active`, from which `children` is filtered by this " +
                "account's group and stripped of the summary. A second read here — re-posting " +
                "from `manager.activeNotifications` — would put ANOTHER account's banners back on " +
                "screen, and a send failure or a push notice with them (the class of Codeberg #92: " +
                "the group is per account, GROUP_PREFIX + accountId)",
            listOf("val active = manager.activeNotifications"),
            codeLinesNaming(body, "activeNotifications"),
        )
        assertEquals(
            "…and the one loop in this function walks `children`, that same filtered list",
            listOf("children.forEach { child -> repostSilently(context, manager, child) }"),
            codeLinesNaming(body, ".forEach"),
        )
    }

    @Test fun `every dismissal path hands the summary the set it expects, not a fresh read`() {
        // The three callers together, because a guard that exists on one path only is no guard:
        // the same stale-read race is reachable from a diff pass, from a read receipt and from
        // the notification's own buttons. NewMailNotifier's own pin lives next door, in
        // DepartedMailLosesItsBannerWiringTest — it is the caller that already had the set.
        assertEquals(
            "Notifications.dismiss cancels first and refreshes second, so the shade still shows " +
                "what it cancelled. It must subtract the hits from `active` — the read taken at " +
                "the TOP of the function, before the cancels — and hand THAT over. Recomputing " +
                "from a second read would re-post the banners this call just took down",
            listOf(
                "{",
                "val active = activeChildIds(context, accountId)",
                "val hit = emailIds.filter { isChildActive(active, accountId, it) }",
                "if (hit.isEmpty()) return",
                "hit.forEach { cancelChild(context, accountId, it) }",
                "updateGroupSummary(",
                "context,",
                "accountId,",
                "accountLabel,",
                "silent = true,",
                "expectedChildIds = liveChildIdsAfter(active, accountId, cleared = hit, added = emptyList()),",
                "cancelledChildIds = childIdsOf(accountId, hit),",
                ")",
                "}",
            ),
            codeLinesNaming(bodyOf(NOTIFICATIONS, "dismiss"), ""),
        )

        val receiver = codeLinesNaming(bodyOf(RECEIVER, "dismiss"), "")
        assertEquals(
            "the notification's own buttons take the banner down themselves, so this path must " +
                "read the shade BEFORE its cancel and subtract the message it is dismissing. " +
                "`liveChildIdsAfter` strips both id shapes of `emailId`, so the pre-read is " +
                "exactly what remains. Reading after the cancel replays the very race being " +
                "fixed, inside the fix",
            listOf(
                "{",
                "val active = Notifications.activeChildIds(context, accountId)",
                "NotificationManagerCompat.from(context).cancel(notifId)",
                "credentials?.let {",
                "Notifications.updateGroupSummary(",
                "context, accountId, it.username, silent = true,",
                "expectedChildIds = Notifications.liveChildIdsAfter(active, accountId, listOf(emailId), emptyList()),",
                "cancelledChildIds = Notifications.childIdsOf(accountId, listOf(emailId)),",
                ")",
                "}",
                "}",
            ),
            receiver,
        )
        // Order again, for the same reason as the cancel/re-post pair above: every line can
        // stay and the read simply move below the cancel, and no list-of-lines assertion sees it.
        val readAt = receiver.indexOfFirst { it == "val active = Notifications.activeChildIds(context, accountId)" }
        val cancelAt = receiver.indexOfFirst { it == "NotificationManagerCompat.from(context).cancel(notifId)" }
        assertTrue(
            "the shade must be read BEFORE the cancel (read at line $readAt, cancel at line " +
                "$cancelAt of the body). After it, `getActiveNotifications` still returns the " +
                "banner whose cancel is merely queued, the summary refresh counts it, and the " +
                "banner the user just acted on is put back on screen",
            readAt >= 0 && cancelAt >= 0 && readAt < cancelAt,
        )
    }

    @Test fun `the re-posted banner does not ring, buzz or fly in again`() {
        val body = bodyOf(NOTIFICATIONS, "repostSilently")
        assertEquals(
            "the message was already announced when it first arrived: the re-post must stay mute. " +
                "GROUP_ALERT_SUMMARY hands the noise to a summary that is being cancelled, so " +
                "nothing sounds. Drop this line and every summary teardown makes old mail ring " +
                "again with a fresh heads-up banner",
            listOf(".setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)"),
            codeLinesNaming(body, "setGroupAlertBehavior"),
        )
        assertEquals(
            "…and never GROUP_ALERT_ALL here. That is Codeberg #56 turned inside out — the two " +
                "pop-ups the reporter complained about, brought back by the teardown path — and " +
                "#56 carries a public promise made on 2026-07-28 that the individual banner would " +
                "only come back if he asked for it",
            emptyList<String>(),
            codeLinesNaming(body, "GROUP_ALERT_ALL"),
        )
    }

    @Test fun `the re-posted banner is the very notification that was on screen, copied`() {
        val body = bodyOf(NOTIFICATIONS, "repostSilently")
        assertEquals(
            "the banner is COPIED, not rebuilt. `recoverBuilder` keeps the Reply / Mark as read / " +
                "Delete actions, their PendingIntents, the subText and the BigTextStyle exactly as " +
                "they were posted. Rebuilding from the extras would silently return a banner whose " +
                "buttons are gone or point at the wrong message",
            listOf("Notification.Builder.recoverBuilder(context, child.notification)"),
            codeLinesNaming(body, "recoverBuilder"),
        )
        assertEquals(
            "…and the body WHOLE, every line of it — the pins above name a needle each and leave " +
                "the rest unheld. What could escape them: `manager.notify(child.id + 1, …)` posts " +
                "a duplicate banner nothing will ever cancel; `child.notification.contentIntent` " +
                "swapped for a fresh Intent opens the wrong message on tap; a `.setSilent(true)` " +
                "or a `.setTimeoutAfter(…)` slipped in makes the restored banner behave unlike the " +
                "one it replaces",
            listOf(
                "{",
                "ensureMailChannel(context)",
                "manager.notify(",
                "child.id,",
                "Notification.Builder.recoverBuilder(context, child.notification)",
                ".setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)",
                ".build(),",
                ")",
                "}",
            ),
            codeLinesNaming(body, ""),
        )
    }

    @Test fun `the comment that let this defect live for thirteen months may not come back`() {
        // Searched in the RAW text: `codeLinesNaming` throws comments away, which is exactly where
        // this sentence lives.
        val body = bodyOf(NOTIFICATIONS, "updateGroupSummary")
        assertEquals(
            "\"The system shows a lone child by itself\" states the opposite of what happens: the " +
                "system takes the lone child DOWN with the summary. That sentence is why the " +
                "cancel was read as harmless for thirteen months, and why every reader after it " +
                "stopped looking. It must not come back beside the cancel",
            emptyList<String>(),
            body.lines().map { it.trim() }.filter { "The system shows a lone child by itself" in it },
        )
    }

    /**
     * The code lines of [body] naming [needle], comments and blank lines dropped. An empty
     */
    private fun codeLinesNaming(body: String, needle: String): List<String> =
        body.lines().map { it.trim() }
            .filterNot { it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { needle in it }

    /** The parameter list of `fun [name]` in [file] — parentheses included, KDoc excluded. */
    private fun paramsOf(file: File, name: String): String {
        val source = file.readText()
        val fn = Regex("""\bfun\s+$name\s*\(""").find(source)
            ?: error("${file.name} has no function named '$name' — did it get renamed?")
        val open = source.indexOf('(', fn.range.first)
        var depth = 0
        var i = open
        while (i < source.length) {
            when (source[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return source.substring(open, i + 1)
            }
            i++
        }
        error("Unbalanced parentheses in ${file.name}.$name")
    }

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
        private const val NOTIFICATIONS_PATH = "app/src/main/kotlin/app/sterna/push/Notifications.kt"
        private const val RECEIVER_PATH = "app/src/main/kotlin/app/sterna/push/NotificationActionReceiver.kt"

        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, NOTIFICATIONS_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        private val NOTIFICATIONS: File by lazy { File(root, NOTIFICATIONS_PATH) }
        private val RECEIVER: File by lazy { File(root, RECEIVER_PATH) }
    }
}
