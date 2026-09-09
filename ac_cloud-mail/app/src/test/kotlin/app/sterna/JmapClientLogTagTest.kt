package app.sterna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * SOURCE RULE, and a deliberate one. `core/jmap` is plain Kotlin/JVM, so it logs through a lambda
 */
class JmapClientLogTagTest {

    @Test fun `the jmap client is built with a logging seam`() {
        val lines = applicationLinesContaining(": JmapClient = ")
        assertEquals("expected exactly one JmapClient construction site: $lines", 1, lines.size)
        assertEquals(
            "private val jmapClient: JmapClient = JmapClient { message, error ->",
            lines.single(),
        )
    }

    @Test fun `the seam writes under the tag handed to reporters, at a level release keeps`() {
        val lines = applicationLinesContaining("\"JmapClient\"")
        assertEquals("expected exactly one line carrying the logcat tag: $lines", 1, lines.size)
        assertEquals(
            "if (error == null) Log.i(\"JmapClient\", message) else Log.w(\"JmapClient\", message, error)",
            lines.single(),
        )
    }

    @Test fun `the wiring uses no level that release strips`() {
        val source = applicationSource()
        assertFalse("Log.d is removed by -assumenosideeffects in release", source.contains("Log.d("))
        assertFalse("Log.v is removed by -assumenosideeffects in release", source.contains("Log.v("))
    }

    private fun applicationLinesContaining(token: String): List<String> =
        applicationSource().lines().map { it.trim() }.filter { it.contains(token) }

    private fun applicationSource(): String =
        File(repoRoot, "app/src/main/kotlin/app/sterna/SternaApplication.kt").readText()

    private companion object {
        val repoRoot: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "app/src/main/res/values/strings.xml").isFile }
                ?: error("cannot locate the checkout from ${File("").absolutePath}")
        }
    }
}
