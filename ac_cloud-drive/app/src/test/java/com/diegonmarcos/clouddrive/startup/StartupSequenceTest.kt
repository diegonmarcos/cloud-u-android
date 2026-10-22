package com.diegonmarcos.clouddrive.startup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1106 every transition and every typed error of the startup sequence, on a fake clock. */
class StartupSequenceTest {

    private class Fake : StartupEnv {
        var installed: Boolean = true
        var installedThrows = false
        var start: () -> TerminalStart = { TerminalStart.Started }
        var homeAfterPolls = 0          // homeReady() is true from this poll on; Int.MAX_VALUE = never
        var mount: () -> MountResult = { MountResult.Mounted }
        var now = 0L
        val sleeps = mutableListOf<Long>()
        val calls = mutableListOf<String>()
        private var polls = 0
        override fun terminalPackage() = "cld.termux"
        override fun terminalInstalled(): Boolean { calls += "installed"; if (installedThrows) error("pm died"); return installed }
        override fun startTerminal(timeoutMs: Long): TerminalStart { calls += "start"; return start() }
        override fun homePath() = "/data/user/0/cld.termux/files/home/store"
        override fun homeReady(): Boolean { polls++; calls += "home"; return polls > homeAfterPolls }
        override fun mount(timeoutMs: Long): MountResult { calls += "mount"; return mount() }
        override fun sleep(ms: Long) { sleeps += ms; now += ms }
        override fun nowMs() = now
        override fun log(msg: String) {}
    }

    private fun run(env: Fake, limits: StartupLimits = StartupLimits()): Pair<StartupState, List<StartupState>> {
        val seen = mutableListOf<StartupState>()
        val end = StartupSequence(env, limits).run { seen += it }
        return end to seen
    }

    private fun failed(s: StartupState): StartupError = (s as StartupState.Failed).error

    @Test fun happyPathWalksEveryStateInOrder() {
        val (end, seen) = run(Fake())
        assertEquals(StartupState.Ready, end)
        assertEquals(
            listOf(StartupState.CheckingTerminal, StartupState.StartingTerminal, StartupState.WaitingForHome(1), StartupState.Mounting, StartupState.Ready),
            seen,
        )
    }

    @Test fun missingTerminalStopsBeforeAnyStart() {
        val env = Fake().apply { installed = false }
        val (end, _) = run(env)
        assertEquals(StartupError.TermuxMissing("cld.termux"), failed(end))
        assertFalse("start" in env.calls)
    }

    @Test fun anInstalledCheckThatThrowsIsMissingNotACrash() {
        val (end, _) = run(Fake().apply { installedThrows = true })
        assertTrue(failed(end) is StartupError.TermuxMissing)
    }

    @Test fun aStoppedTerminalIsStartedThenTheHomeIsAwaitedWithBackoff() {
        val env = Fake().apply { homeAfterPolls = 4 }
        val (end, seen) = run(env)
        assertEquals(StartupState.Ready, end)
        assertEquals(listOf(250L, 500L, 1000L, 2000L), env.sleeps)
        assertEquals(5, seen.filterIsInstance<StartupState.WaitingForHome>().size)
        assertTrue(env.calls.indexOf("start") < env.calls.indexOf("home"))
    }

    @Test fun backoffIsCappedAndTheWaitIsBounded() {
        val env = Fake().apply { homeAfterPolls = Int.MAX_VALUE }
        val (end, _) = run(env, StartupLimits(homeWaitMs = 10_000))
        assertEquals(StartupError.HomeUnavailable(env.homePath()), failed(end))
        assertTrue(env.sleeps.max() <= 2_000L)
        assertTrue("never waits past the bound by more than one backoff", env.now < 10_000 + 2_000)
        assertFalse("mount" in env.calls)
    }

    @Test fun startDeniedWithoutAHomeIsPermissionDenied() {
        val env = Fake().apply { start = { TerminalStart.Denied("cld.termux.permission.CLOUD_EXEC") }; homeAfterPolls = Int.MAX_VALUE }
        val (end, _) = run(env)
        assertEquals(StartupError.PermissionDenied("cld.termux.permission.CLOUD_EXEC", StartupStep.START), failed(end))
    }

