package app.sterna.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — it reads source files as text.
 */
class NotifyCandidatesWiringTest {

    @Test fun `the foreground hook reads the notifier's two sets, not the inbox`() {
        val source = code(FETCH_AND_NOTIFY)
        assertTrue(
            "FetchAndNotify.onInboxRefreshed must read " +
                "container.mailRepository.notifyRead(credentials.id, inboxMailboxId) — the same " +
                "bounded read the push/worker pass gets. Its line is now:\n" +
                source.lines().filter { "mailRepository" in it }.joinToString("\n"),
            "val read = container.mailRepository.notifyRead(credentials.id, inboxMailboxId)"
                in source.lines().map { it.trim() },
        )
        assertTrue(
            "a whole-folder read is back in FetchAndNotify",
            "cachedEmailsForMailboxes" !in source && "getByMailbox" !in source,
        )
    }

    @Test fun `every seed writes the baseline ids, never only the announced messages`() {
        // The defect the core/data two-pass test caught: seeding from what was ANNOUNCED means the
        // baseline is capped, a capped baseline forgets rows, and a later pass announces them —
        // and unarchives their threads, server-side. There are three seed points across the two
        // passes and each has to remember the whole read.
        val lines = code(FETCH_AND_NOTIFY).lines().map { it.trim() }
        listOf(
            "val remembered = baselineIds + returned.map { it.id }",
            "NewMailNotifier.seed(context, credentials.id, folder.mailboxId, remembered)",
            "NewMailNotifier.seed(context, credentials.id, inboxMailboxId, read.baselineIds)",
            "val remembered = read.baselineIds + returned.map { it.id }",
            "NewMailNotifier.seed(context, credentials.id, inboxMailboxId, remembered)",
        ).forEach {
            assertTrue(
                "FetchAndNotify no longer contains the line:\n  $it\n" +
                    "A baseline seeded from anything narrower than the pass's own id read is the " +
                    "audited defect coming back.",
                it in lines,
            )
        }
        assertTrue(
            "notifyDiff must be handed the remembered ids as well as the announceable messages",
            "context, credentials, folder.mailboxId, folderName, folder.emails + returned, remembered," in lines &&
                "NewMailNotifier.notifyDiff(context, credentials, inboxMailboxId, null, emails + returned, remembered)"
                in lines,
        )
    }

    /**
     * SOURCE LINT, NEGATIVE SPACE — these are ALL the places this file seeds a baseline, and
     */
    @Test fun `these three are every baseline this file writes, and there is no fourth`() {
        assertEquals(
            "FetchAndNotify seeds a baseline somewhere new. There are exactly three seed points — " +
                "the folder loop's silent seed, the foreground hook's first sight, and its " +
                "notifications-off arm — and each writes ids the pass actually read. A fourth is " +
                "how an empty baseline gets written without any of the rules above noticing.",
            listOf(
                "NewMailNotifier.seed(context, credentials.id, folder.mailboxId, remembered)",
                "NewMailNotifier.seed(context, credentials.id, inboxMailboxId, read.baselineIds)",
                "NewMailNotifier.seed(context, credentials.id, inboxMailboxId, remembered)",
            ),
            codeLinesNaming(code(FETCH_AND_NOTIFY), "NewMailNotifier.seed("),
        )
    }

    /**
     * SOURCE LINT — a refresh that says "I cannot tell you what this folder holds"
     */
    @Test fun `a folder whose page could not say what it holds is skipped, never remembered as empty`() {
        val lines = code(FETCH_AND_NOTIFY).lines().map { it.trim() }
        assertTrue(
            "FetchAndNotify.run must refuse a null baseline by leaving the folder alone:\n" +
                "  val baselineIds = folder.baselineIds ?: return@forEach\n" +
                "Its lines naming folder.baselineIds are now:\n" +
                lines.filter { "folder.baselineIds" in it }.joinToString("\n"),
            listOf("val baselineIds = folder.baselineIds ?: return@forEach") ==
                lines.filter { "folder.baselineIds" in it },
        )
        assertTrue(
            "a null baseline was flattened into an empty one somewhere in FetchAndNotify — " +
                "`orEmpty()` / `?: emptyList()` on this path IS the defect, spelled quietly",
            "orEmpty()" !in code(FETCH_AND_NOTIFY) && "?: emptyList()" !in code(FETCH_AND_NOTIFY),
        )
    }

