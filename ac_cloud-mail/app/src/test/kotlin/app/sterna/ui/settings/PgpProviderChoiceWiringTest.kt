package app.sterna.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WHAT THIS FILE IS WORTH. It proves the SHAPE of the two `AccountsViewModel` methods behind the
 */
class PgpProviderChoiceWiringTest {

    @Test fun `discovery runs off the main thread and publishes both values together`() {
        // The two assignments are consecutive and come after every suspension in the method.
        // Move `_pgpProviders.value = installed` above the resolution and the row is drawn, with
        assertEquals(
            "refreshPgpAvailable changed shape. Either the package-manager query left " +
                "Dispatchers.IO (it is a binder round trip, and viewModelScope is the main " +
                "thread), or the provider list is published without the provider in use beside " +
                "it — a row drawn over a state that is not resolved yet — or the pin no longer " +
                "goes through PgpProviders.providerToPin with the STORED value and the RESOLVED " +
                "one, which is what keeps a single-provider device from switching silently, and " +
                "an explicit choice from being overwritten by the fallback.",
            listOf(
                "fun refreshPgpAvailable() {",
                "viewModelScope.launch {",
                "val installed = withContext(Dispatchers.IO) { PgpProviders.installed(getApplication()) }",
                "val stored = settings.pgpProvider.first()",
                "val inUse = PgpProviders.resolve(installed, stored)",
                "_pgpProviders.value = installed",
                "_pgpProviderInUse.value = inUse",
                "_pgpAvailable.value = pgp.isAvailable()",
                "PgpProviders.providerToPin(stored, inUse)?.let { settings.setPgpProvider(it) }",
                "}",
                "}",
            ),
            block("fun refreshPgpAvailable() {", 11),
        )
    }

    @Test fun `picking a provider persists it first, then erases keys only on a real switch`() {
        // The order is an arbitration (WYSIWYG): between the two writes, what is displayed must
        // already be what is used. And what is in use is re-read from storage inside the coroutine
        // — never `_pgpProviderInUse.value`, which is screen state and can still be null.
        assertEquals(
            "setPgpProvider changed shape. Either the choice is no longer persisted, or what is " +
                "in use is no longer re-read from storage (reading screen state destroys every " +
                "account's signing key on a tap that changed nothing), or the erasure no longer " +
                "goes through PgpProviders.switchErasesKeys, or the two writes swapped order.",
            listOf(
                "fun setPgpProvider(packageName: String) {",
                "viewModelScope.launch {",
                "val installed = withContext(Dispatchers.IO) { PgpProviders.installed(getApplication()) }",
                "val inUse = PgpProviders.resolve(installed, settings.pgpProvider.first())",
                "settings.setPgpProvider(packageName)",
                "_pgpProviderInUse.value = packageName",
                "if (PgpProviders.switchErasesKeys(inUse, packageName)) {",
                "store.clearPgpSignKeys()",
                "refresh()",
                "}",
                "_pgpAvailable.value = pgp.isAvailable()",
                "}",
                "}",
            ),
            block("fun setPgpProvider(packageName: String) {", 13),
        )
    }

    /** [count] consecutive CODE lines of `AccountsViewModel.kt` from the single one starting with
     *  [prefix], trimmed, comments and blanks dropped. A prefix matching anything other than
     *  exactly one line returns a message, so an assertion can never pass by matching nothing. */
    private fun block(prefix: String, count: Int): List<String> {
        val path = "app/src/main/kotlin/app/sterna/ui/settings/AccountsViewModel.kt"
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