    @Test fun startFailureWithoutAHomeIsTermuxNotStarted() {
        val env = Fake().apply { start = { TerminalStart.Failed("cannot bind") }; homeAfterPolls = Int.MAX_VALUE }
        val (end, _) = run(env)
        assertEquals(StartupError.TermuxNotStarted("cannot bind"), failed(end))
        assertTrue("a known-bad start does not burn the whole wait", env.now < 2_000)
    }

    @Test fun startTimeoutAndSecurityExceptionAreTyped() {
        val t = Fake().apply { start = { throw StartupTimeout(15_000) }; homeAfterPolls = Int.MAX_VALUE }
        assertEquals(StartupError.Timeout(StartupStep.START, 15_000), failed(run(t).first))
        val s = Fake().apply { start = { throw SecurityException("no grant") }; homeAfterPolls = Int.MAX_VALUE }
        assertEquals(StartupError.PermissionDenied("no grant", StartupStep.START), failed(run(s).first))
        val x = Fake().apply { start = { throw IllegalStateException("boom") }; homeAfterPolls = Int.MAX_VALUE }
        assertEquals(StartupError.TermuxNotStarted("boom"), failed(run(x).first))
    }

    @Test fun aStartThatFailedButWhoseHomeIsReadableStillOpens() {
        // cld.termux's files are readable in the shared uid even while it is stopped.
        val env = Fake().apply { start = { TerminalStart.Failed("service unreachable") } }
        assertEquals(StartupState.Ready, run(env).first)
    }

    @Test fun mountErrorsAreTyped() {
        assertEquals(StartupError.MountFailed("read-only file system"), failed(run(Fake().apply { mount = { MountResult.Failed("read-only file system") } }).first))
        assertEquals(StartupError.Timeout(StartupStep.MOUNT, 15_000), failed(run(Fake().apply { mount = { throw StartupTimeout(15_000) } }).first))
        assertEquals(StartupError.PermissionDenied("EACCES", StartupStep.MOUNT), failed(run(Fake().apply { mount = { throw SecurityException("EACCES") } }).first))
        assertEquals(StartupError.MountFailed("io"), failed(run(Fake().apply { mount = { throw java.io.IOException("io") } }).first))
    }

    @Test fun everyErrorOffersRetryAndTheDegradedLocalFileList() {
        val all = listOf(
            StartupError.TermuxMissing("p"), StartupError.TermuxNotStarted("w"), StartupError.HomeUnavailable("h"),
            StartupError.PermissionDenied("x", StartupStep.START), StartupError.MountFailed("s"), StartupError.Timeout(StartupStep.MOUNT, 1),
        )
        for (e in all) {
            val a = actionsFor(e)
            assertTrue("$e retry", StartupAction.RETRY in a)
            assertTrue("$e degraded", StartupAction.CONTINUE_LOCAL in a)
        }
        assertTrue(StartupAction.INSTALL_TERMINAL in actionsFor(StartupError.TermuxMissing("p")))
        assertTrue(StartupAction.OPEN_TERMINAL in actionsFor(StartupError.TermuxNotStarted("w")))
        assertTrue(StartupAction.GRANT_ACCESS in actionsFor(StartupError.PermissionDenied("x", StartupStep.START)))
        assertTrue(StartupAction.VIEW_LOG in actionsFor(StartupError.MountFailed("s")))
    }

    @Test fun timeLimitedAnswersOrTimesOutAndRethrowsTheCause() {
        assertEquals(7, TimeLimited.call(1_000) { 7 })
        val slow = runCatching { TimeLimited.call(50) { Thread.sleep(2_000); 1 } }.exceptionOrNull()
        assertTrue(slow is StartupTimeout)
        val bad = runCatching { TimeLimited.call(1_000) { throw SecurityException("denied") } }.exceptionOrNull()
        assertTrue(bad is SecurityException)
    }

    @Test fun theFinishedFlagMarksOnlyTheEnds() {
        assertTrue(StartupState.Ready.finished)
        assertTrue(StartupState.Failed(StartupError.MountFailed("x")).finished)
        assertFalse(StartupState.Mounting.finished)
    }
}
