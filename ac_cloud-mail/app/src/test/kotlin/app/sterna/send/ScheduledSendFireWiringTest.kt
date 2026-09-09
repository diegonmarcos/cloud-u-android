package app.sterna.send

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, same shape and same reason as [OutboxParkWiringTest]. `ScheduledSendWorker` is a
 */
class ScheduledSendFireWiringTest {

    private fun linesOf(file: File): List<String> =
        file.readText().lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") || it.isEmpty() }

    private fun blockAt(lines: List<String>, anchor: String, before: Int, size: Int): List<String> {
        val at = lines.indexOf(anchor)
        assertTrue("no line reads exactly:\n  $anchor\nthe file holds:\n" + lines.joinToString("\n"), at >= 0)
        return lines.subList(at - before, at - before + size)
    }

    @Test fun `a scheduled row is destroyed only on a proven departure, and kept armed otherwise`() {
        assertEquals(
            "⛔ The scheduled row is the ONLY copy of the text — ComposeViewModel.scheduleSend " +
                "writes no draft — and a null from credentials() is not proof that the account " +
                "left: an unreadable secret, a pw_ slot never written for a linked sub-account, a " +
                "slot withdrawn on a live account and an unreadable blob all answer null too. So " +
                "the departure is PROVEN through accountDepartureIsProven before anything is " +
                "deleted, and the unproven arm writes nothing at all. ⛔ retry(), not success(): " +
                "the Scheduled screen is still announcing this send, and success() would leave the " +
                "row on screen with no job behind it — a promise that can never be kept. ⛔ And a " +
                "bare retry(), with no attemptCount around it: MAX_ATTEMPTS counts DELIVERY " +
                "attempts and nothing has been attempted here.",
            listOf(
                // The idempotence guard the arm below sits under: a row already fired or cancelled
                // is gone, and this worker must then do nothing at all.
                "val row = repo.scheduledSend(id) ?: return Result.success() // already fired or cancelled",
                "val credentials = container.accountStore.credentials(row.accountId) ?: run {",
                // Both halves, handed to the one place a JVM test can execute the rule. Dropping
                // the first one makes an unreadable blob read as "every account left at once".
                "val departed = accountDepartureIsProven(",
                "accountsUnreadable = container.accountStore.accountsUnreadable(),",
                "accountStillListed = container.accountStore.accounts().any { it.id == row.accountId },",
                ")",
                "if (departed) {",
                "repo.deleteScheduledSend(id)",
                "return Result.success()",
                "}",
                "return Result.retry()",
                "}",
            ),
            blockAt(
                linesOf(WORKER),
                "val credentials = container.accountStore.credentials(row.accountId) ?: run {",
                before = 1,
                size = 12,
            ),
        )
    }

    /**
     * Its own test, and the one that closes the window. The block above pins TWELVE lines around
     */
    @Test fun `the row is destroyed in three places, and nowhere else`() {
        assertEquals(
            "⛔ a destruction of the scheduled row was added, moved or removed. This row is the " +
                "ONLY copy of the message: the proven-departure arm, the hand-over to the outbox " +
                "and the give-up in the catch are the three places allowed to destroy it, in that " +
                "order. Anything else destroys a message the user is still waiting to see leave",
            listOf(
                // 1. Proven departure: nothing can ever send it, so nothing is being lost.
                "repo.deleteScheduledSend(id)",
                // 2. AFTER enqueueSend returned — the outbox now holds the copy. Moved above the
                // enqueue, a throw would leave neither a scheduled row nor an outbox item.
                "repo.deleteScheduledSend(id)",
                // 3. Retries exhausted, and only after the banner has named the failure (#57).
                "repo.deleteScheduledSend(id)",
            ),
            linesOf(WORKER).filter { "repo.deleteScheduledSend(" in it },
        )
    }

    /**
     * Counting the sites is not enough, and its own test says why. The three lines above are
     */
    @Test fun `only the proven-departure arm may destroy the row before the hand-over`() {
        val lines = linesOf(WORKER)
        val handOver = lines.indexOfFirst { it == "repo.enqueueSend(" }
        assertTrue("no line reads exactly 'repo.enqueueSend(' any more", handOver >= 0)

        assertEquals(
            "⛔ a destruction of the scheduled row now stands between the account check and " +
                "enqueueSend. The row is the ONLY copy of the message: destroyed before the outbox " +
                "holds it, a throw from enqueueSend loses it outright — the catch retries, the " +
                "next run finds no row and returns success(), and the user is told nothing at all",
            1,
            lines.take(handOver).count { "repo.deleteScheduledSend(" in it },
        )
    }

    /** Its own test: a failed assertion ends its method, so one rule would hide the other. */
    @Test fun `the worker restates no departure rule of its own`() {
        assertEquals(
            "⛔ the verdict must be READ from the store and handed to accountDepartureIsProven, " +
                "never recombined here: an expression written in this file is a rule nothing in " +
                "this repository can run, and it is the copy that drifts",
            listOf("accountsUnreadable = container.accountStore.accountsUnreadable(),"),
            linesOf(WORKER).filter { "accountsUnreadable" in it },
        )
    }

    private companion object {
        val WORKER = repoFile("app/src/main/kotlin/app/sterna/send/ScheduledSendWorker.kt")

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
