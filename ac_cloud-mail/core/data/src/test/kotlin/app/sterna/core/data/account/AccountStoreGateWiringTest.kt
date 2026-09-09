package app.sterna.core.data.account

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WHAT THIS FILE IS WORTH, first, because it is easy to over-read. It proves the SHAPE of
 */
class AccountStoreGateWiringTest {

    @Test fun `the gate is built with this store's own parser and writer`() {
        assertEquals(
            "AccountStore no longer hands AccountBlobGate its real Json codec. A decoder that " +
                "cannot fail is a gate that never refuses AND an account list that reads empty on " +
                "every install — the original data loss, on every device instead of broken ones.",
            listOf(
                "private val gate = AccountBlobGate(",
                "decode = { json.decodeFromString<List<StoredAccount>>(it) },",
                "encode = { json.encodeToString(it) },",
                ")",
            ),
            accountStoreBlock("private val gate = AccountBlobGate(", 4),
        )
    }

    @Test fun `accounts() decodes through the gate, so the flag is never stale`() {
        assertEquals(
            "accounts() no longer decodes through AccountBlobGate — whatever flag saveAccounts " +
                "guards on is then stale, or never set at all.",
            listOf(
                "fun accounts(): List<StoredAccount> {",
                "migrateIfNeeded()",
                "return gate.read(prefs.getString(KEY_ACCOUNTS, null))",
                "}",
            ),
            accountStoreBlock("fun accounts(): List<StoredAccount> {", 4),
        )
    }

    @Test fun `accountsUnreadable re-reads storage before it answers`() {
        // The startup screen (RootState.AccountsUnreadable) is drawn from this one boolean, so a
        // stale answer is a user held on an alert screen after the fixing update — or, the other
        assertEquals(
            "accountsUnreadable() no longer re-reads the blob before reporting, so the startup " +
                "routing runs on a flag left over from an earlier moment.",
            listOf(
                "fun accountsUnreadable(): Boolean {",
                "accounts()",
                "return gate.blobUnreadable",
                "}",
            ),
            accountStoreBlock("fun accountsUnreadable(): Boolean {", 4),
        )
    }

    @Test fun `saveAccounts writes nothing outside the guarded lambda`() {
        assertEquals(
            "saveAccounts changed shape. The blob may now be written outside " +
                "AccountBlobGate.writeGuarded, or the list published to the UI without having been " +
                "stored — either way the unreadable list is overwritten and the accounts are gone.",
            listOf(
                "private fun saveAccounts(list: List<StoredAccount>) {",
                "val written = gate.writeGuarded(list) { encoded ->",
                "prefs.edit().putString(KEY_ACCOUNTS, encoded).apply()",
                "}",
                "if (written) live.publish(list) else Log.e(TAG, REFUSED_WRITE)",
                "}",
            ),
            accountStoreBlock("private fun saveAccounts(", 6),
        )
    }

    @Test fun `clear lifts the refusal, since it empties the file outside saveAccounts`() {
        assertEquals(
            "clear() no longer lifts the gate. It wipes the whole prefs file without going through " +
                "saveAccounts, so an install reset from an unreadable state would refuse to store " +
                "an account for ever.",
            listOf(
                "fun clear() {",
                "prefs.edit().clear().apply()",
                "KeystoreCrypto.deleteKey()",
                "gate.onStorageWiped()",
                "live.publish(emptyList())",
                "}",
            ),
            accountStoreBlock("fun clear() {", 6),
        )
    }

    @Test fun `remove reads the list before it destroys the password`() {
        // The one mutator that destroyed something irreversible before reading anything. On an
        // unreadable blob every id looks unknown, so the guard is what stops the encrypted password
        // of a real account from being deleted while the gate is busy protecting its record.
        assertEquals(
            "remove(id) no longer bails out on an id the readable list does not contain, or it " +
                "deletes the password before looking. On an unreadable blob that destroys the " +
                "account's only secret, and no write-refusal can bring it back.",
            listOf(
                "fun remove(id: String) {",
                "val list = accounts()",
                "if (list.none { it.id == id }) return",
                "prefs.edit().remove(passwordKey(id)).apply()",
                "val remaining = list.filterNot { it.id == id }",
                "saveLinkedMemory(forgetRemovedLinked(linkedMemory(), list.filter { it.id == id }))",
                "saveAccounts(remaining)",
                "if (currentId() == id || remaining.none { it.id == prefs.getString(KEY_CURRENT, null) }) {",
                "prefs.edit().putString(KEY_CURRENT, remaining.firstOrNull()?.id).apply()",
                "}",
                "}",
            ),
            accountStoreBlock("fun remove(id: String) {", 11),
        )
    }

