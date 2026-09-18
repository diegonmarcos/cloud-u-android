package app.sterna.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — the same instrument as [HomeDestinationWiringTest] and
 * [app.sterna.ui.components.LoadingRingTest]: nothing in this module can run a Composable, so
 * #501's two hard, testable promises about the Home screen exist nowhere a behavioural test can
 * reach them:
 *
 *   1. every number this page shows is read straight off [AccountMailStats] — never a literal a
 *      layout preview left behind (the "vacuity trap" the ticket itself names);
 *   2. the count-up animation on those numbers is gated on the SAME [rememberMotionEnabled]
 *      decision [MotionTest] proves is battery-saver aware — not a second, unsynced boolean.
 *
 * This file stands over both by pinning [HomeScreen.kt]'s exact source shape. [MotionTest] is what
 * proves the DECISION honours battery saver; this file is what proves HomeScreen actually reads
 * that decision, and reads it into an animation whose target is a real field and not a number typed
 * by hand.
 */
class HomeAnimatedStatsWiringTest {

    @Test fun `every stat's value comes straight off AccountMailStats, not a literal`() {
        assertEquals(
            "a Home stat stopped reading its value off AccountMailStats. This is the vacuity #501 " +
                "warns about by name: the page can look byte-identical while one row quietly shows " +
                "a hardcoded number instead of whatever the mail store returned.",
            listOf(
                "Stat(stringResource(R.string.home_stat_unread), stats.unread),",
                "Stat(stringResource(R.string.home_stat_cached), stats.cachedMessages),",
                "Stat(stringResource(R.string.home_stat_folders), stats.folders),",
                "Stat(stringResource(R.string.home_stat_subscribed), stats.subscribedFolders),",
            ),
            block(HOME_SCREEN, "Stat(stringResource(R.string.home_stat_unread)", count = 4),
        )
    }

    @Test fun `HomeScreen reads its motion decision from the one shared gate, once`() {
        assertEquals(
            "HomeScreen stopped reading rememberMotionEnabled() at its top level — the shared gate " +
                "MotionTest proves is battery-saver aware. A screen computing its own motion " +
                "boolean can agree with that gate today and silently drift the day either one " +
                "changes; #501's second hard constraint holds here only because there is exactly " +
                "one decision, read once and threaded down to every account section.",
            listOf("val motionOn = rememberMotionEnabled()"),
            block(HOME_SCREEN, "val motionOn = rememberMotionEnabled()", count = 1),
        )
    }

    @Test fun `motionOn travels as a parameter into AccountSection and StatRow, not read twice`() {
        assertEquals(
            "AccountSection stopped taking motionOn as a parameter — meaning a StatRow underneath " +
                "it now has to call rememberMotionEnabled() itself (a second, unsynced gate) or has " +
                "no gate at all, either of which breaks the one-decision guarantee the test above " +
                "pins.",
            listOf("private fun AccountSection(stats: AccountMailStats, motionOn: Boolean) {"),
            block(HOME_SCREEN, "private fun AccountSection(stats: AccountMailStats, motionOn: Boolean) {", count = 1),
        )
        assertEquals(
            "StatRow stopped taking motionOn as a parameter (same failure as AccountSection above, " +
                "one call site closer to the animation itself).",
            listOf("private fun StatRow(label: String, value: Int?, accent: Color, motionOn: Boolean) {"),
            block(
                HOME_SCREEN,
                "private fun StatRow(label: String, value: Int?, accent: Color, motionOn: Boolean) {",
                count = 1,
            ),
        )
    }

    @Test fun `the count-up's target is the real stat value, and its spec branches on motionOn`() {
        assertEquals(
            "StatRow's animation changed shape. `targetValue` must stay exactly `value` — the very " +
                "parameter the test above ties to AccountMailStats — or the number animated to is " +
                "no longer provably the real one. And the spec must keep branching on `motionOn`: " +
                "collapse the two arms to a bare tween() and the count-up runs even in battery " +
                "saver, which is the regression #501 exists to prevent (see MotionTest for the " +
                "decision this branch must not stop reading).",
            listOf(
                "val animated by animateIntAsState(",
                "targetValue = value,",
                "animationSpec = if (motionOn) tween(SCREEN_SLIDE_MS) else snap(),",
                "label = \"homeStat\",",
                ")",
            ),
            block(HOME_SCREEN, "val animated by animateIntAsState(", count = 5),
        )
    }

    // ── instrument (copied from HomeDestinationWiringTest / CollapsedFoldersSurfaceWiringTest) ─────

    /** [count] consecutive code lines starting at the ONE line matching [prefix]. */
    private fun block(file: File, prefix: String, count: Int): List<String> {
        val lines = codeLines(file)
        val at = only(lines, prefix)
        return lines.subList(at, minOf(at + count, lines.size))
    }

    /** The index of the single code line starting with [needle]; fails loudly on none or several. */
    private fun only(lines: List<String>, needle: String): Int {
        val hits = lines.indices.filter { lines[it].startsWith(needle) }
        return hits.singleOrNull()
            ?: error(
                "${hits.size} code lines start with `$needle` — this lint reads the shipped source " +
                    "and must be taught the new shape rather than left green over something it " +
                    "never read",
            )
    }

    /** [file]'s lines, trimmed, comment-only lines dropped so no rule is satisfied by prose. */
    private fun codeLines(file: File): List<String> = file.readLines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private companion object {
        private const val HOME_SCREEN_PATH = "app/src/main/kotlin/app/sterna/ui/home/HomeScreen.kt"

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, HOME_SCREEN_PATH).isFile }
                ?: error("cannot locate the repo root from ${File("").absolutePath}")
        }

        val HOME_SCREEN: File by lazy { File(root, HOME_SCREEN_PATH) }
    }
}
