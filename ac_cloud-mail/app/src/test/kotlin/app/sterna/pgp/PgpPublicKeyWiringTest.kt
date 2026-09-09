package app.sterna.pgp

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST. It reads two view models as text and proves nothing about what
 */
class PgpPublicKeyWiringTest {

    @Test fun `choosing a key caches the public half, and never fails the gesture over it`() {
        assertEquals(
            "choosePgpKey changed shape. The two failures it must not have: (a) the public key is " +
                "no longer read for the key that was just chosen AND for that account's own " +
                "address (without which the provider exports every user id on the keyring), or " +
                "no longer encoded by " +
                "pgpPublicKeyCacheValue, so nothing can ever be announced; (b) a provider that " +
                "errors or wants interaction now stops the key id being persisted — 'Choose key' " +
                "then fails outright for a cache the composer would have caught up on its own.",
            listOf(
                "val publicKey = when (val key = pgp.getPublicKey(result.value, account.username)) {",
                "is PgpResult.Success -> pgpPublicKeyCacheValue(key.value)",
                "else -> \"\"",
                "}",
                "store.setPgp(",
                "accountId,",
                "enabled = true,",
                "signKeyId = result.value,",
                "publicKey = publicKey,",
                "encryptByDefault = account.pgpEncryptByDefault,",
                ")",
            ),
            block(
                "app/src/main/kotlin/app/sterna/ui/settings/AccountsViewModel.kt",
                prefix = "val publicKey = when (val key = pgp.getPublicKey(result.value, account.username)) {",
                count = 11,
            ),
        )
    }

    @Test fun `the toggle hands the account's own cached key straight back`() {
        assertEquals(
            "setPgp (the on/off + encrypt-by-default toggle) no longer passes the account's " +
                "existing pgpPublicKey back. The key id does not change here, so anything else — " +
                "an empty string above all — silently destroys a cache the account still needs.",
            listOf(
                "store.setPgp(",
                "accountId,",
                "enabled = enabled,",
                "signKeyId = account.pgpSignKeyId,",
                "publicKey = account.pgpPublicKey,",
                "encryptByDefault = encryptByDefault,",
                ")",
            ),
            block(
                "app/src/main/kotlin/app/sterna/ui/settings/AccountsViewModel.kt",
                prefix = "enabled = enabled,",
                count = 7,
                startingLinesEarlier = 2,
            ),
        )
    }

    @Test fun `the composer catches up an account that has a key id and no cache`() {
        assertEquals(
            "cachePgpPublicKey changed shape. What each line is holding up: the backfill decision " +
                "is pgpPublicKeyBackfill's, not a condition rewritten here; the account is marked " +
                "as tried BEFORE the round-trip, or a provider that is absent is asked again on " +
                "every From change; the key read is the account's OWN pgpSignKeyId, minimized to " +
                "the account's OWN address; the write " +
                "goes through setPgpPublicKey (which refuses it if the key id moved) and encodes " +
                "with pgpPublicKeyCacheValue; and only Success writes — a failure is silent.",
            listOf(
                "private suspend fun cachePgpPublicKey(account: StoredAccount?) {",
                "val id = pgpPublicKeyBackfill(account, pgpPublicKeyTried) ?: return",
                "val keyId = account?.pgpSignKeyId ?: return",
                "pgpPublicKeyTried += id",
                "val read = pgp.getPublicKey(keyId, account.username)",
                "if (read is PgpResult.Success) {",
                "store.setPgpPublicKey(id, keyId, pgpPublicKeyCacheValue(read.value))",
                "}",
                "}",
            ),
            block(
                "app/src/main/kotlin/app/sterna/ui/compose/ComposeViewModel.kt",
                prefix = "private suspend fun cachePgpPublicKey(",
                count = 9,
            ),
        )
    }

    @Test fun `the catch-up is hung off the PGP refresh, and only when the provider answers`() {
        // refreshPgp runs on opening, on restore and on every From change — the composer's own
        // "something about PGP may have changed" point. Behind `available`, so a phone with no
        // OpenPGP app at all is not asked once per composer for a key nobody can read.
        assertEquals(
            "the catch-up call left refreshPgp, or lost its `available` guard.",
            listOf("if (available) cachePgpPublicKey(account)"),
            block(
                "app/src/main/kotlin/app/sterna/ui/compose/ComposeViewModel.kt",
                prefix = "if (available) cachePgpPublicKey(account)",
                count = 1,
            ),
        )
    }

    /** Same helper as the account-store wiring tests: whole trimmed code lines, comments and blank
     *  lines dropped, anchored on the ONE line starting with [prefix] — a prefix matching anything
     *  other than exactly one line returns a message rather than nothing, so the assertion says
     *  what happened instead of silently comparing an empty list. */
    private fun block(path: String, prefix: String, count: Int, startingLinesEarlier: Int = 0): List<String> {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, path).isFile }
            ?: error("cannot locate the repo root from ${File("").absolutePath}")
        val code = File(root, path).readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
        val starts = code.indices.filter { code[it].startsWith(prefix) }
        val anchor = starts.singleOrNull()
            ?: return listOf("«${starts.size} code lines start with `$prefix`»")
        val start = maxOf(anchor - startingLinesEarlier, 0)
        return code.subList(start, minOf(start + count, code.size))
    }
}
