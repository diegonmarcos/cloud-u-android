package app.sterna.core.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WHAT THIS FILE IS WORTH. It proves the SHAPE of the two members that store the chosen OpenPGP
 * app, by reading the shipped source and comparing whole trimmed lines. It is not a behaviour test:
 * `SettingsRepository` needs a `Context` and a real DataStore, and there is no Robolectric here.
 * The decisions taken ON that stored value are executed elsewhere (`PgpProviders.resolve` and
 * `PgpProviders.switchErasesKeys`, in the app module's `PgpProviderChoiceTest`).
 *
 * Why it exists at all: the whole selector rests on this flow returning what was written, and a
 * body of `map { null }` breaks it completely while every other test in the repo stays green — the
 * resolver then only ever sees "no choice expressed", so the picker becomes decorative and #151 is
 * not fixed. Nothing reads this flow's VALUE anywhere a JVM test can reach.
 *
 * Whole lines, as CONTIGUOUS blocks, never `contains`: a fragment check is blind to anything
 * appended to a line (bitten twice on 2026-08-04).
 */
class PgpProviderStorageWiringTest {

    @Test fun `the chosen provider is read back from the key it is written under`() {
        assertEquals(
            "the pgpProvider flow no longer reads KEY_PGP_PROVIDER out of the DataStore. If it " +
                "answers a constant, the resolver only ever sees 'no choice expressed' and the " +
                "OpenPGP app picked in Settings is never the one bound.",
            listOf(
                "val pgpProvider: Flow<String?> = dataStore.data.map { it[KEY_PGP_PROVIDER] }",
            ),
            block("val pgpProvider:", 1),
        )
    }

    @Test fun `setting the provider writes that same key`() {
        assertEquals(
            "setPgpProvider no longer stores the package name under KEY_PGP_PROVIDER, so the " +
                "choice does not survive leaving the screen.",
            listOf(
                "suspend fun setPgpProvider(packageName: String) {",
                "dataStore.edit { it[KEY_PGP_PROVIDER] = packageName }",
                "}",
            ),
            block("suspend fun setPgpProvider(", 3),
        )
    }

    @Test fun `the storage key keeps its name`() {
        // Renaming it silently forgets every choice already made; the users who set it are back on
        // the default provider after an update, without a word.
        assertEquals(
            listOf("""private val KEY_PGP_PROVIDER = stringPreferencesKey("pgp_provider")"""),
            block("private val KEY_PGP_PROVIDER", 1),
        )
    }

    /** [count] consecutive CODE lines of `SettingsRepository.kt` from the single one starting with
     *  [prefix], trimmed, comments and blanks dropped. A prefix matching anything other than
     *  exactly one line returns a message, so an assertion can never pass by matching nothing. */
    private fun block(prefix: String, count: Int): List<String> {
        val path = "core/data/src/main/kotlin/app/sterna/core/data/settings/SettingsRepository.kt"
        val root = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .firstOrNull { java.io.File(it, path).isFile }
            ?: error("cannot locate the repo root from ${java.io.File("").absolutePath}")
        val code = java.io.File(root, path).readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
        val starts = code.indices.filter { code[it].startsWith(prefix) }
        val start = starts.singleOrNull()
            ?: return listOf("«${starts.size} code lines start with `$prefix`»")
        return code.subList(start, minOf(start + count, code.size))
    }
}
