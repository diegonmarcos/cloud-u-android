package com.diegonmarcos.clouddrive.startup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.clouddrive.DrivePrefs
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #1106 the crash-safe startup contract, on the JVM: a failure becomes a report file with the full
 * stack, is surfaced until shown, never throws out of the guard, safe mode skips only optional
 * phases, and a stale prefs schema or a main-thread socket is a fall-through, not a crash.
 */
@RunWith(RobolectricTestRunner::class)
class StartupGuardTest {

    private fun tmp(): File = Files.createTempDirectory("crashes").toFile()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun reportCarriesPhaseAndFullStackTrace() {
        val dir = tmp()
        val f = StartupGuard.record(dir, "DataMigration", IllegalStateException("boom", RuntimeException("root cause")), "device: X", 1_000L)
        val text = f!!.readText()
        assertTrue(text.startsWith("Drive hit a startup error"))
        assertTrue(text.contains("phase: DataMigration"))
        assertTrue(text.contains("java.lang.IllegalStateException: boom"))
        assertTrue("the cause chain is part of the stack", text.contains("Caused by: java.lang.RuntimeException: root cause"))
        assertTrue(text.contains("device: X"))
    }

    @Test fun anUnshownReportIsSurfacedUntilMarkedSeen() {
        val dir = tmp()
        StartupGuard.record(dir, "a", RuntimeException("1"), "", 1_000L)
        StartupGuard.record(dir, "b", RuntimeException("2"), "", 2_000L)
        assertEquals(2, StartupGuard.unseen(dir).size)
        assertTrue(StartupGuard.latest(dir)!!.readText().contains("phase: b"))
        StartupGuard.markAllSeen(dir)
        assertTrue("shown once, not on every launch", StartupGuard.unseen(dir).isEmpty())
        assertNotNull("still readable for Copy / Share", StartupGuard.latest(dir))
        assertTrue(StartupGuard.shareText(dir).contains("phase: b"))
    }

    @Test fun recordNeverThrowsAndOldReportsArePruned() {
        val blocked = File(tmp(), "file").apply { writeText("x") }
        assertNull("a dir that cannot be created is swallowed", StartupGuard.record(File(blocked, "sub"), "p", RuntimeException(), ""))
        val dir = tmp()
        repeat(30) { StartupGuard.record(dir, "p$it", RuntimeException(), "", 1_000L + it) }
        assertEquals(20, dir.listFiles()!!.size)
        assertTrue(StartupGuard.latest(dir)!!.readText().contains("phase: p29"))
    }

    @Test fun aFailingStepIsRecordedAndTheNextStepStillRuns() {
        val c = ctx
        StartupGuard.safeMode = false
        val before = StartupGuard.failureCount()
        var ran = false
        assertFalse(StartupGuard.step(c, "boom") { throw IllegalStateException("startup boom") })
        assertTrue(StartupGuard.step(c, "next") { ran = true })
        assertTrue(ran)
        assertEquals(before + 1, StartupGuard.failureCount())
        assertTrue(StartupGuard.latest(StartupGuard.dir(c))!!.readText().contains("startup boom"))
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