    @Test fun `importAccounts decides with the shared account key, not a rule of its own`() {
        // The duplicate-account defect is one rule re-derived per creation route. importAccounts is
        // the route that already had it right; it now CALLS AccountIdentity.kt instead of holding a
        // private copy, so V2 cannot fix the other routes against a different notion of "same
        // account". A local `fun key(...)` reappearing here is the defect coming back.
        assertEquals(
            "importAccounts no longer keys on accountKeyOf. Either it re-derived its own key " +
                "(the duplicate-account defect, one rule per route again), or it stopped skipping " +
                "the record whose key is null and now merges half-filled accounts together.",
            listOf(
                "fun importAccounts(incoming: List<StoredAccount>): Int {",
                "val existing = accounts()",
                "val seen = existing.mapNotNull(::accountKeyOf).toMutableSet()",
                "val added = mutableListOf<StoredAccount>()",
                "for (a in incoming) {",
                "val k = accountKeyOf(a) ?: continue",
                "if (k in seen) continue",
                "seen += k",
            ),
            accountStoreBlock("fun importAccounts(", 8),
        )
    }

    @Test fun `both creation routes go through the one add-or-refresh helper`() {
        // The duplicate-account defect in one place: the two writing routes (six creation flows
        // above them) resolve the account already stored under this identity and REFRESH it,
        assertEquals(
            "AccountStore.addOrRefresh changed shape. Either the existing login is no longer " +
                "resolved (adding the same mailbox twice puts a second identical line in " +
                "Settings > Accounts again), or the secret is no longer written under the id the " +
                "account is stored as (the account is then signed in against a slot nothing " +
                "reads), or the existing record is replaced instead of refreshed (her account " +
                "name, colour, watched folders and identities are gone). And narrowed back to " +
                "resolveExistingLogin on the one current key, an account OAuth-added under 1.5.0 " +
                "or 1.5.1 takes a SECOND line at the first re-add after the update — the fix for " +
                "#55 manufacturing the duplicate of #55.",
            listOf(
                "private fun addOrRefresh(",
                "proven: StoredAccount,",
                "secret: String,",
                "alsoRecognisedBy: List<AccountKey?> = emptyList(),",
                "): String {",
                "val list = accounts()",
                "val existing = resolveExistingLoginAmong(list, listOf(accountKeyOf(proven)) + alsoRecognisedBy)",
                "val id = existing?.id ?: proven.id",
                "writePassword(id, secret)",
                "val stored = if (existing == null) {",
                "list + proven",
                "} else {",
                "list.map { if (it.id == id) refreshedWith(it, proven) else it }",
                "}",
                "saveAccounts(stored)",
                "prefs.edit().putString(KEY_CURRENT, id).apply()",
                "return id",
                "}",
            ),
            accountStoreBlock("private fun addOrRefresh(", 18),
        )
    }

    @Test fun `add ends on the helper, so a password re-add refreshes one account`() {
        assertEquals(
            "add() no longer ends on addOrRefresh. Adding an account that is already there makes " +
                "a SECOND identical line in Settings > Accounts, with its own copy of the mail.",
            listOf("return addOrRefresh(account, password)"),
            accountStoreBlock("return addOrRefresh(account, password)", 1),
        )
    }

    @Test fun `addOAuth stores the host it was handed, not the OAuth document's`() {
        // The one line the whole of #55 turns on, and nothing else in this tree holds it. Rewritten
        // as `server = documentHost.ifBlank { server }.trim(),` it reads like tidying up and files
        assertEquals(
            "addOAuth no longer stores the server it was handed. Filed under the OAuth document's " +
                "host, the same mailbox added by password and then by OAuth makes two accounts " +
                "(#55) — and the two keys coincide then, so nothing else goes red.",
            listOf(
                "documentHost: String = \"\",",
                "): String {",
                "val id = UUID.randomUUID().toString()",
                "val account = StoredAccount(",
                "id = id,",
                "server = server.trim(),",
                "username = username.trim(),",
            ),
            accountStoreBlock("documentHost: String = \"\",", 7),
        )
    }

    @Test fun `addOAuth ends on the same helper, and hands it the host 1_5_x filed under`() {
        // The two lines are pinned together because either alone is decorative: building the
        // inherited key and not passing it changes nothing, and passing an empty list is the
        // defect with a line above it that reads right. `documentHost` blank yields no key, which
        // is what leaves the Microsoft OAuth IMAP route (no document host) untouched.
        assertEquals(
            "addOAuth() no longer ends on addOrRefresh, or no longer hands it the host the OAuth " +
                "document came from. Signing in again through OAuth then makes a SECOND identical " +
                "line in Settings > Accounts instead of refreshing the tokens of the account " +
                "already there: an account OAuth-added under 1.5.0 or 1.5.1 is filed under " +
                "mail.<domain> while this version resolves <domain>, so it takes a SECOND line at " +
                "the first re-add after the update — the fix for #55 manufacturing the duplicate " +
                "of #55.",
            listOf(
                "val inherited = if (documentHost.isBlank()) null else accountKeyOf(protocol, documentHost, imapHost, username)",
                "return addOrRefresh(account, refreshToken, alsoRecognisedBy = listOfNotNull(inherited))",
            ),
            accountStoreBlock("val inherited = if (documentHost.isBlank())", 2),
        )
    }

    /**
     * [count] consecutive CODE lines of `AccountStore.kt` from the single one starting with
     */
    private fun accountStoreBlock(prefix: String, count: Int): List<String> {
        val path = "core/data/src/main/kotlin/app/sterna/core/data/account/AccountStore.kt"
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
