package app.sterna.push

import app.sterna.core.data.account.MailProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A fallback poll cycle that fetched nothing — or fetched an account without its inbox — must say
 */
class FallbackPollSaysWhatItSkippedTest {

    // --- an empty cycle: three failures and one choice, four sentences ---------------------------
    //
    // The promise of the whole volet is here. `watched` is already the output of two SILENT filters
    // — an unreadable blob makes AccountStore.accounts() answer an empty list, and allCredentials()

    /** The failure the blob gate knows about. It must not read as "this phone has no account". */
    @Test fun `an unreadable account blob is not reported as an empty install`() {
        assertEquals(
            "Fallback poll found nothing to fetch: the stored account list could not be read, " +
                "so no account was polled (not an empty install)",
            PushController.nothingToPollLine(stored = 0, candidates = 0, accountsUnreadable = true),
        )
    }

    /**
     * ORDER, and it is the whole reason the unreadable verdict is asked first: an unreadable blob
     */
    @Test fun `the unreadable verdict wins over the empty count it also produces`() {
        assertNotEquals(
            PushController.nothingToPollLine(stored = 0, candidates = 0, accountsUnreadable = false),
            PushController.nothingToPollLine(stored = 0, candidates = 0, accountsUnreadable = true),
        )
        assertEquals(
            "Fallback poll found nothing to fetch: the stored account list could not be read, " +
                "so no account was polled (not an empty install)",
            PushController.nothingToPollLine(stored = 3, candidates = 0, accountsUnreadable = true),
        )
    }

    /** The genuinely empty phone: nothing configured, nothing to poll, nothing wrong. */
    @Test fun `an install with no account says exactly that`() {
        assertEquals(
            "Fallback poll found nothing to fetch: no account is configured, so there was nothing to poll",
            PushController.nothingToPollLine(stored = 0, candidates = 0, accountsUnreadable = false),
        )
    }

    /**
     * The third case, and the one no count could show before: accounts ARE stored, and not one
     */
    @Test fun `stored accounts with no credentials read as a failure, not as a choice`() {
        assertEquals(
            "Fallback poll found nothing to fetch: 2 account(s) configured, but no credentials " +
                "came back for the watched set, so no account was polled (not a choice)",
            PushController.nothingToPollLine(stored = 2, candidates = 0, accountsUnreadable = false),
        )
    }

    /** The only branch that is a decision of the user's: credentials came back, notifications off. */
    @Test fun `every watched account opting out is reported as a choice`() {
        assertEquals(
            "Fallback poll found nothing to fetch: 0 of 2 watched account(s) have notifications on, cycle over",
            PushController.nothingToPollLine(stored = 2, candidates = 2, accountsUnreadable = false),
        )
    }

    /**
     * The four outcomes must be four sentences. Collapsing any two of them puts a failure and a
     */
    @Test fun `the four empty-cycle outcomes print four different sentences`() {
        val lines = listOf(
            PushController.nothingToPollLine(stored = 0, candidates = 0, accountsUnreadable = true),
            PushController.nothingToPollLine(stored = 0, candidates = 0, accountsUnreadable = false),
            PushController.nothingToPollLine(stored = 2, candidates = 0, accountsUnreadable = false),
            PushController.nothingToPollLine(stored = 2, candidates = 2, accountsUnreadable = false),
        )
        assertEquals("two empty-cycle outcomes print the same sentence: $lines", 4, lines.toSet().size)
    }

    // --- the per-account skips: what the code KNOWS, not what it hopes ---------------------------

    /**
     * The JMAP case the `continue` is widest for: the account is skipped whole even with folders
     */
    @Test fun `a skipped JMAP account says its extras are left to the handle`() {
        assertEquals(
            "Fallback poll skipped account acc-1 whole: a push connection handle is held for it, " +
                "so its inbox and its 3 watched extra folder(s) are left to that handle (JMAP)",
            PushController.pollSkippedLine("acc-1", MailProtocol.JMAP, watchedFolders = 3),
        )
    }

    /** On IMAP the whole-skip only happens with no watched extra: IDLE sees the INBOX alone (#16). */
    @Test fun `a skipped IMAP account is one with no watched extra`() {
        assertEquals(
            "Fallback poll skipped account acc-1 whole: a push connection handle is held for it, " +
                "so its inbox and its 0 watched extra folder(s) are left to that handle (IMAP)",
            PushController.pollSkippedLine("acc-1", MailProtocol.IMAP, watchedFolders = 0),
        )
    }

