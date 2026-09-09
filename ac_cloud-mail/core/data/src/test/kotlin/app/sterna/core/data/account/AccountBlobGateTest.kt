package app.sterna.core.data.account

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision that stops an unreadable account list from being deleted, EXECUTED — not read off
 * the source of [AccountStore], which needs a `Context` and the Keystore and cannot be built here.
 *
 * The bench saw it twice on 2026-08-06: four accounts, then two, then "Add an account", with the
 * `pw_<id>` keys of the vanished accounts still in the prefs file — the list had been rewritten,
 * not emptied by anyone. The mechanism is that a decode failure used to read back as "no accounts",
 * and every mutator is a read-modify-write, so the first setting the user touched persisted that
 * empty list over the real one.
 *
 * The parser below is the one [AccountStore] declares — but this file builds its OWN gate, so it
 * cannot prove the store passes that parser: [AccountStoreGateWiringTest] pins the construction
 * site, because a gate handed a decoder that cannot fail never refuses anything and every test
 * here stays green. The trigger is real too: kotlinx serialises an enum BY NAME and
 * `ignoreUnknownKeys` does not cover a name, so ONE constant this build does not know takes the
 * whole list down.
 */
class AccountBlobGateTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun gate() = AccountBlobGate(
        decode = { json.decodeFromString<List<StoredAccount>>(it) },
        encode = { json.encodeToString(it) },
    )

    /** Four accounts as a device has them, the third one on a sync window this build cannot name —
     *  exactly what a renamed `SyncWindow` constant leaves behind. */
    private val fourAccountsOneUnknownName =
        """[{"id":"a1","server":"https://mail.example.org","username":"alex"},""" +
            """{"id":"a2","server":"https://mail.example.org","username":"jordan"},""" +
            """{"id":"a3","server":"https://mail.example.org","username":"sam","syncWindow":"PAST_YEAR_2"},""" +
            """{"id":"a4","server":"https://mail.example.org","username":"casey"}]"""

    /** The same four, all on names this build knows. */
    private val fourAccountsAllKnown =
        """[{"id":"a1","server":"https://mail.example.org","username":"alex"},""" +
            """{"id":"a2","server":"https://mail.example.org","username":"jordan"},""" +
            """{"id":"a3","server":"https://mail.example.org","username":"sam","syncWindow":"COUNT_500"},""" +
            """{"id":"a4","server":"https://mail.example.org","username":"casey"}]"""

    @Test fun `a blob that will not decode reads as empty, and nothing may be written over it`() {
        val gate = gate()

        assertEquals(
            "the empty list is still what callers get — 62 sites and the store's constructor take " +
                "nothing else",
            emptyList<StoredAccount>(),
            gate.read(fourAccountsOneUnknownName),
        )
        assertTrue("the gate did not notice the decode failure", gate.blobUnreadable)

        var wrote = false
        assertFalse(
            "the write was allowed: this is the original defect, and the user's four accounts are " +
                "gone the moment she changes any setting",
            gate.writeGuarded(emptyList()) { wrote = true },
        )
        assertFalse("the guarded block ran anyway", wrote)
    }

    @Test fun `an absent blob is a legitimate empty, and writing is allowed`() {
        val gate = gate()

        assertEquals(emptyList<StoredAccount>(), gate.read(null))
        assertFalse("a fresh install was mistaken for a broken one", gate.blobUnreadable)

        var wrote = false
        assertTrue(
            "a fresh install cannot store its first account any more",
            gate.writeGuarded(emptyList()) { wrote = true },
        )
        assertTrue(wrote)
    }

    @Test fun `a blob that decodes to an empty list is trusted too`() {
        val gate = gate()

        assertEquals(emptyList<StoredAccount>(), gate.read("[]"))
        assertFalse(gate.blobUnreadable)
        assertTrue("an install whose last account was removed can no longer add one", gate.writeGuarded(emptyList()) {})
    }

    @Test fun `the refusal lifts on the very next readable blob, with no restart`() {
        val gate = gate()
        gate.read(fourAccountsOneUnknownName)
        assertTrue("precondition: the gate must be holding the refusal", gate.blobUnreadable)

        // The build that knows the name is installed; nothing else happened, no restart, no repair.
        val back = gate.read(fourAccountsAllKnown)

        assertEquals(listOf("a1", "a2", "a3", "a4"), back.map { it.id })
        assertEquals(SyncWindow.COUNT_500, back.single { it.id == "a3" }.syncWindow)
        assertFalse("the refusal outlived the failure it was protecting against", gate.blobUnreadable)
        assertTrue("settings can never be saved again on this install", gate.writeGuarded(emptyList()) {})
    }

    @Test fun `an absent blob after an unreadable one also lifts the refusal`() {
        // "Clear app data" wipes the prefs FILE, so the next read sees no key at all. Without the
        // lift, that install would refuse to store an account for ever.
        val gate = gate()
        gate.read(fourAccountsOneUnknownName)

        assertEquals(emptyList<StoredAccount>(), gate.read(null))
        assertFalse("the refusal outlived the blob it was protecting", gate.blobUnreadable)
        assertTrue("a wiped install stayed frozen", gate.writeGuarded(emptyList()) {})
    }

    @Test fun `clear lifts the refusal without a read`() {
        // AccountStore.clear() empties the whole prefs file and never goes through saveAccounts, so
        // it lifts the flag by hand; the very next add() must be able to write.
        val gate = gate()
        gate.read(fourAccountsOneUnknownName)

        gate.onStorageWiped()

        assertFalse(gate.blobUnreadable)
        assertTrue("a full reset left the store unable to write", gate.writeGuarded(emptyList()) {})
    }

    /**
     * The whole incident, played end to end on a fake of the store's storage: the read-modify-write
     */
    @Test fun `changing a setting over an unreadable blob leaves the stored bytes untouched`() {
        var blob: String? = fourAccountsOneUnknownName
        val gate = gate()

        // Any mutator: read the list, change something, write it back. The read hands back nothing,
        // which is precisely why the write must not happen.
        val loaded = gate.read(blob)
        assertEquals(emptyList<StoredAccount>(), loaded)
        gate.writeGuarded(loaded) { blob = it }

        assertEquals(
            "the account blob was rewritten — this is the permanent loss, byte for byte",
            fourAccountsOneUnknownName,
            blob,
        )

        // Corrective build installed: the SAME bytes now decode, and all four accounts are there.
        blob = fourAccountsAllKnown
        assertEquals(listOf("alex", "jordan", "sam", "casey"), gate.read(blob).map { it.username })
    }
}
