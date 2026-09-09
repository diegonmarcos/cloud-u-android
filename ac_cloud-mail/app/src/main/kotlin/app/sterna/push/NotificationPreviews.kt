package app.sterna.push

import app.sterna.core.data.mail.PreviewSource
import app.sterna.core.data.settings.NotificationContent
import kotlinx.coroutines.CancellationException

/**
 * WHEN a notification pass may read the opening of a message, HOW MANY it may read and for HOW LONG
 */
object NotificationPreviews {

    /** The wall clock for ONE ACCOUNT'S WHOLE PASS, every watched folder included. Four seconds:
     *  this runs while mail already arrived waits to be announced, and a late notification is worth
     *  less than one with no opening line. */
    const val BUDGET_MS = 4_000

    /** How many messages ONE ACCOUNT'S PASS may read, its folders sharing the count. Six, which is
     *  how many children the group summary lists: past that the reader is looking at the summary. */
    const val MAX_PER_PASS = 6

    /**
     * The six reads and the four seconds of ONE ACCOUNT'S PASS, held in one place so its FOLDERS
     */
    class PreviewBudget(private val nowMs: () -> Long) {
        private var deadline: Long? = null
        private var reads: Int = MAX_PER_PASS

        /** The deadline the next read may have, or null when this pass may read no more. Never
         *  zero and never negative: `budgetMs = 0` means block until the OS gives up. */
        fun claim(): Int? {
            if (reads <= 0) return null
            val end = deadline ?: (nowMs() + BUDGET_MS).also { deadline = it }
            val left = (end - nowMs()).coerceAtMost(BUDGET_MS.toLong())
            if (left <= 0L) return null
            reads--
            return left.toInt()
        }
    }

    /**
     * The openings this pass may show, keyed by message id, as far as [MAX_PER_PASS] and [BUDGET_MS]
     */
    suspend fun gather(
        content: NotificationContent,
        ids: List<String>,
        sources: Map<String, PreviewSource>,
        budget: PreviewBudget,
        numbering: suspend () -> Long?,
        fetch: suspend (PreviewSource, Int) -> String?,
    ): Map<String, String> {
        if (content != NotificationContent.BODY_PREVIEW) return emptyMap()
        val targets = ids.mapNotNull { id -> sources[id]?.let { id to it } }
        if (targets.isEmpty()) return emptyMap()
        val numberedAtStart = rescued { numbering() } ?: return emptyMap()
        val gathered = LinkedHashMap<String, String>()
        for ((id, source) in targets) {
            // What the ACCOUNT'S pass has left, so a second watched folder cannot start afresh.
            val budgetMs = budget.claim() ?: break
            rescued { fetch(source, budgetMs) }?.let { gathered[id] = it }
            if (rescued { numbering() } != numberedAtStart) break
        }
        return gathered
    }

    /**
     * Post [mail], each with the opening line [gather] found for it — ALL of them gathered BEFORE
     */
    suspend fun <T> postGatheredFirst(
        mail: List<T>,
        keyOf: (T) -> String,
        gather: suspend () -> Map<String, String>,
        post: (T, String?) -> Unit,
    ) {
        val gathered = rescued { gather() }.orEmpty()
        mail.forEach { post(it, gathered[keyOf(it)]) }
    }

    /** [block]'s answer, or null if it failed. A cancellation is not a failure — it means the pass
     *  itself is gone — and goes through, or a cancelled push would carry on notifying. */
    private suspend fun <T> rescued(block: suspend () -> T?): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failed: Throwable) {
        null
    }
}