    /**
     * The half-skip, IMAP: the fetch runs, and the INBOX is not in it. Until this line the trace was
     * a successful fetch — the friendliest possible mark for a missed inbox.
     */
    @Test fun `an unfetched inbox says so even though the fetch ran`() {
        assertEquals(
            "Fallback poll left account acc-1's inbox unfetched: a push connection handle is held " +
                "for it, so only its 2 watched extra folder(s) were polled (IMAP)",
            PushController.inboxSkippedLine("acc-1", MailProtocol.IMAP, watchedFolders = 2),
        )
    }

    /**
     * The word this volet may not print. Both lines are reached through
     */
    @Test fun `neither skip line claims a connection is open`() {
        val lines = listOf(
            PushController.pollSkippedLine("acc-1", MailProtocol.JMAP, watchedFolders = 3),
            PushController.inboxSkippedLine("acc-1", MailProtocol.IMAP, watchedFolders = 2),
        )
        lines.forEach { line ->
            assertFalse("a skip line may not claim an open connection: $line", line.lowercase().contains("open"))
            assertTrue("a skip line must say the handle is held: $line", line.contains("handle is held"))
        }
    }

    /**
     * The protocol and the folder count must both reach the sentence: with either dropped, two
     */
    @Test fun `the skip line carries both facts, so two different skips read differently`() {
        val jmapWithExtras = PushController.pollSkippedLine("acc-1", MailProtocol.JMAP, watchedFolders = 3)
        val imapWithout = PushController.pollSkippedLine("acc-1", MailProtocol.IMAP, watchedFolders = 0)
        val jmapWithout = PushController.pollSkippedLine("acc-1", MailProtocol.JMAP, watchedFolders = 0)
        assertEquals(
            "the three skips must print three distinct sentences: $jmapWithExtras / $imapWithout / $jmapWithout",
            3,
            setOf(jmapWithExtras, imapWithout, jmapWithout).size,
        )
    }

    /** Different accounts, different lines: the id is what lets a reader follow one account. */
    @Test fun `the skip lines name the account they skipped`() {
        assertNotEquals(
            PushController.pollSkippedLine("acc-1", MailProtocol.JMAP, watchedFolders = 0),
            PushController.pollSkippedLine("acc-2", MailProtocol.JMAP, watchedFolders = 0),
        )
        assertNotEquals(
            PushController.inboxSkippedLine("acc-1", MailProtocol.IMAP, watchedFolders = 1),
            PushController.inboxSkippedLine("acc-2", MailProtocol.IMAP, watchedFolders = 1),
        )
    }

    /**
     * Five sentences now share the worker's and the service's logcat, and none may be read as
     */
    @Test fun `none of the silent outcomes print the same sentence`() {
        val lines = listOf(
            PushController.nothingToWatchLine(candidates = 1),
            PushController.nothingToPollLine(stored = 1, candidates = 1, accountsUnreadable = false),
            PushController.nothingToPollLine(stored = 1, candidates = 1, accountsUnreadable = true),
            PushController.pollSkippedLine("acc-1", MailProtocol.IMAP, watchedFolders = 1),
            PushController.inboxSkippedLine("acc-1", MailProtocol.IMAP, watchedFolders = 1),
        )
        assertEquals("two of the silent-outcome lines print the same sentence: $lines", 5, lines.toSet().size)
    }

    // --- SOURCE RULE: the call sites, read whole ------------------------------------------------
    //
    // Weakest tests of the file, and the reason is in the class KDoc: doWork cannot be run here.
    // Whole statements, in order, never a substring — a `contains` check is blind to anything
    //
    // `Log.i`, never `Log.d`/`Log.v`: proguard-rules.pro strips those in the release build reporters
    // run. No Throwable argument either: a skip is a fact the app already knows, and Log's cause
    // chain prints the server and device IPs of an OkHttp ConnectException.

    /**
     * The head of `doWork`, pinned as far as the loop. Four things ride on this window and each was
     */
    @Test fun `an empty cycle names its cause, after reconciling the distributor`() {
        assertEquals(
            "expected exactly one nothingToPollLine call site in MailFetchWorker",
            1,
            workerOccurrencesOf("nothingToPollLine"),
        )
        assertEquals(
            listOf(
                "val container = (applicationContext as Application).container",
                "val store = container.accountStore",
                "val watched = if (store.pushAllAccounts()) store.allCredentials() else listOfNotNull(store.load())",
                "val accounts = watched.filter { store.notificationsEnabled(it.id) }",
                "val up = container.unifiedPushManager",
                "up.reconcileDistributorPresence()",
                "if (accounts.isEmpty()) {",
                "Log.i(",
                "TAG,",
                "PushController.nothingToPollLine(",
                "stored = store.accounts().size,",
                "candidates = watched.size,",
                "accountsUnreadable = store.accountsUnreadable(),",
                "),",
                ")",
                "return Result.success()",
                "}",
                "for (credentials in accounts) {",
            ),
            doWorkStatements().take(18),
        )
    }

