package app.sterna.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SOURCE LINT over the Home package — the two rules of #501's animation constraint that no rendered
 * test can observe, because they are about what the code is allowed to CONTAIN rather than what one
 * frame shows. What Home does under battery saver and "Remove animations" is proven by
 * [HomeContentRenderTest], which renders the page and reads it; this file only stands over the two
 * ways that proof could be walked around.
 */
class HomeAnimatedStatsWiringTest {

    private val homeSources: List<File> by lazy {
        File(root, "app/src/main/kotlin/app/sterna/ui/home").listFiles { f -> f.extension == "kt" }!!.sortedBy { it.name }
    }

    @Test fun `nothing on the Home page animates forever`() {
        val forever = Regex("""infiniteRepeatable|rememberInfiniteTransition|InfiniteTransition|repeatMode""")
        assertEquals(
            "an endless animation on the Home page — it would keep drawing frames while the reader is " +
                "looking at nothing, which is precisely the Battery Hunger #501 forbids. Every " +
                "animation here must be a finite one-shot that ends by itself.",
            emptyList<String>(),
            homeSources.flatMap { file ->
                file.readLines().mapIndexedNotNull { i, line ->
                    "${file.name}:${i + 1}".takeIf { code(line).contains(forever) }
                }
            },
        )
    }

    @Test fun `the Home package reads motion from the one shared gate and never from the system directly`() {
        val own = Regex("""isPowerSaveMode|POWER_SERVICE|ANIMATOR_DURATION_SCALE|ValueAnimator|areAnimatorsEnabled""")
        assertEquals(
            "the Home package read a power or animation setting itself. #501: wire to the EXISTING " +
                "signal (rememberMotionEnabled, proven by MotionTest), do not invent a second one — " +
                "a private check agrees with the shared gate today and drifts the day either changes.",
            emptyList<String>(),
            homeSources.flatMap { file ->
                file.readLines().mapIndexedNotNull { i, line ->
                    "${file.name}:${i + 1}".takeIf { code(line).contains(own) }
                }
            },
        )
        assertEquals(
            "exactly one call site of the shared gate in the Home package: HomeContent, which threads " +
                "the decision down. Two would be two decisions.",
            1,
            homeSources.sumOf { file -> file.readLines().count { code(it).contains("rememberMotionEnabled()") } },
        )
    }

    /** [line] without its `//` comment and without comment-only lines, so prose is not judged as code. */
    private fun code(line: String): String {
        val t = line.trim()
        return if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) "" else line.substringBefore("//")
    }

    private companion object {
        private const val MARKER = "app/src/main/kotlin/app/sterna/ui/home/HomeScreen.kt"

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, MARKER).isFile }
                ?: error("cannot locate the repo root from ${File("").absolutePath}")
        }
    }
}
