package com.diegonmarcos.capitalfinterm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The user's keys: stored per source through the one SecretBox, trimmed, never kept when malformed, readable only by read(). */
class SourceKeysTest {
    private class MemBox : SecretBox {
        val store = LinkedHashMap<String, String>()
        override fun get(id: String) = store[id]
        override fun put(id: String, value: String) { store[id] = value }
        override fun remove(id: String) { store.remove(id) }
    }

    @Test fun saveTrimsAndKeysPerSource() {
        val box = MemBox()
        val keys = SourceKeys(box)
        assertTrue(keys.save("polygon", "  abc123DEF  "))
        assertTrue(keys.has("polygon"))
        assertFalse(keys.has("databento"))
        assertEquals("abc123DEF", keys.read("polygon"))
        assertEquals(setOf("polygon"), box.store.keys)
    }

    @Test fun malformedKeysAreRefusedAndNothingIsStored() {
        val box = MemBox()
        val keys = SourceKeys(box)
        assertFalse(keys.save("polygon", "   "))
        assertFalse(keys.save("polygon", "ab cd"))
        assertFalse(keys.save("polygon", "ab\ncd"))
        assertFalse(keys.save("", "abc"))
        assertTrue(box.store.isEmpty())
    }

    @Test fun clearForgetsTheKey() {
        val keys = SourceKeys(MemBox())
        keys.save("polygon", "k1")
        keys.clear("polygon")
        assertFalse(keys.has("polygon"))
        assertNull(keys.read("polygon"))
    }

    @Test fun theKeyIsInNoStringTheClassPrints() {
        val keys = SourceKeys(MemBox())
        keys.save("polygon", "SECRET-KEY-VALUE")
        assertFalse(keys.toString().contains("SECRET"))
    }
}
