package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same disclaimer as [ConversationScopeWiringTest]: it reads
 */
class BulkOutcomeWiringTest {

    private fun bulkBatchedBody(): String {
        val source = INBOX_VIEW_MODEL.readText()
        val start = source.indexOf("private fun bulkBatched(")
        assertTrue("InboxViewModel has no bulkBatched() — renamed?", start >= 0)
        var depth = 0
        var i = source.indexOf('{', start)
        val bodyStart = i
        do {
            when (source[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        } while (depth > 0)
        return source.substring(bodyStart, i)
    }

    @Test fun theBulkToastGoesThroughTheOutcomeDecision() {
        val body = bulkBatchedBody()
        assertTrue(
            "bulkBatched must ask bulkOutcome() rather than re-deciding from failedKeys itself",
            "bulkOutcome(" in body,
        )
        // The counts it feeds it: the WHOLE selection handed in (plus whatever a delegating caller
        // already lost), and everything that did not go through — the batch's rejects, the keys
        assertEquals(
            "bulkOutcome must be fed the whole selection (plus the caller's losses) and every " +
                "failure, unresolved keys included",
            listOf(
                "val failed = failedKeys.size + resolved.unresolved.size + failedBefore",
                "when (bulkOutcome(attempted = targetKeys.size + attemptedBefore, failed = failed)) {",
            ),
            body.lines().map { it.replace(Regex("""\s+"""), " ").trim() }
                .filter { it.startsWith("val failed =") || it.startsWith("when (bulkOutcome(") },
        )
    }

    /**
     * WHOLE BRANCHES, not the first `R.string` each names. The total-failure branch no longer
     */
    @Test fun eachOutcomeReachesForItsOwnString() {
        assertEquals(
            "each outcome must reach for its own sentence, the total-failure branch pinned whole",
            mapOf(
                "NONE" to "Unit",
                "TOTAL" to "_message.value = app.getString(if (failure == null) " +
                    "R.string.status_action_failed else actionFailureMessage(failure, online = " +
                    "hasUsableNetwork(app)))",
                "PARTIAL" to "_message.value = getApplication<Application>()" +
                    ".getString(R.string.status_action_partly_failed)",
            ),
            outcomeBranches(),
        )
    }

    /**
     * The branches of `bulkBatched`'s `when (bulkOutcome(…))`, braces balanced so the last one
     */
    private fun outcomeBranches(): Map<String, String> {
        val body = bulkBatchedBody()
        val start = body.indexOf("when (bulkOutcome(")
        assertTrue("bulkBatched no longer decides through when (bulkOutcome(…)) — renamed?", start >= 0)
        var i = body.indexOf('{', start)
        val blockStart = i + 1
        var depth = 0
        do {
            when (body[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        } while (depth > 0)
        val block = body.substring(blockStart, i - 1).lines()
            .filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
        return Regex("""BulkOutcome\.(\w+)\s*->((?:(?!BulkOutcome\.)[\s\S])*)""")
            .findAll(block)
            .associate { m -> m.groupValues[1] to m.groupValues[2].trim().replace(Regex("""\s+"""), " ") }
    }

    private companion object {
        const val PATH = "app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt"

        val INBOX_VIEW_MODEL: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, PATH).isFile }
                ?.let { File(it, PATH) }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads a " +
                        "source file as text and needs a working directory inside the checkout",
                )
        }
    }
}
