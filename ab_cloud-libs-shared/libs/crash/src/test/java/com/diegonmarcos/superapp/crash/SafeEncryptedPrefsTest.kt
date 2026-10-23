package com.diegonmarcos.superapp.crash

import android.content.SharedPreferences
import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1106 a corrupt keyset (the prefs file survived, the Keystore master key is another one) must not crash the app. */
class SafeEncryptedPrefsTest {
    private val log = mutableListOf<String>()
    private fun good(): SharedPreferences = SafeEncryptedPrefs.MemoryPrefs()

    @Test fun healthyStoreOpensUntouched() {
        val p = good()
        val r = SafeEncryptedPrefs.recover({ p }, { log += "file" }, { log += "key" })
        assertTrue(r.prefs === p)
        assertFalse(r.wasReset)
        assertTrue(log.isEmpty())
    }

    @Test fun corruptKeysetRecoversByDeletingTheFileAndReports() {
        var opens = 0
        val r = SafeEncryptedPrefs.recover(
            { if (opens++ == 0) throw AEADBadTagException("Signature/MAC verification failed") else good() },
            { log += "file" }, { log += "key" })
        assertTrue(r.wasReset)
        assertEquals(listOf("file"), log)
        r.prefs.edit().putString("t", "x").apply()
        assertEquals("x", r.prefs.getString("t", null))
    }

    @Test fun aKeystoreThatStaysBrokenGetsItsAliasDeletedToo() {
        var opens = 0
        val r = SafeEncryptedPrefs.recover(
            { if (opens++ < 2) throw GeneralSecurityException("Keystore VERIFICATION_FAILED") else good() },
            { log += "file" }, { log += "key" })
        assertTrue(r.wasReset)
        assertEquals(listOf("file", "file", "key"), log)
    }

    @Test fun neverThrowsEvenWhenEverythingFailsAndFallsBackToMemory() {
        val r = SafeEncryptedPrefs.recover(
            { throw RuntimeException("no keystore") },
            { throw java.io.IOException("cannot delete") }, { throw IllegalStateException("no alias") })
        assertTrue(r.wasReset)
        assertNotNull(r.prefs)
        r.prefs.edit().putBoolean("b", true).putInt("i", 3).commit()
        assertTrue(r.prefs.getBoolean("b", false))
        assertEquals(3, r.prefs.getInt("i", 0))
        r.prefs.edit().remove("b").apply()
        assertFalse(r.prefs.contains("b"))
    }
}
