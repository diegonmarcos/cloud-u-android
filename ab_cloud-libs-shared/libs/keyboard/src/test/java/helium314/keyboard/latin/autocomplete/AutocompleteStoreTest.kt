// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.autocomplete

import com.diegonmarcos.superapp.autofill.AutocompleteContract
import com.diegonmarcos.superapp.autofill.AutocompleteContract.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1103 items 1-3: the Autocomplete engine's own store, encrypted at rest, filled by Account's answer and by nothing else. */
class AutocompleteStoreTest {
    /** XOR with a key: not secure, but the stored text is provably not the plain text, and a second key cannot read it. */
    private class FakeCipher(private val key: Int) : AutocompleteCipher {
        override fun encrypt(plain: ByteArray): String = plain.map { (it.toInt() xor key).toByte() }.joinToString(",") { it.toString() }
        override fun decrypt(text: String): ByteArray? = runCatching {
            val b = text.split(",").map { (it.toInt().toByte().toInt() xor key).toByte() }.toByteArray()
            // a wrong key yields garbage; the store must treat that as unreadable (JSON parse fails)
            b
        }.getOrNull()
    }

    private class MemoryBacking : AutocompleteBacking {
        var value: String? = null
        override fun read() = value
        override fun write(value: String?) { this.value = value }
    }

    private val rows = listOf(
        Row("ids", "National ID", "X1234567Z"),
        Row("addresses", "Home", "Calle Falsa 123, Madrid"),
        Row("urls", "Blog", "https://example.org/me"),
        Row("secrets", "Wi-Fi", "correct-horse-battery"),
    )

    @Test fun rowsRoundTripThroughTheEncryptedBlob() {
        val backing = MemoryBacking()
        val store = AutocompleteStore(backing, FakeCipher(0x5A))
        assertTrue(store.all().isEmpty())
        store.replaceAll(rows, now = 1234L)
        assertEquals(rows, store.all())
        assertEquals(listOf(rows[3]), store.page("secrets"))
        assertEquals(1234L, store.syncedAt())
    }

    @Test fun nothingPlainIsStoredAndAnotherKeyCannotReadIt() {
        val backing = MemoryBacking()
        AutocompleteStore(backing, FakeCipher(0x5A)).replaceAll(rows, 1L)
        val blob = backing.value!!
        for (r in rows) assertFalse("the blob must not hold ${r.label}", blob.contains(r.value))
        assertTrue(AutocompleteStore(backing, FakeCipher(0x33)).all().isEmpty())
    }

    @Test fun aReplaceReplacesAndClearEmpties() {
        val store = AutocompleteStore(MemoryBacking(), FakeCipher(7))
        store.replaceAll(rows, 1L)
        store.replaceAll(listOf(rows[0]), 2L)
        assertEquals(listOf(rows[0]), store.all())
        store.clear()
        assertTrue(store.all().isEmpty())
        assertEquals(0L, store.syncedAt())
    }

    @Test fun onlyTheFourPagesAndNonBlankValuesAreKept() {
        val store = AutocompleteStore(MemoryBacking(), FakeCipher(7))
        store.replaceAll(rows + Row("passwords", "x", "y") + Row("ids", "empty", "  "), 1L)
        assertEquals(rows, store.all())
    }

    @Test fun theStoreIsDeclaredAsADeviceStoreAndOpensItsOwnPrefsFile() {
        assertEquals("autocomplete_store", AutocompleteStore.PREFS)
        assertEquals("cloud_keyboard_autocomplete", KeystoreCipher.ALIAS)
    }

    @Test fun syncTakesAccountsAnswerAndLeavesTheStoreAloneWhenRefused() {
        val store = AutocompleteStore(MemoryBacking(), FakeCipher(9))
        val json = AutocompleteContract.toJson(rows)
        assertEquals(AutocompleteSync.Outcome.Synced(4), AutocompleteSync.apply(store, ok = true, json = json, error = null, now = 5L))
        assertEquals(rows, store.all())
        val refused = AutocompleteSync.apply(store, ok = false, json = null, error = "no grant for x", now = 6L)
        assertEquals(AutocompleteSync.Outcome.Refused("no grant for x"), refused)
        assertEquals("a refused call keeps what was synced", rows, store.all())
        assertEquals(5L, store.syncedAt())
        // an answer that is not a document empties nothing silently: it yields no rows
        assertEquals(AutocompleteSync.Outcome.Synced(0), AutocompleteSync.apply(store, ok = true, json = "nonsense", error = null, now = 7L))
    }

    @Test fun theWireDocumentDropsUnknownPagesBlankValuesAndTheCap() {
        val big = (1..600).joinToString(",") { """{"label":"l$it","value":"v$it"}""" }
        val json = """{"v":1,"ids":[$big],"urls":[{"label":"a","value":""}],"passwords":[{"label":"p","value":"x"}],
            "secrets":[{"label":" k ","value":"s"}]}"""
        val parsed = AutocompleteContract.parse(json)
        assertEquals(AutocompleteContract.MAX_PER_PAGE, parsed.count { it.page == "ids" })
        assertTrue(parsed.none { it.page == "urls" || it.page == "passwords" })
        assertEquals(Row("secrets", "k", "s"), parsed.single { it.page == "secrets" })
        assertTrue(AutocompleteContract.parse(null).isEmpty())
    }
}
