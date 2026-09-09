package app.sterna.ui.compose

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A composer rebuilt after a process death taking its queued row back — [resumeOutboxRow] EXECUTED
 */
class OutboxResumeTest {

    private enum class State { QUEUED, EDITING, FAILED, SENDING }

    private class Bench(initial: Map<Long, State>) {
        val rows = initial.toMutableMap()
        val rearmed = mutableListOf<Long>()
        /** Every call, in the order it ran — the ORDER is the verdict. */
        val calls = mutableListOf<String>()

        fun sweep() {
            calls += "sweep"
            for ((id, state) in rows) if (state == State.EDITING) rows[id] = State.FAILED
            rows.filterValues { it == State.QUEUED }.keys.forEach { rearmed += it }
        }

        fun take(id: Long): String? {
            calls += "take($id)"
            val state = rows[id] ?: return null
            if (state == State.EDITING || state == State.SENDING) return null
            rows[id] = State.EDITING
            return "row $id"
        }
    }

    @Test fun `the sweep already over, the row is taken back and comes back EDITING`() = runTest {
        val bench = Bench(mapOf(7L to State.EDITING))
        val recovery = launch { bench.sweep() }
        recovery.join()
        assertEquals(listOf("sweep"), bench.calls)

        val taken = resumeOutboxRow(7L, recovery) { bench.take(it) }

        assertEquals("row 7", taken)
        assertEquals(State.EDITING, bench.rows[7L])
        assertEquals("nothing may be re-armed under an open composer", emptyList<Long>(), bench.rearmed)
        assertEquals(listOf("sweep", "take(7)"), bench.calls)
    }

    @Test fun `the resume started first, the sweep still running, the take waits for it`() = runTest {
        val bench = Bench(mapOf(7L to State.EDITING))
        val sweepMayRun = CompletableDeferred<Unit>()
        val recovery = launch { sweepMayRun.await(); bench.sweep() }

        val resume = async { resumeOutboxRow(7L, recovery) { bench.take(it) } }
        runCurrent()
        assertEquals(
            "⛔ the take ran while the startup sweep was still ahead of it: the row it just marked " +
                "EDITING is about to be parked FAILED under the open composer",
            emptyList<String>(),
            bench.calls,
        )

        sweepMayRun.complete(Unit)
        assertEquals("row 7", resume.await())
        assertEquals(State.EDITING, bench.rows[7L])
        assertEquals(emptyList<Long>(), bench.rearmed)
        assertEquals("the take runs ONCE, and after the sweep", listOf("sweep", "take(7)"), bench.calls)
    }

    @Test fun `a sweep that crashed does not hold the resume hostage`() = runTest {
        val bench = Bench(mapOf(7L to State.FAILED))
        // A scope of its own, its failure swallowed by its handler, so the crash reaches neither
        // the test scope nor runTest's collector: `join()` returns on a failed Job like on a
        // completed one, which is the guarantee this test pins.
        val recovery = CoroutineScope(Job() + CoroutineExceptionHandler { _, _ -> })
            .launch { error("sweep crashed") }

        assertEquals("row 7", resumeOutboxRow(7L, recovery) { bench.take(it) })
        assertEquals(State.EDITING, bench.rows[7L])
    }

    @Test fun `a row that is gone, or not reopenable, resumes nothing`() = runTest {
        val bench = Bench(mapOf(7L to State.SENDING))
        val recovery = launch { bench.sweep() }

        assertNull(resumeOutboxRow(7L, recovery) { bench.take(it) })
        assertNull(resumeOutboxRow(8L, recovery) { bench.take(it) })
        assertEquals(State.SENDING, bench.rows[7L])
    }
}
