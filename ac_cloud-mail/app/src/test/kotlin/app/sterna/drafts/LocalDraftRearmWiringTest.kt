package app.sterna.drafts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT. `AppContainer` builds Room, WorkManager and a `Context`, and a `CoroutineWorker`
 */
class LocalDraftRearmWiringTest {

    private fun linesOf(file: File): List<String> =
        file.readText().lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") || it.isEmpty() }

    private fun blockAt(lines: List<String>, anchor: String, before: Int, size: Int): List<String> {
        val at = lines.indexOf(anchor)
        assertTrue("no line reads exactly:\n  $anchor\nthe file holds:\n" + lines.joinToString("\n"), at >= 0)
        return lines.subList(at - before, at - before + size)
    }

    @Test fun `startup re-arms the drafts the phone is still holding, one account at a time`() {
        // Two orders in one block, and both matter:
        //  - the rescue runs BEFORE the scheduling, or a row stranded in UPLOADING by a process
        val lines = linesOf(APPLICATION)
        assertEquals(
            "AppContainer must re-arm the local drafts at startup, exactly as it re-arms the outbox:",
            listOf(
                "appScope.launch {",
                "runCatching {",
                "mailRepository.accountsWithLocalDrafts().forEach { accountId ->",
                "mailRepository.revertUnfinishedLocalDrafts(accountId)",
                "localDraftUploadJobs(",
                "accountId,",
                "mailRepository.localDraftsAwaitingUpload(accountId),",
                "System.currentTimeMillis(),",
                ").forEach { LocalDraftUploads.enqueue(appContext, it.accountId, it.id, it.delayMillis) }",
                "}",
                "}.onFailure { Log.w(\"Sterna\", \"local-draft re-arm failed; retried next start\", it) }",
                "}",
            ),
            blockAt(lines, "mailRepository.accountsWithLocalDrafts().forEach { accountId ->", before = 2, size = 12),
        )
    }

    @Test fun `the save itself can book a draft's upload, through the seam the outbox uses`() {
        // Without this line `LocalDraftUploads.enqueue` has exactly two callers — the startup
        // re-arm and the worker re-booking itself — so a draft saved with no network leaves the
        // phone only after the app has DIED and started again. The row is written and the backoff
        // is armed, and nothing is ever attached to it. That is the promise of the volet, unkept.
        val lines = linesOf(APPLICATION)
        assertEquals(
            "the data layer must be handed a way to book a draft's upload, exactly as it is handed " +
                "one to book a send (`mailRepository.outboxScheduler`):",
            listOf(
                "mailRepository.localDraftScheduler = LocalDraftScheduler { accountId, id, delay ->",
                "LocalDraftUploads.enqueue(appContext, accountId, id, delay)",
                "}",
            ),
            blockAt(
                lines,
                "mailRepository.localDraftScheduler = LocalDraftScheduler { accountId, id, delay ->",
                before = 0, size = 3,
            ),
        )
    }

    @Test fun `the work item waits for a network and is one per local row`() {
        val lines = linesOf(SCHEDULER)
        assertEquals(
            "a deferred draft upload must be gated on connectivity — that is what makes it happen " +
                "on its own when the network comes back — and keyed per (account, draft), so two " +
                "waiting drafts do not replace each other's work item. ⛔ And the policy is the " +
                "CALLER's: everything books under one unique name, so a hard-coded REPLACE cancels " +
                "whatever is there — including the retry WorkManager itself armed for a run it " +
                "stopped, which the stop path books behind (KEEP) rather than over:",
            listOf(
                "val request = OneTimeWorkRequestBuilder<LocalDraftUploadWorker>()",
                ".setInitialDelay(delayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)",
                ".setInputData(workDataOf(KEY_ACCOUNT to accountId, KEY_ID to id))",
                ".setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())",
                ".setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)",
                ".build()",
                "val policy = if (keepExisting) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE",
                "WorkManager.getInstance(context)",
                ".enqueueUniqueWork(workName(accountId, id), policy, request)",
            ),
            blockAt(lines, "val request = OneTimeWorkRequestBuilder<LocalDraftUploadWorker>()", before = 0, size = 9),
        )
        assertEquals(
            "⛔ and nothing in the scheduler may cancel or drop a draft's work: a draft has no " +
                "deadline, and the only thing that ends its wait is an upload that went through",
            emptyList<String>(),
            lines.filter { "cancel" in it },
        )
    }

