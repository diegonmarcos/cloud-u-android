package app.sterna.ui.inbox

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST. It reads `InboxViewModel.kt` as text and proves nothing about
 */
class ActionFailureWiringTest {

    @Test fun `the swipe's failure arm is pinned whole`() {
        assertEquals(
            "swipeRemove's onFailure must log the throwable, show the sentence the shared decision " +
                "picks, put the row back in the search results and reconcile the list — in that " +
                "order and nothing else. The `refresh()` and `restoreSearchResults` calls are part " +
                "of the pin on purpose: the failed row was never dropped locally, and dropping " +
                "either leaves the list lying about what is on the server.",
            SWIPE_ARM, arm("private fun swipeRemove("),
        )
    }

    @Test fun `the undo's failure arm is pinned whole`() {
        assertEquals(
            "undo()'s onFailure must count the entries it could not restore, log the throwable and " +
                "put a NON-NULL sentence in the banner. `error = it.message` there was the defect: " +
                "a transport exception's message is raw English, and a null one is " +
                "RefreshNotice.NONE — a banner saying nothing at all.",
            UNDO_ARM, arm("fun undo()"),
        )
    }

    @Test fun `no arm puts the exception's own text on screen`() {
        for (site in SITES) {
            val text = arm(site)
            assertFalse(
                "${site.name} must not read `it.message` at all: that string is the platform " +
                    "resolver's, in English whatever the app's language, and it is exactly what " +
                    "this fix takes off the screen. Arm was:\n$text",
                READS_THE_MESSAGE.containsMatchIn(text),
            )
        }
    }

    @Test fun `every site asks the shared decision, and nowhere else does`() {
        val calls = Regex("""(?<!fun )\bactionFailureMessage\(""").findAll(code()).count()
        assertEquals(
            "actionFailureMessage must be CALLED exactly three times in InboxViewModel — the " +
                "swipe, the Undo and the batched bulk's total failure. A fourth caller is another " +
                "site quietly changing its wording through this one; fewer means one of the three " +
                "went back to its own rule. Found $calls.",
            3, calls,
        )
        for (site in SITES) {
            val text = arm(site)
            assertTrue(
                "${site.name} must call actionFailureMessage(${site.failure}, online = " +
                    "hasUsableNetwork(app)) — the failure itself and the device's LIVE " +
                    "connectivity reading. Arm was:\n$text",
                decidesOnLiveConnectivity(site).containsMatchIn(text),
            )
        }
    }

    @Test fun `no site hard-codes whether the device is online`() {
        for (site in SITES) {
            val text = arm(site)
            assertFalse(
                "${site.name} must not pass a constant for `online`. `online = true` is one word, " +
                    "compiles, and gives every offline swipe the generic sentence again — with " +
                    "the whole suite green if the rule only looked for the call by name. Arm " +
                    "was:\n$text",
                CONSTANT_CONNECTIVITY.containsMatchIn(text),
            )
        }
    }

    /**
     * The batched bulk loops over ACCOUNTS, so it can be handed several failures. The one that
     */
    @Test fun `the batched bulk keeps the first failure, not the last`() {
        val site = SITES.first { it.cut == Cut.CAUGHT_THEN_TOLD }
        val text = arm(site)
        assertTrue(
            "${site.name} must keep the first escaped throwable and never overwrite it. Arm " +
                "was:\n$text",
            KEEPS_THE_FIRST_FAILURE.containsMatchIn(text),
        )
    }

    @Test fun `the undo banner can never be left empty`() {
        val text = arm("fun undo()")
        assertTrue(
            "undo()'s banner must be fed a resolved string resource. `error = null`, `error = " +
                "it.message` or any expression that can answer null is RefreshNotice.NONE, and " +
                "the banner then carries nothing at all about an Undo that did not happen. Arm " +
                "was:\n$text",
            NON_NULL_BANNER.containsMatchIn(text),
        )
    }

