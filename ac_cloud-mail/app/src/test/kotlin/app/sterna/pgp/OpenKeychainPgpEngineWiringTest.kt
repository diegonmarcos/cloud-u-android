package app.sterna.pgp

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WHAT THIS FILE IS WORTH. It proves the SHAPE of `OpenKeychainPgpEngine.kt` and of the one
 */
class OpenKeychainPgpEngineWiringTest {

    @Test fun `no OpenKeychain package name is pinned in the engine`() {
        assertEquals(
            "the hard-coded pinning is back: a third-party OpenPGP provider becomes invisible " +
                "again, and PGP stays dead for anyone without OpenKeychain. Package names belong " +
                "to PgpProviders, which the JVM tests can execute.",
            emptyList<String>(),
            engineCode().filter { it.contains("org.sufficientlysecure.keychain") },
        )
    }

    @Test fun `the two package-manager probes are gone, so there is one discovery path`() {
        // They were TWO probes over one literal list, and isAvailable() called one while service()
        // bound through the other. Fixing one alone leaves isAvailable() answering false while the
        // bind would have succeeded — the defect reappears as "PGP is off" on a working provider.
        assertEquals(
            "providerInstalled/installedProvider are back in the engine: a second discovery path " +
                "that can disagree with the one service() binds through.",
            emptyList<String>(),
            engineCode().filter {
                it.contains("providerInstalled") || it.contains("installedProvider")
            },
        )
    }

    @Test fun `the head of service() picks a package and binds it, with nothing in between`() {
        // A CONTIGUOUS block, and it starts at the signature: the defect's second discovery path
        // came back as a single new statement carrying none of the words the absence tests look for
        //
        // Since the provider became selectable, this block also carries the ORDER the whole
        // selector depends on. The stored choice must be read and resolved BEFORE the cached
        assertEquals(
            "the head of service() changed shape. Either it no longer resolves the package from " +
                "PgpProviders + the stored choice, or the cached connection is consulted before " +
                "(or without) checking which package it is bound to, or a statement was slipped " +
                "in between. First case: a third-party provider is refused again. Second: the " +
                "provider chosen in Settings is never reached until the app restarts.",
            listOf(
                "private suspend fun service(): IOpenPgpService2? = bindMutex.withLock {",
                "val chosen = settings.pgpProvider.first()",
                "val pkg = PgpProviders.resolve(PgpProviders.installed(appContext), chosen) ?: return null",
                "if (!PgpProviders.bindingReusable(boundPackage, pkg)) {",
                "connection?.unbindFromService()",
                "connection = null",
                "boundPackage = null",
                "}",
                "connection?.takeIf { it.isBound }?.let { return it.service }",
                "val bound = withTimeoutOrNull(BIND_TIMEOUT_MS) {",
            ),
            block(ENGINE, "private suspend fun service()", 10),
        )
    }

    @Test fun `the bound package is recorded at the moment the connection is`() {
        // The bookkeeping half of the guard above. Lose this line and `boundPackage` stays null
        // for ever: bindingReusable then answers false on every call, and the engine unbinds and
        // rebinds the provider for each single operation.
        assertEquals(
            "the package a connection is bound to is no longer recorded beside the connection " +
                "itself, so the reuse guard is deciding on stale (or absent) information.",
            listOf(
                "connection = conn",
                "boundPackage = pkg",
                "conn.bindToService()",
            ),
            block(ENGINE, "connection = conn", 3),
        )
    }

    @Test fun `isAvailable is the bind attempt itself, with no second test in front of it`() {
        assertEquals(
            "isAvailable() grew a test of its own again. Whatever it tests can disagree with what " +
                "service() binds, and the disagreement always reads as 'no PGP here'.",
            listOf(
                "override suspend fun isAvailable(): Boolean =",
                "withContext(Dispatchers.IO) { service() != null }",
            ),
            block(ENGINE, "override suspend fun isAvailable()", 2),
        )
    }