    @Test fun `the worker gives up on nothing and deletes nothing`() {
        // THE difference with OutboxWorker, and the reason this is a second worker rather than a
        // flag on that one: no MAX_ATTEMPTS, no parked state, and above all no delete. A draft
        val lines = linesOf(WORKER)
        // And the re-booking goes through `uploadLocalDraftRebooking`, which is not decoration:
        // when WorkManager STOPS this worker mid-upload the coroutine is cancelled, every
        assertEquals(
            "the worker must read BOTH halves of the row's key from the data it was given, ask the " +
                "repository for the verdict, and hand the re-booking decision the queue — " +
                "deciding nothing of its own:",
            listOf(
                "override suspend fun doWork(): Result {",
                "val accountId = inputData.getString(LocalDraftUploads.KEY_ACCOUNT) ?: return Result.success()",
                "val id = inputData.getString(LocalDraftUploads.KEY_ID) ?: return Result.success()",
                "val container = (applicationContext as android.app.Application).container",
                "val credentials = container.accountStore.credentials(accountId) ?: return Result.success()",
                "uploadLocalDraftRebooking(",
                // A CLOCK, not an instant. `nowMillis = System.currentTimeMillis()` is an
                // argument, so it is read BEFORE the attempt — and the attempt may now take up to
                // a minute. The re-booking would then carry that minute on top of the row's own
                // backoff, and the slower the link the later the draft comes back.
                "now = { System.currentTimeMillis() },",
                "upload = { container.mailRepository.uploadLocalDraft(credentials, id) },",
                // BOTH arguments handed on, in this order. Dropping the second — or hard-coding
                // it — is what makes a stopped worker's booking REPLACE the retry WorkManager just
                // armed for it, i.e. a 30 s delay to the resumption it was meant to guarantee.
                "rebook = { delay, onlyIfNothingBooked ->",
                "LocalDraftUploads.enqueue(applicationContext, accountId, id, delay, onlyIfNothingBooked)",
                "},",
                ")",
                "return Result.success()",
                "}",
            ),
            blockAt(lines, "override suspend fun doWork(): Result {", before = 0, size = 14),
        )
        assertEquals(
            "⛔ no attempt cap may reappear in the worker (OutboxLogic.MAX_ATTEMPTS is the outbox's, " +
                "and it exists because an undeliverable MESSAGE has a recipient waiting; a draft " +
                "has nobody waiting and no deadline)",
            emptyList<String>(),
            lines.filter { "MAX_ATTEMPTS" in it || "shouldRetry" in it },
        )
        assertEquals(
            "⛔ and nothing here deletes a row. The repository consumes it on a successful upload " +
                "and on nothing else; a `delete` in the worker is the loss this chantier exists to " +
                "prevent, one 'account not found' away",
            emptyList<String>(),
            lines.filter { "delete" in it.lowercase() || "discard" in it.lowercase() },
        )
        assertEquals(
            "⛔ Result.failure() would let WorkManager drop the work item for good — a draft that " +
                "fails is retried, always",
            emptyList<String>(),
            lines.filter { "Result.failure()" in it },
        )
    }

    private companion object {
        val APPLICATION = repoFile("app/src/main/kotlin/app/sterna/SternaApplication.kt")
        val SCHEDULER = repoFile("app/src/main/kotlin/app/sterna/drafts/LocalDraftUploads.kt")
        val WORKER = repoFile("app/src/main/kotlin/app/sterna/drafts/LocalDraftUploadWorker.kt")

        private fun repoFile(path: String): File =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, path) }
                .firstOrNull { it.isFile }
                ?: error(
                    "cannot find $path from ${File("").absolutePath} — this test reads sources as " +
                        "text and needs a working directory inside the checkout",
                )
    }
}