    @Test fun `every site writes the technical text to the log, with the throwable`() {
        for (site in SITES) {
            val text = arm(site)
            assertTrue(
                "${site.name} must call android.util.Log.w(tag, message, it), passing the THROWABLE as " +
                    "the third argument — not `it.message`, or the stack trace that says which " +
                    "layer failed is gone from the bug report too. The text is not lost by this " +
                    "fix, it changes surface. Arm was:\n$text",
                LOGS_THE_THROWABLE.containsMatchIn(text),
            )
        }
    }

    /** Witness, deliberately out of scope: the twin that was already right must not have moved. */
    @Test fun `the folded-thread twin still shows the generic sentence`() {
        assertTrue(
            "threadSwipeRemove was already showing a translated status_action_failed — it is not " +
                "part of this fix and must stay exactly as it was.",
            TWIN_UNTOUCHED.containsMatchIn(code()),
        )
    }

    // -- reading the source ------------------------------------------------------------------------

    /**
     * The `.onFailure { … }` block of the declaration starting at [header], comments stripped and
     */
    private fun arm(site: Site): String = when (site.cut) {
        Cut.ON_FAILURE -> arm(site.header)
        Cut.CAUGHT_THEN_TOLD -> caughtThenTold(site.header)
    }

    /**
     * The two halves of the batched bulk's arm, joined: the `getOrElse { … }` that logs the
     */
    private fun caughtThenTold(header: String): String {
        val text = code()
        val start = text.indexOf(header)
        check(start >= 0) { "InboxViewModel.kt declares no '$header' — did it get renamed?" }
        val declaration = balanced(text, start, '{', '}')
        val caught = declaration.indexOf(".getOrElse {")
        check(caught >= 0) { "'$header' no longer catches its batch failure in a getOrElse arm." }
        check(declaration.indexOf(".getOrElse {", caught + 1) < 0) {
            "'$header' now has more than one getOrElse arm; this rule pins one."
        }
        val told = declaration.indexOf("BulkOutcome.TOTAL ->")
        check(told >= 0) { "'$header' has no BulkOutcome.TOTAL branch — where did the message go?" }
        val end = declaration.indexOf("BulkOutcome.", told + 1).let {
            if (it < 0) declaration.length else it
        }
        return flat(balanced(declaration, caught, '{', '}')) + " " + flat(declaration.substring(told, end))
    }

    private fun arm(header: String): String {
        val text = code()
        val start = text.indexOf(header)
        check(start >= 0) { "InboxViewModel.kt declares no '$header' — did it get renamed?" }
        val declaration = balanced(text, start, '{', '}')
        val onFailure = declaration.indexOf(".onFailure {")
        check(onFailure >= 0) { "'$header' has no .onFailure arm any more — where did the failure go?" }
        val second = declaration.indexOf(".onFailure {", onFailure + 1)
        check(second < 0) { "'$header' now has more than one .onFailure arm; this rule pins one." }
        return flat(balanced(declaration, onFailure, '{', '}'))
    }

    /** [text] with every run of whitespace squeezed to one space, so a wrapped call reads as one. */
    private fun flat(text: String): String = text.trim().replace(Regex("\\s+"), " ")

    /** [text] from the first [open] at or after [from], up to the [close] that balances it. */
    private fun balanced(text: String, from: Int, open: Char, close: Char): String {
        val start = text.indexOf(open, from).let { if (it < 0) from else it + 1 }
        var depth = 1
        var i = start
        while (i < text.length && depth > 0) {
            when (text[i]) {
                open -> depth++
                close -> depth--
            }
            i++
        }
        return text.substring(start, (i - 1).coerceAtLeast(start)).trim()
    }

