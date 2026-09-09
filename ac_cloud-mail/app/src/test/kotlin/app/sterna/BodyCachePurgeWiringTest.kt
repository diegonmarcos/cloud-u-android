package app.sterna

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, and a narrow one: nothing in this chain runs in a JVM test — `AppContainer.init`
 */
class BodyCachePurgeWiringTest {

    @Test fun `the purge is asked about the build now running, not about a threshold`() {
        assertEquals(
            "the version handed to onceForVersion must be BuildConfig.VERSION_CODE — the build " +
                "now starting. Pass a threshold instead (169, or BODY_CACHE_PURGE_VERSION) and " +
                "the decision is asked `did 169 cross 170?`, which is always no: the purge never " +
                "fires, the read-receipt banner never appears on mail already in the cache — the " +
                "twenty newest of the inbox, kept warm by the prefetch — and since 170 is the " +
                "LAST threshold, never again for the life of the install. Silently, suite green.",
            listOf("BodyCachePurge(appContext).onceForVersion(BuildConfig.VERSION_CODE) {"),
            codeLinesNaming(SOURCE.readText(), "onceForVersion("),
        )
    }

    @Test fun `what the purge drops is the cached bodies, and nothing else`() {
        assertEquals(
            "the lambda handed to onceForVersion must clear the cached BODIES and only them. " +
                "Empty it and the version is recorded anyway (BodyCachePurge writes the " +
                "preference after the lambda returns), so the one chance this install had to " +
                "shed the bodies serialised without dispositionNotificationTo is spent on " +
                "nothing and no later launch crosses a threshold again. Widen it to the message " +
                "list and an automatic startup purge becomes a full resync of every folder.",
            listOf("mailRepository.clearCachedBodies()"),
            codeLinesNaming(purgeLambda(), "mailRepository."),
        )
        assertEquals(
            "and the whole file clears the body cache in exactly ONE place: a second call site " +
                "outside the once-per-threshold gate is a purge on every start",
            listOf("mailRepository.clearCachedBodies()"),
            codeLinesNaming(SOURCE.readText(), "clearCachedBodies("),
        )
    }

    /** The block of the lambda passed to `onceForVersion` — braces included. */
    private fun purgeLambda(): String = blockAfter(SOURCE, "onceForVersion(")

    /** The code lines of [body] naming [needle], comments dropped. */
    private fun codeLinesNaming(body: String, needle: String): List<String> =
        body.lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { needle in it }

    /** The block opened by the first `{` after [anchor] in [file] — braces included. */
    private fun blockAfter(file: File, anchor: String): String {
        val source = file.readText()
        val at = source.indexOf(anchor)
        if (at < 0) error("${file.name} no longer names '$anchor' — did the purge wiring move?")
        val open = source.indexOf('{', at)
        var depth = 0
        var i = open
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open, i + 1)
            }
            i++
        }
        error("Unbalanced braces in ${file.name} after '$anchor'")
    }

    companion object {
        private const val SOURCE_PATH = "app/src/main/kotlin/app/sterna/SternaApplication.kt"

        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, SOURCE_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        private val SOURCE: File by lazy { File(root, SOURCE_PATH) }
    }
}
