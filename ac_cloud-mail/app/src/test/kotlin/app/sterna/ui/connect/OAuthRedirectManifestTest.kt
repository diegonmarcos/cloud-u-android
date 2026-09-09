package app.sterna.ui.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and disclaimer as [OAuthWiringTest]. A
 */
class OAuthRedirectManifestTest {

    @Test fun `the oauth redirect is registered on the applicationId placeholder`() {
        val lines = manifest().lines().map { it.trim() }
        assertTrue(
            "AndroidManifest.xml must register the OAuth return as exactly this line:\n" +
                "  $REQUIRED\n" +
                "Without it the browser has nowhere to come back to and the authorization code " +
                "never reaches the app.",
            lines.any { it == REQUIRED },
        )
    }

    @Test fun `no intent filter claims a literal package name as a scheme`() {
        val offenders = manifest().lines().withIndex()
            .filter { (_, line) -> LITERAL.containsMatchIn(line) }
            .map { (i, line) -> "${i + 1}: ${line.trim()}" }
        assertEquals(
            "a scheme must never be the literal application id: the test app installs beside " +
                "production and both would answer the same redirect. Use \${applicationId}. Found:\n" +
                offenders.joinToString("\n"),
            emptyList<String>(), offenders,
        )
    }

    private fun manifest(): String = MANIFEST.readText()

    private companion object {
        const val PATH = "app/src/main/AndroidManifest.xml"

        val MANIFEST: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, PATH).isFile }
                ?.let { File(it, PATH) }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the manifest as text and needs a working directory inside the checkout",
                )
        }

        /** Written with the character, not a raw-string escape, so what is compared is readable. */
        const val DOLLAR = '$'

        /** The whole line, compared for equality — the strongest anchor there is. */
        val REQUIRED = "<data android:scheme=\"$DOLLAR{applicationId}\" android:host=\"oauth\" />"

        val LITERAL = Regex("""android:scheme="app\.sterna""")
    }
}
