package app.sterna.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST. It reads the navigation sources as text and checks the
 */
class NavHostSourceRulesTest {

    /**
     * Applies to EVERY file that hosts a NavHost, not just the two that exist today — a third
     */
    @Test
    fun `every navigation action in a NavHost file goes through the shared guard`() {
        val offenders = mainSources()
            .filter { "NavHost(" in it.readText() }
            .flatMap { file ->
                val lines = file.readLines()
                lines.indices
                    .filter { lines[it].isNavAction() && !lines.isGuarded(it) && !lines.isExempt(it) }
                    .map { "${file.name}:${it + 1}" }
            }
        assertTrue(
            "navigation actions neither guarded by navigateOnce nor marked '$EXEMPT': $offenders",
            offenders.isEmpty(),
        )
    }

    /**
     * Leaving for another app is the other half of the problem [navigateOnce] solves, and the
     */
    @Test
    fun `every hand-off to another app goes through the leave guard`() {
        val sources = mainSources().map { it to it.readLines() }
        val handOffs = listOf(RAW_HAND_OFF) + sources.openerCalls()
        val offenders = sources.flatMap { (file, lines) ->
            lines.indices
                .filter { i ->
                    lines[i].isAppHandOff(handOffs) &&
                        !lines.isInsideLeaveGuard(i) &&
                        !lines.reportsWhetherItLeft(i) &&
                        !lines.isExempt(i)
                }
                .map { "${file.name}:${it + 1}" }
        }
        assertTrue(
            "hand-offs to another app neither guarded by $LEAVE_GUARD nor marked '$EXEMPT': $offenders",
            offenders.isEmpty(),
        )
    }

    /**
     * The openers the app has written for itself, read out of the sources rather than named here: a
     */
    private fun List<Pair<File, List<String>>>.openerCalls(): List<String> = flatMap { (_, lines) ->
        lines.indices
            .filter { lines[it].isAppHandOff(listOf(RAW_HAND_OFF)) && lines.reportsWhetherItLeft(it) }
            .mapNotNull { lines.enclosingFunctionName(it) }
    }.distinct().map { "$it(" }

    @Test
    fun `the resumed-entry check lives in exactly one place`() {
        val offenders = mainSources()
            .filter { it.name != "NavGuard.kt" && "Lifecycle.State.RESUMED" in it.readText() }
            .map { it.name }
        assertTrue("hand-rolled copy of the navigation guard: $offenders", offenders.isEmpty())
    }

    /** A real assertion, not lint: DESIGN.md caps every animation in the app at 250 ms. */
    @Test
    fun `the screen slide obeys the design cap`() {
        assertTrue("DESIGN.md caps motion at 250 ms, got $SCREEN_SLIDE_MS", SCREEN_SLIDE_MS <= 250)
    }

    @Test
    fun `no navigation transition is written longer than the design cap`() {
        val tooLong = navGraphs().flatMap { file ->
            Regex("""tween\((\d+)""").findAll(file.readText())
                .map { file.name to it.groupValues[1].toInt() }
                .filter { (_, ms) -> ms > 250 }
                .toList()
        }
        assertTrue("transition over DESIGN.md's 250 ms cap: $tooLong", tooLong.isEmpty())
    }

    @Test
    fun `every navigation transition falls back to a static state when the system asks`() {
        val motion = listOf("slideInHorizontally(", "slideOutHorizontally(", "fadeIn(", "fadeOut(")
        val ungated = navGraphs().flatMap { file ->
            file.readLines().mapIndexed { i, line -> Triple(file.name, i + 1, line) }
        }.filter { (_, _, line) ->
            motion.any { it in line } && "motionEnabled" !in line
        }
        assertTrue("motion not gated on the reduced-motion setting: $ungated", ungated.isEmpty())
    }