    @Test fun `discovery asks the package manager which apps answer the service intent`() {
        // The single line the whole fix rests on, and the only one no other test reads: this file's
        // engine assertions stop at `PgpProviders.installed(...)`, and PgpProvidersTest only ever
        assertEquals(
            "discovery no longer goes through the api's service query filtered by eligible(). If " +
                "it went back to a list of package names, every third-party OpenPGP provider is " +
                "invisible again and PGP is dead for its users; if it lost eligible(), APG becomes " +
                "bindable and PGP breaks instead of working.",
            listOf(
                "fun installed(context: Context): List<String> =",
                "eligible(OpenPgpProviderUtil.getOpenPgpProviderPackages(context))",
            ),
            block(PROVIDERS, "fun installed(context: Context)", 2),
        )
    }

    @Test fun `both Autocrypt calls hand their arguments to the pure requests`() {
        // What each of these two lines is holding up is now EXECUTED next door
        // ([PgpApiRequestTest]): the minimize pair and the absent armor for the key read, the peer
        assertEquals(
            "getPublicKey no longer builds its intent from publicKeyRequest with the key id AND " +
                "the account's own address. Either the announced key stops being minimized to the " +
                "one identity this account may claim, or the arguments stopped coming from the " +
                "caller.",
            listOf("val intent = intentFor(publicKeyRequest(keyId, ownAddress), interactionResult)"),
            block(ENGINE, prefix = "val intent = intentFor(publicKeyRequest", count = 1),
        )
        assertEquals(
            "updateAutocryptPeer no longer builds its intent from autocryptPeerRequest with the " +
                "peer id, the key and the message's own date.",
            listOf(
                "intentFor(autocryptPeerRequest(peerId, keyData, effectiveDateMillis), interactionResult = null)",
            ),
            block(ENGINE, prefix = "intentFor(autocryptPeerRequest(", count = 1),
        )
    }

    @Test fun `the one place a pure request becomes an Intent puts every extra it can carry`() {
        // The translation half. It cannot be executed here (Intent is a stub in this source set),
        // and dropping ONE branch is invisible everywhere else: the extra is simply never put, and
        // the provider answers as if it had never been asked — a full keyring export, or an
        // Autocrypt peer filed with no key at all.
        assertEquals(
            "intentFor changed shape. A missing type branch silently drops an extra; a value that " +
                "is not the one from the request (a `false` for isMutual's own field, a `Date()` " +
                "for the message's date) is the same defect one level down.",
            listOf(
                "private fun intentFor(spec: PgpApiRequest, interactionResult: Intent?): Intent =",
                "request(spec.action, interactionResult).apply {",
                "for ((name, value) in spec.extras) when (value) {",
                "is Boolean -> putExtra(name, value)",
                "is Long -> putExtra(name, value)",
                "is String -> putExtra(name, value)",
                "is PeerUpdateFacts -> putExtra(",
                "name,",
                "AutocryptPeerUpdate.create(",
                "value.keyData,",
                "java.util.Date(value.effectiveDateMillis),",
                "value.isMutual,",
                "),",
                ")",
                "else -> error(\"no Intent form for extra \$name\")",
                "}",
                "}",
            ),
            block(ENGINE, prefix = "private fun intentFor(", count = 17),
        )
    }

    /** Code lines of [path], trimmed; comments and blanks dropped (a mutation cannot hide in a comment). */
    private fun code(path: String): List<String> {
        val root = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .firstOrNull { java.io.File(it, path).isFile }
            ?: error("cannot locate the repo root from ${java.io.File("").absolutePath}")
        return java.io.File(root, path).readLines()
            .map { it.trim() }
            .filter {
                it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*")
            }
    }

    private fun engineCode(): List<String> = code(ENGINE)

    /**
     * [count] CONSECUTIVE code lines of [path] from the single one starting with [prefix]. A prefix
     */
    private fun block(path: String, prefix: String, count: Int): List<String> {
        val lines = code(path)
        val starts = lines.indices.filter { lines[it].startsWith(prefix) }
        val start = starts.singleOrNull()
            ?: return listOf("«${starts.size} code lines of $path start with `$prefix`»")
        return lines.subList(start, minOf(start + count, lines.size))
    }

    private companion object {
        const val ENGINE = "app/src/main/kotlin/app/sterna/pgp/OpenKeychainPgpEngine.kt"
        const val PROVIDERS = "app/src/main/kotlin/app/sterna/pgp/PgpProviders.kt"
    }
}