    /**
     * The file's code as one string, comments taken off: a line whose first non-blank character
     */
    private fun code(): String = INBOX_VIEW_MODEL.readLines().mapNotNull { line ->
        val start = line.trimStart()
        if (start.startsWith("//") || start.startsWith("*") || start.startsWith("/*")) null
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

    companion object {

        /** The three arms this fix owns, by the declaration each lives in. */
        private val SITES = listOf(
            Site("swipeRemove", "private fun swipeRemove(", Cut.ON_FAILURE, "it"),
            Site("undo()", "fun undo()", Cut.ON_FAILURE, "it"),
            Site("bulkBatched()", "private fun bulkBatched(", Cut.CAUGHT_THEN_TOLD, "failure"),
        )

        /**
         * `actionFailureMessage(<this arm's throwable>, online = hasUsableNetwork(app))` — and, on
         */
        private fun decidesOnLiveConnectivity(site: Site) = Regex(
            """\bactionFailureMessage\(${Regex.escape(site.failure)}, online = hasUsableNetwork\(app\)\)"""
                .let { call -> if (site.cut == Cut.ON_FAILURE) """app\.getString\($call\)""" else call },
        )

        /**
         * The capture that survives a second failing account. ONE spelling, not an alternation:
         */
        private val KEEPS_THE_FIRST_FAILURE = Regex("""if \(batchFailure == null\) batchFailure = it\b""")

        private const val DECISION =
            """app\.getString\(actionFailureMessage\(it, online = hasUsableNetwork\(app\)\)\)"""

        /**
         * The last two lines before `refresh()` are one pair: the row goes back into the search
         */
        private val SWIPE_ARM =
            "val app = getApplication<Application>() " +
                "android.util.Log.w(\"SternaInbox\", \"swipe action failed\", it) " +
                "_message.value = app.getString(actionFailureMessage(it, online = hasUsableNetwork(app))) " +
                "restoreSearchResults(listOf(email.emailKey())) " +
                "_swipeRewind.value = _swipeRewind.value + email.emailKey() " +
                "refresh()"

        private val UNDO_ARM =
            "unrestored += entries.size " +
                "val app = getApplication<Application>() " +
                "android.util.Log.w(\"SternaInbox\", \"undo restore failed\", it) " +
                "status.value = Status(refreshing = false, " +
                "error = app.getString(actionFailureMessage(it, online = hasUsableNetwork(app))))"

        /** Any read of the exception's own text — the thing that used to reach the screen. */
        private val READS_THE_MESSAGE = Regex("""\bit\.message\b""")

        /** `online = true` / `online = false`, and the two literals on their own anywhere in the arm. */
        private val CONSTANT_CONNECTIVITY = Regex("""online\s*=\s*(true|false)\b""")

        /** The banner is a resolved resource, never an expression that can answer null. */
        private val NON_NULL_BANNER = Regex("""error = $DECISION\)""")

        private val LOGS_THE_THROWABLE = Regex("""android\.util\.Log\.w\("[^"]+", "[^"]+", it\)""")

        /** The folded-thread twin, unchanged: one translated sentence, then a reconcile. */
        private val TWIN_UNTOUCHED = Regex(
            """_message\.value = getApplication<Application>\(\)\.getString\(R\.string\.status_action_failed\)\s*""" +
                """refresh\(\)""",
        )

        private const val INBOX_VIEW_MODEL_PATH =
            "app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt"

        /** Repo root, walked up from the module's working directory. */
        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, INBOX_VIEW_MODEL_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the source as text and needs a working directory inside the checkout",
                )
        }

        private val INBOX_VIEW_MODEL: File by lazy { File(root, INBOX_VIEW_MODEL_PATH) }
    }
}

/**
 * One failure arm this fix owns: where it lives, how it is cut out of the source, and the name the
 */
private data class Site(val name: String, val header: String, val cut: Cut, val failure: String)

/** How a site's arm is cut out of its declaration. */
private enum class Cut {
    /** The declaration's single `.onFailure { … }` block. */
    ON_FAILURE,

    /** Its `getOrElse { … }` and its `BulkOutcome.TOTAL ->` branch, joined. */
    CAUGHT_THEN_TOLD,
}