    /**
     * The reader's fade must test the crash sentinel FIRST: on devices latched after the #10
     * GL-functor SIGSEGV, a running fade over a hardware WebView is a hard crash, not a taste.
     */
    @Test
    fun `the crash sentinel still short-circuits the reader fade`() {
        val text = source("app/sterna/ui/SternaApp.kt").readText()
        assertTrue(
            "the #10 latch must come first in both reader fade lambdas",
            "if (messageFadeDisabled || !motionEnabled) EnterTransition.None" in text &&
                "if (messageFadeDisabled || !motionEnabled) ExitTransition.None" in text,
        )
    }

    private fun navGraphs() = listOf(
        source("app/sterna/ui/SternaApp.kt"),
        source("app/sterna/ui/settings/SettingsScreen.kt"),
    )

    private fun String.isNavAction() = "nav.navigate(" in this || "nav.popBackStack(" in this

    /**
     * A line that hands something to another app, in code — the prose about it does not count, and
     */
    private fun String.isAppHandOff(handOffs: List<String>): Boolean {
        val code = trimStart()
        if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) return false
        return handOffs.any { it in code && "fun $it" !in code }
    }

    /**
     * Guarded by the opener on this line or by any block enclosing it. Walks strictly outwards by
     */
    private fun List<String>.isInsideLeaveGuard(index: Int): Boolean {
        if (LEAVE_GUARD in this[index]) return true
        var indent = this[index].indentWidth()
        for (i in index - 1 downTo 0) {
            val candidate = this[i]
            if (candidate.isBlank() || candidate.indentWidth() >= indent) continue
            if (LEAVE_GUARD in candidate) return true
            indent = candidate.indentWidth()
        }
        return false
    }

    /**
     * The line sits in a helper that answers "did we actually leave?" — the shape the guard needs,
     */
    private fun List<String>.reportsWhetherItLeft(index: Int): Boolean {
        for (i in index downTo 0) {
            if (!FUN_DECLARATION.containsMatchIn(this[i])) continue
            val signature = StringBuilder()
            for (j in i..index) {
                signature.append(this[j])
                val code = this[j].trimEnd()
                if (code.endsWith("{") || code.endsWith("=")) break
            }
            return ": Boolean" in signature.toString()
        }
        return false
    }

    /** The name of the function the line belongs to, for naming an opener after finding one. */
    private fun List<String>.enclosingFunctionName(index: Int): String? {
        for (i in index downTo 0) {
            FUN_DECLARATION.find(this[i])?.let { return it.groupValues[3] }
        }
        return null
    }

    /**
     * Guarded either on the same line, or by the enclosing block: walking up to the first line
     */
    private fun List<String>.isGuarded(index: Int): Boolean {
        val line = this[index]
        if (GUARD in line.substringBefore("nav.")) return true
        val indent = line.indentWidth()
        for (i in index - 1 downTo 0) {
            val candidate = this[i]
            if (candidate.isBlank() || candidate.indentWidth() >= indent) continue
            return GUARD in candidate
        }
        return false
    }

    /** Opted out on the action's own line, or in the contiguous comment block above it. */
    private fun List<String>.isExempt(index: Int): Boolean {
        if (EXEMPT in this[index]) return true
        for (i in index - 1 downTo 0) {
            if (!this[i].trimStart().startsWith("//")) return false
            if (EXEMPT in this[i]) return true
        }
        return false
    }

    private fun String.indentWidth() = length - trimStart().length

    companion object {
        private const val GUARD = "navigateOnce {"
        private const val LEAVE_GUARD = "leaveOnce {"
        private const val EXEMPT = "unguarded:"

        /** The hand-off everything else is written around, and the seed the openers are found from. */
        private const val RAW_HAND_OFF = "startActivity("

        /** A declaration, and its name in group 3 — the enclosing function of a line of body. */
        private val FUN_DECLARATION = Regex("""^\s*(private |internal |public )?(suspend )?fun (\w+)""")

        /** Repo root, found by walking up from the module's working directory. */
        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "app/src/main/kotlin/app/sterna/ui/SternaApp.kt").isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        fun source(relative: String): File = File(root, "app/src/main/kotlin/$relative")

        fun mainSources(): List<File> = File(root, "app/src/main/kotlin")
            .walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }
}
