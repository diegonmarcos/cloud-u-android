package app.sterna.core.data.account

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `SyncWindow` is `@Serializable` and stored in every account record BY CONSTANT NAME
 */
class SyncWindowStoredNameTest {

    /** Not `Json` with defaults tweaked: this is the parser [AccountStore] uses. */
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `the ten stored names are these, in this order`() {
        // Order is not itself persisted (names are); what this pins is the SET, whole, so a renamed
        // or dropped entry cannot pass as a reordering. Nothing here may ever be removed: the
        // picker's contents are decided by `syncWindowChoices()` and not by this list.
        assertEquals(
            listOf(
                "DAYS_30", "DAYS_90", "YEAR_1",
                "COUNT_100", "COUNT_1000", "COUNT_10000",
                "COUNT_50", "COUNT_200", "COUNT_500", "ALL",
            ),
            SyncWindow.entries.map { it.name },
        )
    }

    @Test fun `All is written to disk as the string ALL`() {
        assertEquals("\"ALL\"", json.encodeToString(SyncWindow.serializer(), SyncWindow.ALL))
        assertEquals(SyncWindow.ALL, json.decodeFromString(SyncWindow.serializer(), "\"ALL\""))
    }

    /**
     * THE TEST OF THIS VOLET. Seven account records, exactly as an install written BEFORE the
     */
    @Test fun `every window an older install may have stored still decodes, one record each`() {
        listOf(
            Triple("DAYS_30", 200, 30),
            Triple("DAYS_90", 200, 90),
            Triple("YEAR_1", 500, 365),
            Triple("COUNT_50", 50, null),
            Triple("COUNT_200", 200, null),
            Triple("COUNT_500", 500, null),
            Triple("ALL", 10_000, null),
        ).forEach { (name, limit, age) ->
            val stored = """{"id":"a1","server":"https://mail.example.org","username":"u","syncWindow":"$name"}"""
            val account = runCatching { json.decodeFromString(StoredAccount.serializer(), stored) }
            assertTrue(
                "an account record carrying \"$name\" no longer decodes. That member was removed " +
                    "or renamed, and the observable consequence on a device is NOT this failure: " +
                    "it is an empty account list, followed by the first write making it permanent.",
                account.isSuccess,
            )
            val window = account.getOrThrow().syncWindow
            assertEquals("the record decoded into the wrong window", name, window.name)
            assertEquals("$name no longer caches the number it has always meant", limit, window.limit)
            assertEquals("$name changed the kind of window it is", age, window.maxAgeDays)
        }
    }

    @Test fun `an account record written before this change still decodes, and gets the new value`() {
        // Literally what an existing install has on disk for an account set to "Everything".
        val stored = """{"id":"a1","server":"https://mail.example.org","username":"u","syncWindow":"ALL"}"""
        val account = json.decodeFromString(StoredAccount.serializer(), stored)
        assertEquals(SyncWindow.ALL, account.syncWindow)
        assertEquals(
            "an account that already asked for everything must get the largest window still " +
                "offered, without being touched and without a number of its own",
            10_000, account.syncWindow.limit,
        )
    }

    @Test fun `and it is written back unchanged`() {
        // Defaults are not encoded, so this is the whole record — a value compare, not a search.
        assertEquals(
            """{"id":"a1","server":"https://mail.example.org","username":"u","syncWindow":"ALL"}""",
            json.encodeToString(
                StoredAccount.serializer(),
                StoredAccount(id = "a1", server = "https://mail.example.org", username = "u", syncWindow = SyncWindow.ALL),
            ),
        )
    }

    @Test fun `a name this build does not know reads back as NO ACCOUNTS, and freezes every write`() {
        // The consequence of a rename, executed on the shipped decision. `SyncWindow` has no
        // entry called PAST_YEAR_2, exactly as a renamed ALL would have none called ALL — and the
        // record next to it is a perfectly good account that goes down with it.
        val stored = """[{"id":"a1","server":"s","username":"u","syncWindow":"PAST_YEAR_2"},""" +
            """{"id":"a2","server":"s","username":"v"}]"""

        val decoded = runCatching { json.decodeFromString<List<StoredAccount>>(stored) }
        assertTrue("kotlinx now accepts an unknown constant — this whole file's premise is gone", decoded.isFailure)

        // What `AccountStore.accounts()` runs, run here on that blob. The answer is STILL an empty
        // list — nothing above it can tell that apart from a device with no accounts — but the
        // store now knows why, and refuses to write, so the records above survive on disk and come
        // back with a build that knows the name. See AccountBlobGateTest for the rest.
        val gate = AccountBlobGate(
            decode = { json.decodeFromString<List<StoredAccount>>(it) },
            encode = { json.encodeToString(ListSerializer(StoredAccount.serializer()), it) },
        )
        assertEquals(emptyList<StoredAccount>(), gate.read(stored))
        var wrote = false
        assertFalse(
            "a rename is silently PERMANENT again: the empty list would be persisted over the two " +
                "real accounts by the first setter the user touches",
            gate.writeGuarded(emptyList()) { wrote = true },
        )
        assertFalse("the guarded write ran anyway", wrote)
    }

    @Test fun `every window round-trips under its own name`() {
        SyncWindow.entries.forEach {
            val written = json.encodeToString(SyncWindow.serializer(), it)
            assertEquals("\"${it.name}\"", written)
            assertEquals(it, json.decodeFromString(SyncWindow.serializer(), written))
        }
    }
}
