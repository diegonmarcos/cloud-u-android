package com.diegonmarcos.superapp.extinstall

import com.diegonmarcos.superapp.appstore.JobBoard
import com.diegonmarcos.superapp.extinstall.ExtInstallViews.Action
import com.diegonmarcos.superapp.updater.ApkInstallWorker
import com.diegonmarcos.superapp.updater.UpdateProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Notification content per state, the tile ring per state, and the throttle. */
class ExtInstallViewsTest {

    private fun s(phase: ExtPhase, pct: Int = -1, bytes: Long = 0, total: Long = 0, bps: Long = 0, reason: String = "") =
        ExtSnapshot("p", "Chat", phase, bytes, total, pct, bps, reason, "github.com")

    @Test fun noticePerState() {
        val d = ExtInstallViews.notice(s(ExtPhase.DOWNLOADING, 42, 42_000_000, 100_000_000, 2_000_000))
        assertEquals("Downloading 42% · Chat", d.title)
        assertEquals("42.0 MB / 100.0 MB · 2.0 MB/s", d.text)
        assertEquals(42, d.progress); assertFalse(d.indeterminate); assertTrue(d.ongoing)
        assertEquals(listOf(Action.CANCEL), d.actions)

        val unknown = ExtInstallViews.notice(s(ExtPhase.DOWNLOADING, -1, 5_000))
        assertTrue(unknown.indeterminate)

        val i = ExtInstallViews.notice(s(ExtPhase.INSTALLING))
        assertEquals("Installing · Chat", i.title); assertTrue(i.indeterminate); assertTrue(i.actions.isEmpty())

        val done = ExtInstallViews.notice(s(ExtPhase.DONE))
        assertEquals("Done · Chat", done.title); assertFalse(done.ongoing); assertFalse(done.showProgress)

        val f = ExtInstallViews.notice(s(ExtPhase.FAILED, reason = "INSTALL_FAILED_NO_MATCHING_ABIS"))
        assertEquals("Failed · Chat", f.title); assertEquals("INSTALL_FAILED_NO_MATCHING_ABIS", f.text)
        assertEquals(listOf(Action.RETRY), f.actions); assertFalse(f.ongoing)

        assertEquals(listOf(Action.RESTART), ExtInstallViews.notice(s(ExtPhase.CANCELLED)).actions)
        assertEquals(listOf(Action.CANCEL), ExtInstallViews.notice(s(ExtPhase.QUEUED)).actions)
    }

    @Test fun tilePerState() {
        assertEquals(TileProgress(0.42f, "Downloading 42%", ExtPhase.DOWNLOADING), ExtInstallViews.tile(s(ExtPhase.DOWNLOADING, 42)))
        assertEquals("Installing", ExtInstallViews.tile(s(ExtPhase.INSTALLING))!!.label)
        assertTrue(ExtInstallViews.tile(s(ExtPhase.INSTALLING))!!.fraction < 0)
        assertEquals("Failed", ExtInstallViews.tile(s(ExtPhase.FAILED))!!.label)
        assertEquals("Cancelled", ExtInstallViews.tile(s(ExtPhase.CANCELLED))!!.label)
        assertNull(ExtInstallViews.tile(s(ExtPhase.DONE)))
        assertNull(ExtInstallViews.tile(null))
    }

    @Test fun tileReflectsTheStore() {
        val t = ExtInstallTracker(JobBoard())
        t.start("p", "Chat", "https://h/x.apk")
        assertEquals("Queued", ExtInstallViews.tile(t.snapshot("p"))!!.label)
        t.onEvent(UpdateProgress.State.Downloading(7, 7, 100), UpdateProgress.Job("p", "p", "Chat", "downloading"))
        assertEquals("Downloading 7%", ExtInstallViews.tile(t.snapshot("p"))!!.label)
        t.cancel("p")
        assertEquals(ExtPhase.CANCELLED, ExtInstallViews.tile(t.snapshot("p"))!!.phase)
    }

    @Test fun throttle_fewPerSecond_butPhaseChangesAlwaysPass() {
        val th = PostThrottle(333)
        assertTrue(th.allow("p", ExtPhase.DOWNLOADING, 0))
        assertFalse(th.allow("p", ExtPhase.DOWNLOADING, 100))
        assertFalse(th.allow("p", ExtPhase.DOWNLOADING, 300))
        assertTrue(th.allow("p", ExtPhase.DOWNLOADING, 340))
        assertTrue(th.allow("p", ExtPhase.FAILED, 350))
        assertTrue(th.allow("q", ExtPhase.DOWNLOADING, 351))
    }

    @Test fun sha256Sidecar_parsed() {
        val h = "a".repeat(64)
        assertEquals(h, ApkInstallWorker.parseSha256("$h  Cloud-Chat.apk\n"))
        assertEquals(h, ApkInstallWorker.parseSha256(h.uppercase()))
        assertNull(ApkInstallWorker.parseSha256("<html>Not Found</html>"))
    }
}
