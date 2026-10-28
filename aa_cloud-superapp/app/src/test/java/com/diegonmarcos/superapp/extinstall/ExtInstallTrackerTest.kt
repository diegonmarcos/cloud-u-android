package com.diegonmarcos.superapp.extinstall

import com.diegonmarcos.superapp.appstore.JobBoard
import com.diegonmarcos.superapp.updater.UpdateProgress
import com.diegonmarcos.superapp.updater.UpdateProgress.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The install state machine: queued → downloading → verifying → installing → done | failed | cancelled, restart. */
class ExtInstallTrackerTest {

    private var clock = 1_000L
    private val board = JobBoard()
    private val t = ExtInstallTracker(board) { clock }
    private val pkg = "com.diegonmarcos.cloud.chat"
    private val url = "https://github.com/diegonmarcos/cloudy-u-android/releases/download/latest/cloud-comms-chat.apk"

    private fun job(stage: String) = UpdateProgress.Job(pkg, pkg, "Chat", stage)
    private fun phase() = t.snapshot(pkg)!!.phase

    @Test fun happyPath() {
        t.start(pkg, "Chat", url)
        assertEquals(ExtPhase.QUEUED, phase())
        t.onEvent(State.Downloading(0, 0, -1), job(UpdateProgress.STAGE_DOWNLOADING))
        assertEquals(ExtPhase.DOWNLOADING, phase())
        t.onEvent(State.Downloading(42, 42_000_000, 100_000_000), job(UpdateProgress.STAGE_DOWNLOADING))
        val s = t.snapshot(pkg)!!
        assertEquals(42, s.percent); assertEquals(100_000_000, s.total); assertEquals("github.com", s.sourceHost)
        t.onEvent(State.Downloading(100, 100_000_000, 100_000_000), job(UpdateProgress.STAGE_VERIFYING))
        assertEquals(ExtPhase.VERIFYING, phase())
        t.onEvent(State.Installing, job(UpdateProgress.STAGE_INSTALLING))
        assertEquals(ExtPhase.INSTALLING, phase())
        // PackageInstallerReceiver's Done names no package: it lands on the one installing row.
        t.onEvent(State.Done, null)
        assertEquals(ExtPhase.DONE, phase())
    }

    @Test fun failureCarriesReason_thenRestartRequeues() {
        t.start(pkg, "Chat", url)
        t.onEvent(State.Downloading(10, 10, 100), job(UpdateProgress.STAGE_DOWNLOADING))
        t.onEvent(State.Failed("HTTP 404", pkg = pkg), job(UpdateProgress.STAGE_DOWNLOADING))
        assertEquals(ExtPhase.FAILED, phase())
        assertEquals("HTTP 404", t.snapshot(pkg)!!.reason)
        // Late events do not resurrect a finished row.
        t.onEvent(State.Downloading(20, 20, 100), job(UpdateProgress.STAGE_DOWNLOADING))
        assertEquals(ExtPhase.FAILED, phase())
        assertTrue(t.restart(pkg))
        assertEquals(ExtPhase.QUEUED, phase())
        t.onEvent(State.Downloading(20, 20, 100), job(UpdateProgress.STAGE_DOWNLOADING))
        assertEquals(ExtPhase.DOWNLOADING, phase())
    }

    @Test fun cancelIsItsOwnTerminalState_andRestartable() {
        t.start(pkg, "Chat", url)
        t.onEvent(State.Downloading(30, 30, 100), job(UpdateProgress.STAGE_DOWNLOADING))
        t.cancel(pkg)
        assertEquals(ExtPhase.CANCELLED, phase())
        t.onEvent(State.Downloading(31, 31, 100), job(UpdateProgress.STAGE_DOWNLOADING))
        assertEquals(ExtPhase.CANCELLED, phase())
        t.restart(pkg)
        assertEquals(ExtPhase.QUEUED, phase())
        t.onEvent(State.Cancelled, job(UpdateProgress.STAGE_DOWNLOADING))
        assertEquals(ExtPhase.CANCELLED, phase())
    }

    @Test fun eventsForOtherJobsAreIgnored() {
        t.start(pkg, "Chat", url)
        assertNull(t.onEvent(State.Downloading(50, 50, 100), UpdateProgress.Job("x", "com.other", "Other", "downloading")))
        assertEquals(ExtPhase.QUEUED, phase())
        assertNull(t.snapshot("com.other"))
    }

    @Test fun speedAndEta() {
        t.start(pkg, "Chat", url)
        t.onEvent(State.Downloading(0, 0, 10_000_000), job("downloading"))
        clock += 1_000
        t.onEvent(State.Downloading(10, 1_000_000, 10_000_000), job("downloading"))
        val s = t.snapshot(pkg)!!
        assertEquals(1_000_000, s.bytesPerSec)
        assertEquals(9, s.etaSeconds)
    }

    @Test fun installedOnResumeClosesTheRow() {
        t.start(pkg, "Chat", url)
        t.onEvent(State.Installing, job(UpdateProgress.STAGE_INSTALLING))
        t.installed(pkg)
        assertEquals(ExtPhase.DONE, phase())
    }
}
