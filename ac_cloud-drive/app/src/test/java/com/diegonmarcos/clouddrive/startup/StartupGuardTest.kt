package com.diegonmarcos.clouddrive.startup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.clouddrive.DrivePrefs
import com.diegonmarcos.superapp.crash.CrashReports
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #1106 the crash-safe startup contract, on the JVM: a failing phase becomes ONE libs:crash report file with the
 * full stack and the next phase still runs, safe mode skips only optional phases, and a stale prefs schema or a
 * main-thread socket is a fall-through, not a crash. (The report file handling is CrashReportsTest, in libs:crash.)
 */
@RunWith(RobolectricTestRunner::class)
class StartupGuardTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun aFailingStepIsRecordedAndTheNextStepStillRuns() {
        val c = ctx
        StartupGuard.safeMode = false
        val before = StartupGuard.failureCount()
        var ran = false
        val files0 = CrashReports.dir(c).listFiles()?.count { it.name.endsWith(".txt") } ?: 0
        assertFalse(StartupGuard.step(c, "boom") { throw IllegalStateException("startup boom") })
        assertTrue(StartupGuard.step(c, "next") { ran = true })
        assertTrue(ran)
        assertEquals(before + 1, StartupGuard.failureCount())
        assertEquals("one report file per failure", files0 + 1, CrashReports.dir(c).listFiles()!!.count { it.name.endsWith(".txt") })
        assertTrue(CrashReports.latest(CrashReports.dir(c))!!.readText().contains("startup boom"))
    }

    @Test fun safeModeSkipsOptionalPhasesOnly() {
        val c = ctx
        var optional = false
        var essential = false
        StartupGuard.safeMode = true
        try {
            StartupGuard.step(c, "network", optional = true) { optional = true }
            StartupGuard.step(c, "prefs") { essential = true }
        } finally { StartupGuard.safeMode = false }
        assertFalse("migration / network / telemetry are skipped in safe mode", optional)
        assertTrue("the file list still opens", essential)
    }

    @Test fun staleTypedPrefsAreDefaultsNotAClassCastException() {
        val c = ctx
        // An older build stored these under another type; reading them as today's type used to throw.
        c.getSharedPreferences("cloud-drive-prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("files.sort", true).putInt("files.hidden", 3).putBoolean("files.bookmarks", true).commit()
        c.getSharedPreferences("cloud-drive-storage-grants", Context.MODE_PRIVATE).edit().putInt("saf_tree_grant_uri", 1).commit()
        val snap = DrivePrefs(c).snapshot.value
        assertFalse(snap.showHidden)
        assertTrue(snap.bookmarks.isEmpty())
        assertNull(snap.treeGrantUri)
    }

    @Test fun noSocketOnTheMainThread() {
        // Robolectric runs the test on the main looper thread.
        assertTrue(StartupGuard.onMainThread())
        var off = true
        val t = Thread { off = StartupGuard.onMainThread() }.also { it.start() }
        t.join()
        assertFalse(off)
    }
}
