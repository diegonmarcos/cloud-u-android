package app.sterna.send

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, and the twin of `OutboxParkWiringTest` — which stops at the 33rd line of
 */
class OutboxPermanentFailureWiringTest {

    private fun linesOf(file: File): List<String> =
        file.readText().lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") || it.isEmpty() }

    private fun blockAt(lines: List<String>, anchor: String, before: Int, size: Int): List<String> {
        val at = lines.indexOf(anchor)
        assertTrue("no line reads exactly:\n  $anchor\nthe file holds:\n" + lines.joinToString("\n"), at >= 0)
        return lines.subList(at - before, at - before + size)
    }

    @Test fun `a failed attempt asks OutboxLogic what state the row goes to, and obeys the answer`() {
        val lines = linesOf(WORKER)
        assertEquals(
            "⛔ the worker must hand the FAILURE ITSELF to OutboxLogic.attemptsAfterFailure and " +
                "OutboxLogic.stateAfterFailure, and write back what they answer. " +
                "back whatever it answers. Deciding here on the attempt count alone — the " +
                "`if (OutboxLogic.shouldRetry(attempts))` this replaced — ignores the one thing " +
                "the server told us: that it REFUSED this delivery. Retrying a refusal cannot " +
                "change the verdict, and it is not free: the message is already filed in Sent, so " +
                "each retry adds a copy there, and on a partial refusal it is delivered again to " +
                "the recipient whose address was right all along (#183). The Result must be " +
                "DERIVED from that state too: a retry() written next to a FAILED row brings " +
                "WorkManager back on a row that is parked:",
            listOf(
                "} catch (t: Throwable) {",
                // THE COUNT IS A GUARD, not bookkeeping. Written as `item.attemptCount + 1` a
                // refused row is parked FAILED with 1 attempt, and OutboxLogic.stateAfterEdit then
                "val attempts = OutboxLogic.attemptsAfterFailure(item.attemptCount, t)",
                // The message, and only the message. It is persisted in lastError and printed on
                // screen; the exception's own text is the address plus the server's SMTP reply.
                "val error = t.message ?: t.javaClass.simpleName",
                "val next = OutboxLogic.stateAfterFailure(attempts, t)",
                "repo.updateOutboxState(id, next, attempts, error)",
                "if (next == OutboxState.QUEUED) Result.retry() else Result.failure()",
                "}",
            ),
            blockAt(lines, "} catch (t: Throwable) {", before = 0, size = 7),
        )
        assertEquals(
            "⛔ and the attempt cap must not be consulted here at all. OutboxLogic.shouldRetry is " +
                "still the rule for an ordinary failure, but it is stateAfterFailure that applies " +
                "it — a cap read in the worker is a second decision that cannot see the refusal",
            emptyList<String>(),
            lines.filter { "shouldRetry" in it },
        )
    }

    private companion object {
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