    /**
     * The two skips and the fetch they guard, in one window, because the fetch is where the volet
     */
    @Test fun `a skipped account and an unfetched inbox both say so, and the fetch keeps its inbox flag`() {
        assertEquals(
            "expected exactly one pollSkippedLine call site in MailFetchWorker",
            1,
            workerOccurrencesOf("pollSkippedLine"),
        )
        assertEquals(
            "expected exactly one inboxSkippedLine call site in MailFetchWorker",
            1,
            workerOccurrencesOf("inboxSkippedLine"),
        )
        assertEquals(
            listOf(
                "val connected = PushService.isConnected(credentials.id)",
                "val pollInbox = shouldPollInbox(connected, linked)",
                "val watchedFolders = store.watchedFolders(credentials.id)",
                "val pollExtras = hasExtrasToPoll(",
                "isImap = credentials.protocol == MailProtocol.IMAP,",
                "watchedFolders = watchedFolders,",
                ")",
                "if (!pollInbox && !pollExtras) {",
                "Log.i(TAG, PushController.pollSkippedLine(credentials.id, credentials.protocol, watchedFolders.size))",
                "continue",
                "}",
                "if (!pollInbox) {",
                "Log.i(TAG, PushController.inboxSkippedLine(credentials.id, credentials.protocol, watchedFolders.size))",
                "}",
                "runCatching {",
                "FetchAndNotify.run(applicationContext, credentials, includeInbox = pollInbox)",
                "}.onFailure { Log.w(TAG, \"Fallback fetch failed for account \${credentials.id}\", it) }",
            ),
            doWorkStatements().dropWhile { !it.startsWith("val connected =") }.take(17),
        )
    }

    /**
     * The other fetch, on the UnifiedPush path, pinned whole for the same reason: the same mutation
     */
    @Test fun `the UnifiedPush path still fetches the account whole`() {
        assertEquals(
            listOf(
                "if (up.isActive(credentials.id)) {",
                "runCatching { FetchAndNotify.run(applicationContext, credentials) }",
                ".onFailure { Log.w(TAG, \"Safety poll failed for account \${credentials.id}\", it) }",
                "continue",
                "}",
            ),
            doWorkStatements().dropWhile { it != "if (up.isActive(credentials.id)) {" }.take(5),
        )
    }

    /**
     * The volet adds lines and nothing else. Every exit of `doWork` is a plain success — the early
     */
    @Test fun `every exit of the worker is still a plain success, with both fetch calls intact`() {
        val statements = doWorkStatements()
        assertEquals(
            "every return of doWork must be a plain success: ${statements.filter { it.startsWith("return ") }}",
            listOf("return Result.success()", "return Result.success()"),
            statements.filter { it.startsWith("return ") },
        )
        assertEquals(
            "expected exactly two FetchAndNotify.run call sites in doWork: $statements",
            2,
            statements.count { it.contains("FetchAndNotify.run") },
        )
    }

    /**
     * The statements of `MailFetchWorker.doWork`, in order, trimmed, with comments and blank lines
     */
    private fun doWorkStatements(): List<String> {
        val lines = workerSource().lines()
        val start = lines.indexOfFirst { it.trim() == DO_WORK_SIGNATURE }
        assertTrue("signature not found in MailFetchWorker: $DO_WORK_SIGNATURE", start >= 0)
        val body = lines.drop(start + 1).takeWhile { it != "    }" }
        assertTrue("doWork is not closed at member indentation", body.size < lines.size - start - 1)
        return body.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") }
    }

    private fun workerOccurrencesOf(token: String): Int = workerSource().split(token).size - 1

    private fun workerSource(): String =
        File(repoRoot, "app/src/main/kotlin/app/sterna/push/MailFetchWorker.kt").readText()

    private companion object {
        const val DO_WORK_SIGNATURE = "override suspend fun doWork(): Result {"

        val repoRoot: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "app/src/main/res/values/strings.xml").isFile }
                ?: error("cannot locate the checkout from ${File("").absolutePath}")
        }
    }
}
