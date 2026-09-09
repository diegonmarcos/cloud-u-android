package app.sterna.send

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT. `WorkManager` needs a `Context` and this module has neither Robolectric nor
 */
class ScheduledSendRearmWiringTest {

    private fun linesOf(file: File): List<String> =
        file.readText().lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") || it.isEmpty() }

    private fun blockAt(lines: List<String>, anchor: String, before: Int, size: Int): List<String> {
        val at = lines.indexOf(anchor)
        assertTrue("no line reads exactly:\n  $anchor\nthe file holds:\n" + lines.joinToString("\n"), at >= 0)
        return lines.subList(at - before, at - before + size)
    }

    @Test fun `the scheduled send's work item takes its policy from the caller, and replaces by default`() {
        val lines = linesOf(SCHEDULER)
        assertEquals(
            "the REPLACE/KEEP choice belongs to the CALLER, which is why the DEFAULT is pinned here " +
                "with the body: `keepExisting: Boolean = false` flipped to `= true` compiles, " +
                "changes no call site, and makes every booking a KEEP — the composer's own " +
                "scheduling would then be thrown away whenever a job for that row id is still " +
                "lying around, and the message would leave at the OLD hour or not at all. Flipped " +
                "the other way at the startup re-arm, a REPLACE cancels a ScheduledSendWorker that " +
                "is RUNNING between its two non-transactional writes: the outbox row is already " +
                "durable, `deleteScheduledSend` never runs (the `catch (_: Throwable)` swallows the " +
                "CancellationException), and the replacement job — delay zero, the hour being past " +
                "— deposits the message a SECOND time. The user's message goes out twice. " +
                "`initialDelayMillis.coerceAtLeast(0)` is the belt four composer KDocs promise in " +
                "so many words (\"coerces a negative delay to zero\"):",
            listOf(
                "fun enqueue(",
                "context: Context,",
                "id: Long,",
                // NO default: `enqueue(ctx, id)` must not compile. Made optional, dropping
                // the argument at the composer's call site sends every scheduled message AT ONCE.
                "initialDelayMillis: Long,",
                // THE DEFAULT IS THE RULE. Only the startup re-arm may keep; every other caller
                // carries the newest instant the user just picked and must replace.
                "keepExisting: Boolean = false,",
                ") {",
                "val request = OneTimeWorkRequestBuilder<ScheduledSendWorker>()",
                ".setInitialDelay(initialDelayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)",
                ".setInputData(workDataOf(ScheduledSendWorker.KEY_ID to id))",
                ".setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())",
                ".build()",
                "val policy = if (keepExisting) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE",
                "WorkManager.getInstance(context).enqueueUniqueWork(workName(id), policy, request)",
            ),
            blockAt(lines, "val request = OneTimeWorkRequestBuilder<ScheduledSendWorker>()", before = 6, size = 13),
        )
    }

    @Test fun `the startup re-arms every scheduled row, behind what is already there`() {
        // THE WHOLE BLOCK, not the enqueue line alone: `keepExisting = true` is an ARGUMENT, and
        // dropping it is the cheapest mutation of this volet — it compiles, it reads fine, and it
        // turns the safety net into the double-send described above. The `runCatching` is pinned
        // with it: an unreadable row would otherwise take the whole startup coroutine down.
        val lines = linesOf(APPLICATION)
        assertEquals(
            "a scheduled send waits for HOURS, which is exactly how long it has to lose the " +
                "WorkManager job that was going to fire it — they are two different files, and the " +
                "composer writes the row and books the job as two operations. Nothing else ever " +
                "asks about that row: without this block it " +
                "stays on the \"Scheduled\" screen with an hour in the past and the message never " +
                "leaves. And it books BEHIND what WorkManager may already have restored — a " +
                "REPLACE cancels a worker mid-flight between the outbox deposit and the row " +
                "deletion, and the message is sent twice:",
            listOf(
                "appScope.launch {",
                "runCatching {",
                "scheduledSendJobs(mailRepository.scheduledSends(), System.currentTimeMillis())",
                ".forEach { ScheduledSends.enqueue(appContext, it.id, it.delayMillis, keepExisting = true) }",
                "}.onFailure { Log.w(\"Sterna\", \"scheduled-send re-arm failed; retried next start\", it) }",
                "}",
            ),
            blockAt(
                lines,
                "scheduledSendJobs(mailRepository.scheduledSends(), System.currentTimeMillis())",
                before = 2, size = 6,
            ),
        )
        // AND THE BLOCK IS STILL INSIDE `init {`. The assertion above proves the six lines exist
        // in the file, never that anything runs them: lifted verbatim into a `private fun` that
        val initAt = lines.indexOf("init {")
        val reArmAt = lines.indexOf("scheduledSendJobs(mailRepository.scheduledSends(), System.currentTimeMillis())")
        assertTrue("the re-arm must sit inside AppContainer's `init {`, which is what runs it", initAt in 0 until reArmAt)
        assertEquals(
            "no function declaration may sit between `init {` and the re-arm: one there means the " +
                "block was moved out of the constructor into something that has to be CALLED, and " +
                "nothing calls it — the scheduled sends are never re-armed again",
            emptyList<String>(),
            lines.subList(initAt, reArmAt).filter {
                it.startsWith("fun ") || it.startsWith("private fun ") || it.startsWith("internal fun ")
            },
        )
    }

    @Test fun `the composer books its scheduled send with the hour the user picked`() {
        // The call site, whole line and arguments included. This volet moved the delay out of
        // `ScheduledSends.enqueue` and into the seam `core:data` executes, which is what lets a JVM
        assertEquals(
            "the composer must convert the instant the user picked into a delay, through the same " +
                "coerced seam the startup re-arm uses, and must NOT keep an existing booking — a " +
                "re-scheduled message has to replace the hour that is still booked under its id:",
            listOf(
                "ScheduledSends.enqueue(getApplication(), id, scheduledSendDelayMillis(sendAtMillis, System.currentTimeMillis()))",
            ),
            linesOf(COMPOSER).filter { "ScheduledSends.enqueue(" in it },
        )
    }

    @Test fun `the scheduled-send worker names no policy of its own`() {
        // The worker's only defence against firing twice is `repo.scheduledSend(id) ?: return
        // Result.success()`. It books nothing and must keep booking nothing: a KEEP or a REPLACE
        // appearing in there would mean the fire path had started to re-book itself, and every
        // reasoning above about who may keep would be out of date without a line changing here.
        assertEquals(
            "only the seam and the startup re-arm decide a work policy; the worker fires and deletes",
            emptyList<String>(),
            linesOf(WORKER).filter { "keepExisting" in it || "ExistingWorkPolicy" in it },
        )
    }

    private companion object {
        val SCHEDULER = repoFile("app/src/main/kotlin/app/sterna/send/ScheduledSends.kt")
        val APPLICATION = repoFile("app/src/main/kotlin/app/sterna/SternaApplication.kt")
        val WORKER = repoFile("app/src/main/kotlin/app/sterna/send/ScheduledSendWorker.kt")
        val COMPOSER = repoFile("app/src/main/kotlin/app/sterna/ui/compose/ComposeViewModel.kt")

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
