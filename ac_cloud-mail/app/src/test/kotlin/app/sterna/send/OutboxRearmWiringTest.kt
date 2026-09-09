package app.sterna.send

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT. `WorkManager` needs a `Context` and this module has neither Robolectric nor
 */
class OutboxRearmWiringTest {

    private fun linesOf(file: File): List<String> =
        file.readText().lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") || it.isEmpty() }

    private fun blockAt(lines: List<String>, anchor: String, before: Int, size: Int): List<String> {
        val at = lines.indexOf(anchor)
        assertTrue("no line reads exactly:\n  $anchor\nthe file holds:\n" + lines.joinToString("\n"), at >= 0)
        return lines.subList(at - before, at - before + size)
    }

    @Test fun `the send's work item takes its policy from the caller, and replaces by default`() {
        val lines = linesOf(SCHEDULER)
        assertEquals(
            "a queued send must be gated on connectivity — that is what makes it leave on its own " +
                "when the network comes back — and the REPLACE/KEEP choice belongs to the CALLER, " +
                "which is why the DEFAULT is pinned here with the body: `keepExisting: Boolean = " +
                "false` flipped to `= true` compiles, changes no call site, and makes every " +
                "booking that names no policy a KEEP. OutboxWorker re-books ITSELF for a HELD row " +
                "(undo window, scheduled send) while its own unique work is RUNNING, so a kept " +
                "booking is thrown away: the undo window never closes, no scheduled send ever " +
                "leaves, and Retry — which goes through the same seam — does nothing at all. " +
                "Flipped the other way at the call site, every cold start cancels a delivery that " +
                "is genuinely in flight, which the worker then parks as \"interrupted, may already " +
                "have been sent\" on a send nothing interrupted:",
            listOf(
                "fun enqueue(",
                "context: Context,",
                "id: Long,",
                "initialDelayMillis: Long = 0,",
                // THE DEFAULT IS THE RULE. Only the startup re-arm may keep; every other caller
                // carries the newest instant there is and must replace.
                "keepExisting: Boolean = false,",
                ") {",
                "val request = OneTimeWorkRequestBuilder<OutboxWorker>()",
                ".setInitialDelay(initialDelayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)",
                ".setInputData(workDataOf(OutboxWorker.KEY_ID to id))",
                ".setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())",
                ".setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)",
                ".build()",
                "val policy = if (keepExisting) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE",
                "WorkManager.getInstance(context).enqueueUniqueWork(workName(id), policy, request)",
            ),
            blockAt(lines, "val request = OneTimeWorkRequestBuilder<OutboxWorker>()", before = 6, size = 14),
        )
    }

    @Test fun `the startup re-arm books behind what is already there, never over it`() {
        // THE WHOLE BLOCK, not the enqueue line alone: `keepExisting = true` is an ARGUMENT, and
        // dropping it is the cheapest mutation of this volet — it compiles, it reads fine, and it
        // restores the defect in full.
        val lines = linesOf(APPLICATION)
        assertEquals(
            "the startup re-arm cannot know whether WorkManager has already restored the job for a " +
                "send, and that job may be in flight this very second (SENDING written, message " +
                "going out). A REPLACE here cancels it; the cancellation kills the worker's own " +
                "suspending writes, so the row stays SENDING and the next pickup parks it. The " +
                "user sees \"Interrupted, may already have been sent\" on a send nothing " +
                "interrupted, at every cold start — and the exponential backoff of an offline send " +
                "restarts from zero each time the app is opened:",
            listOf(
                // ASSIGNED, not anonymous: `AppContainer.outboxRecovery` is the Job a composer
                // rebuilt after a process death joins before taking its row back by the id its
                "outboxRecovery = appScope.launch {",
                // PARKED, not reverted: a row left EDITING by a process death is parked FAILED
                // with the interrupted-edit reason, and unfinished() below does not return FAILED,
                // so the re-arm never books it. Requeued instead, it leaves on its own at 0 and the
                // composer restored afterwards can send the same message twice.
                "mailRepository.parkInterruptedOutboxEdits()",
                "mailRepository.unfinishedOutbox().forEach { item ->",
                "val delay = (item.notBeforeMillis - System.currentTimeMillis()).coerceAtLeast(0)",
                "Outbox.enqueue(appContext, item.id, delay, keepExisting = true)",
                "}",
                "}",
            ),
            blockAt(lines, "mailRepository.parkInterruptedOutboxEdits()", before = 1, size = 7),
        )
    }

    @Test fun `no other call site keeps what is already booked`() {
        // This is the trap of the volet, and it is why the default stays REPLACE. `keepExisting`
        // spreading to the other two call sites is silent and total:
        //
        // TWO lines now, not one: the scheduled-send queue got the same startup re-arm, for the
        // same reason and with the same `keepExisting = true` (a REPLACE there cancels a
        val application = linesOf(APPLICATION)
        assertEquals(
            "only a startup re-arm may keep an existing booking (see the two damages above):",
            listOf(
                "Outbox.enqueue(appContext, item.id, delay, keepExisting = true)",
                ".forEach { ScheduledSends.enqueue(appContext, it.id, it.delayMillis, keepExisting = true) }",
            ),
            application.filter { "keepExisting" in it },
        )
        assertEquals(
            "the scheduler seam carries send and Retry, the newest instant there is — it replaces:",
            listOf("mailRepository.outboxScheduler = OutboxScheduler { id, delay -> Outbox.enqueue(appContext, id, delay) }"),
            blockAt(
                application,
                "mailRepository.outboxScheduler = OutboxScheduler { id, delay -> Outbox.enqueue(appContext, id, delay) }",
                before = 0, size = 1,
            ),
        )
        assertEquals(
            "⛔ the worker's HELD re-booking must not keep: it runs while its own unique work is " +
                "RUNNING, so a KEEP drops it and no scheduled send is ever delivered",
            emptyList<String>(),
            linesOf(WORKER).filter { "keepExisting" in it || "ExistingWorkPolicy" in it },
        )
    }

    private companion object {
        val SCHEDULER = repoFile("app/src/main/kotlin/app/sterna/send/Outbox.kt")
        val APPLICATION = repoFile("app/src/main/kotlin/app/sterna/SternaApplication.kt")
        val WORKER = repoFile("app/src/main/kotlin/app/sterna/send/OutboxWorker.kt")

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