    @Test fun `the notifier advances its baseline from the ids it was given`() {
        val lines = code(NEW_MAIL_NOTIFIER).lines().map { it.trim() }
        assertTrue(
            "NewMailNotifier.notifyDiff must end with seed(context, credentials.id, mailboxId, " +
                "baselineIds) — seeding `emails` there re-caps the baseline inside the notifier, " +
                "where no call-site rule can see it.",
            "seed(context, credentials.id, mailboxId, baselineIds)" in lines,
        )
        assertTrue(
            "seed must still REPLACE the baseline (putStringSet) with what it was given. If it " +
                "ever unions instead, the reasoning in NotifyCandidates.kt and the two-pass test " +
                "are about a different function than the one that ships.",
            ".putStringSet(key(accountId, mailboxId), baselineIds.toSet())" in lines,
        )
    }

    @Test fun `the notifier takes its age floor from the shared decision`() {
        val source = code(NEW_MAIL_NOTIFIER)
        assertTrue(
            "NewMailNotifier.newSince must compute its floor with notifyFloor(lastPass) — the " +
                "function core/data's NotifierCandidateWindowTest runs against the bounded read. " +
                "Inlined here, the two can drift by any amount with no test able to see it.",
            "val floor = notifyFloor(lastPass)" in source.lines().map { it.trim() },
        )
        assertTrue(
            "NewMailNotifier declares its own horizon again — there is one number, and it lives " +
                "in core/data's NotifyCandidates.kt because the read one module down needs it",
            "NOTIFY_HORIZON_MS =" !in source,
        )
    }

    @Test fun `the age predicate is the shared one, not a second copy of it`() {
        val source = code(NEW_MAIL_NOTIFIER)
        assertTrue(
            "receivedAfter must delegate to announceableAt(email.receivedAt, floorMs): the read " +
                "keeps undated rows (sortKey = 0) precisely because this predicate lets them " +
                "through, and a private copy can lose that without a word.",
            "private fun receivedAfter(email: Email, floorMs: Long): Boolean = " +
                "announceableAt(email.receivedAt, floorMs)" in source.replace("\n", " ")
                .replace(Regex(" +"), " "),
        )
    }

    @Test fun `mark-all-read asks for the unread keys, not for every cached row`() {
        // The read it replaced was per SCOPE, so in the unified view it was one whole folder per
        // account — for a list of ids and a notification dismissal.
        val source = code(INBOX_VIEW_MODEL)
        assertTrue(
            "InboxViewModel.markAllRead must read repo.cachedUnreadKeys(scopes). Its line is now:\n" +
                source.lines().filter { "cachedUnread" in it }.joinToString("\n"),
            "val cachedUnread = repo.cachedUnreadKeys(scopes)" in source.lines().map { it.trim() },
        )
        assertTrue(
            "a whole-folder read is back in InboxViewModel",
            "cachedEmailsForMailboxes" !in source && "getByMailbox" !in source,
        )
    }

    // -- reading the sources --------------------------------------------------------------------

    /** The code lines of [source] naming [needle] — whole lines, and [code] has already cut the
     *  comments. Whole lines, never a search: `in` is blind to anything a mutation LENGTHENS. */
    private fun codeLinesNaming(source: String, needle: String): List<String> =
        source.lines().map { it.trim() }.filter { needle in it }

    /** [file]'s code as one string, comments cut — the comments beside these call sites name the
     *  very expressions the rules forbid. */
    private fun code(file: File): String = file.readLines().mapNotNull { line ->
        val trimmed = line.trimStart()
        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) null
        else withoutTrailingComment(line).takeIf { it.isNotBlank() }
    }.joinToString("\n")

    /** [line] up to its first `//` outside a double-quoted string; `\` escapes the next character. */
    private fun withoutTrailingComment(line: String): String {
        var inString = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inString && c == '\\' -> i++
                c == '"' -> inString = !inString
                !inString && c == '/' && line.getOrNull(i + 1) == '/' -> return line.substring(0, i).trimEnd()
            }
            i++
        }
        return line.trimEnd()
    }

    private companion object {
        const val FETCH_AND_NOTIFY_PATH = "app/src/main/kotlin/app/sterna/push/FetchAndNotify.kt"
        const val NEW_MAIL_NOTIFIER_PATH = "app/src/main/kotlin/app/sterna/push/NewMailNotifier.kt"
        const val INBOX_VIEW_MODEL_PATH = "app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt"

        /** Repo root, walked up from the module's working directory. */
        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, FETCH_AND_NOTIFY_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        val FETCH_AND_NOTIFY: File by lazy { File(root, FETCH_AND_NOTIFY_PATH) }
        val NEW_MAIL_NOTIFIER: File by lazy { File(root, NEW_MAIL_NOTIFIER_PATH) }
        val INBOX_VIEW_MODEL: File by lazy { File(root, INBOX_VIEW_MODEL_PATH) }
    }
}
