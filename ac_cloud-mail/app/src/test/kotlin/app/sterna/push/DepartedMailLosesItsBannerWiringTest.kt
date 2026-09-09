package app.sterna.push

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, and a narrow one: nothing in this chain can be instantiated in a JVM test
 */
class DepartedMailLosesItsBannerWiringTest {

    @Test fun `the diff pass cancels the banners of the ids the server dropped`() {
        val body = bodyOf(NOTIFIER, "notifyDiff")
        assertEquals(
            "the decision must be the pure one, and it must be fed the folder's post-sync content: " +
                "an id the folder still holds is not gone, whatever the delta said. It names " +
                "CANDIDATES, not departures — nothing is cancelled before the server confirms",
            listOf(
                "val candidates = Notifications.departuresToCancel(active, credentials.id, departedIds, emails.map { it.id })",
            ),
            codeLinesNaming(body, "departuresToCancel("),
        )
        assertEquals(
            "read elsewhere and dropped by the server are cancelled the same way — cancelChild, " +
                "which also clears the pre-#92 id form a banner posted by an older build carries",
            listOf(
                "readIds.forEach { Notifications.cancelChild(context, credentials.id, it) }",
                "departed.forEach { Notifications.cancelChild(context, credentials.id, it) }",
            ),
            codeLinesNaming(body, "Notifications.cancelChild("),
        )
        assertEquals(
            "a pass whose only work is removing a banner must still reach the cancel",
            listOf("if (newMail.isNotEmpty() || readIds.isNotEmpty() || departed.isNotEmpty()) {"),
            codeLinesNaming(body, "isNotEmpty() || readIds"),
        )
        assertEquals(
            "the summary count must be told about the departures too, or it keeps counting a " +
                "message that is gone and the wrong voice announces the batch (#56). Computed " +
                "ONCE, into `live`, from the pre-pass read: it is also what the group summary is " +
                "handed below, and a second computation there would drift from the voice already " +
                "chosen",
            listOf(
                "val live = Notifications.liveChildIdsAfter(active, credentials.id, readIds + departed, newMail.map { it.id })",
            ),
            codeLinesNaming(body, "liveChildIdsAfter("),
        )
        assertEquals(
            "…and the voice for this batch is decided from that same set, by the pure rule",
            listOf("val summarised = Notifications.summaryShownFor(live.size)"),
            codeLinesNaming(body, "summaryShownFor("),
        )
        assertEquals(
            "…and `live` is what descends to the group summary. Codeberg #134: `cancel` returns " +
                "once the system has queued the work and `getActiveNotifications` does not drain " +
                "that queue, so a summary that re-reads the shade still sees the banners this " +
                "pass just cancelled — it tears the summary down and puts them BACK. The set from " +
                "BEFORE the pass, minus what left and plus what was posted, is the only honest " +
                "answer. Anything else here (a fresh `activeChildIds`, a second " +
                "`liveChildIdsAfter`) re-opens the race",
            listOf("expectedChildIds = live,"),
            codeLinesNaming(body, "expectedChildIds"),
        )
        assertEquals(
            "…and what this pass CANCELLED is handed over separately, in both id shapes, because " +
                "that — not `live` — is what the summary refresh removes from the shade before " +
                "re-posting. `live` is a set the caller computed for itself: everything alive it " +
                "never heard of is missing from it, and using it to filter would let the " +
                "summary's cancel cascade those banners away with nobody to put them back. This " +
                "function is a long way from its own read of the shade — a server round trip for " +
                "the departures, then the preview pre-pass — and a snooze wake-up posts into " +
                "this very group",
            listOf("cancelledChildIds = Notifications.childIdsOf(credentials.id, readIds + departed),"),
            codeLinesNaming(body, "cancelledChildIds"),
        )
        assertEquals(
            "removing a banner re-posts the summary, so it must stay SILENT when the pass brought " +
                "no new mail — a deletion made elsewhere may not ring",
            listOf("silent = silent || newMail.isEmpty(),"),
            codeLinesNaming(body, "silent = silent"),
        )
    }

    @Test fun `no banner is taken down before the server says the message really left`() {
        // The dangerous half of #134, found by the counter-expertise. `MailRepository`'s own KDoc
        // says the first queryChanges run from a pre-change queryState can report an email as
        val body = bodyOf(NOTIFIER, "notifyDiff")
        assertEquals(
            "the notifier must ask the REPOSITORY — the layer that talks to the server — never " +
                "hold a JMAP client of its own",
            listOf("val repository = (context.applicationContext as Application).container.mailRepository"),
            codeLinesNaming(body, "container.mailRepository"),
        )
        assertEquals(
            "what is cancelled must be the SERVER's answer about this very folder, never the " +
                "candidate list the delta produced",
            listOf("val departed = repository.confirmDepartures(credentials, mailboxId, candidates)"),
            codeLinesNaming(body, "confirmDepartures("),
        )
    }

    @Test fun `each refreshed folder hands the notifier its own departures`() {
        // Pinned as two whole lines rather than one: the call is multi-line since the bounded-read
        // branch added `remembered` beside the announceable set (its own lint pins that argument —
        // NotifyCandidatesWiringTest). `departedIds` is the LAST argument and defaulted, so
        // dropping it compiles: only this assertion sees it go.
        val run = bodyOf(FETCH_AND_NOTIFY, "run")
        assertEquals(
            "the diff must be fed this folder's messages AND the ids it will remember",
            listOf(
                "context, credentials, folder.mailboxId, folderName, folder.emails + returned, remembered,",
            ),
            codeLinesNaming(run, "folder.mailboxId, folderName"),
        )
        assertEquals(
            "without this argument nothing the sync learned ever reaches a cancel, and #134 stands",
            listOf("folder.departedIds,"),
            codeLinesNaming(run, "departedIds"),
        )
    }

    /** The code lines of [body] naming [needle], comments dropped. */
    private fun codeLinesNaming(body: String, needle: String): List<String> =
        body.lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
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
        private const val PUSH_SOURCES = "app/src/main/kotlin/app/sterna/push"
        private const val NOTIFIER_PATH = "$PUSH_SOURCES/NewMailNotifier.kt"
        private const val FETCH_AND_NOTIFY_PATH = "$PUSH_SOURCES/FetchAndNotify.kt"

        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, NOTIFIER_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        private val NOTIFIER: File by lazy { File(root, NOTIFIER_PATH) }
        private val FETCH_AND_NOTIFY: File by lazy { File(root, FETCH_AND_NOTIFY_PATH) }
    }
}
