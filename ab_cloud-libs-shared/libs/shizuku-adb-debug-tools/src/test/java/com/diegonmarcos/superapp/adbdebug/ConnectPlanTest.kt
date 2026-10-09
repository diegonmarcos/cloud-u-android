package com.diegonmarcos.superapp.adbdebug

import com.diegonmarcos.superapp.adbdebug.ChannelMode.AUTO
import com.diegonmarcos.superapp.adbdebug.ChannelMode.EMBEDDED_ONLY
import com.diegonmarcos.superapp.adbdebug.ChannelMode.LOCAL_SERVER
import com.diegonmarcos.superapp.adbdebug.ChannelMode.SHIZUKU
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Connect sequence: which steps a mode runs, and the machine that walks them. */
class ConnectPlanTest {

    private val none = ChannelFacts(LOCAL_SERVER, 1L, serverPort = 38099)

    /** A device where each step's success flips the fact the next steps read; [failAt]/[waitAt] break one step. */
    private class FakeDevice(var f: ChannelFacts, val failAt: StepId? = null, val waitAt: StepId? = null) : ConnectExecutor {
        val ran = ArrayList<StepId>()
        override fun facts() = f
        override fun run(step: StepId): StepResult {
            ran += step
            if (step == failAt) return StepResult(false, "boom")
            if (step == waitAt) return StepResult(false, "code needed", needsUser = true)
            f = when (step) {
                StepId.DEV_OPTIONS -> f.copy(devOptions = true)
                StepId.WIRELESS_DEBUG -> f.copy(wirelessDebug = true)
                StepId.PAIR -> f.copy(adbPaired = true)
                StepId.ADB_CONNECT -> f.copy(adbConnected = true)
                StepId.SHIZUKU_RUNNING -> f.copy(shizukuInstalled = true, shizukuRunning = true)
                StepId.SHIZUKU_PERMISSION -> f.copy(shizukuGranted = true)
                StepId.START_SERVER -> f.copy(serverRunning = true)
                StepId.VERIFY -> f
            }
            return StepResult(true, "ok:${step.name}")
        }
    }

    @Test fun stepsPerMode() {
        val adb = listOf(StepId.DEV_OPTIONS, StepId.WIRELESS_DEBUG, StepId.PAIR, StepId.ADB_CONNECT)
        val shz = listOf(StepId.SHIZUKU_RUNNING, StepId.SHIZUKU_PERMISSION)
        assertEquals(adb + StepId.VERIFY, ConnectPlan.steps(EMBEDDED_ONLY, LaunchVia.ADB))
        assertEquals(shz + StepId.VERIFY, ConnectPlan.steps(SHIZUKU, LaunchVia.ADB))
        assertEquals(adb + StepId.START_SERVER + StepId.VERIFY, ConnectPlan.steps(LOCAL_SERVER, LaunchVia.ADB))
        assertEquals(shz + StepId.START_SERVER + StepId.VERIFY, ConnectPlan.steps(LOCAL_SERVER, LaunchVia.SHIZUKU))
        assertEquals(adb + StepId.START_SERVER + StepId.VERIFY, ConnectPlan.steps(AUTO, LaunchVia.SHIZUKU))   // Auto is adb-led whatever the pref
        for (m in ChannelMode.values()) for (v in LaunchVia.values()) assertEquals(StepId.VERIFY, ConnectPlan.steps(m, v).last())
        // no server of its own: the launch step is never planned
        for (m in ChannelMode.values()) for (v in LaunchVia.values()) assertFalse(StepId.START_SERVER in ConnectPlan.steps(m, v, ownsServer = false))
        assertEquals(adb + StepId.VERIFY, ConnectPlan.steps(AUTO, LaunchVia.ADB, ownsServer = false))
    }

    @Test fun methodsShowOnlyWhatAppliesToTheMode() {
        assertEquals(listOf("Pair with code", "Connect via mDNS"), ConnectPlan.methods(EMBEDDED_ONLY, LaunchVia.ADB).map { it.title })
        // an app with no server of its own (a terminal) is offered the embedded way in every mode but Shizuku
        assertEquals(listOf("Pair with code", "Connect via mDNS"), ConnectPlan.methods(AUTO, LaunchVia.ADB, ownsServer = false).map { it.title })
        assertEquals(listOf("Shizuku only"), ConnectPlan.methods(SHIZUKU, LaunchVia.ADB, ownsServer = false).map { it.title })
        assertEquals(listOf("Shizuku only"), ConnectPlan.methods(SHIZUKU, LaunchVia.ADB).map { it.title })
        assertEquals(listOf("Launch the server via Shizuku"), ConnectPlan.methods(LOCAL_SERVER, LaunchVia.SHIZUKU).map { it.title })
        assertTrue(ConnectPlan.methods(LOCAL_SERVER, LaunchVia.ADB).any { it.title == "Launch the server via adb" })
        assertTrue(ConnectPlan.methods(AUTO, LaunchVia.ADB).none { it.title.contains("Shizuku") })
    }

    @Test fun runsEveryStepInOrderAndSucceeds() {
        val dev = FakeDevice(none)
        val seen = ArrayList<List<StepRun>>()
        val out = ConnectPlan.run(LOCAL_SERVER, LaunchVia.ADB, dev) { seen += it }
        assertEquals(ConnectStatus.SUCCESS, out.status)
        assertNull(out.failedStep); assertNull(out.explanation)
        assertEquals(ConnectPlan.steps(LOCAL_SERVER, LaunchVia.ADB), dev.ran)
        assertTrue(out.steps.all { it.state == StepState.DONE })
        // progress was reported step by step: a RUNNING snapshot precedes each DONE
        assertTrue(seen.any { s -> s.count { it.state == StepState.RUNNING } == 1 })
        assertEquals(StepState.PENDING, seen.first().first().state)
    }

    @Test fun skipsWhatIsAlreadyTrue() {
        val paired = none.copy(devOptions = true, wirelessDebug = true, adbPaired = true)
        val dev = FakeDevice(paired)
        val out = ConnectPlan.run(EMBEDDED_ONLY, LaunchVia.ADB, dev)
        assertEquals(ConnectStatus.SUCCESS, out.status)
        assertEquals(listOf(StepId.ADB_CONNECT, StepId.VERIFY), dev.ran)
        assertEquals(listOf(StepState.SKIPPED, StepState.SKIPPED, StepState.SKIPPED, StepState.DONE, StepState.DONE), out.steps.map { it.state })
    }

    /** The point of the machine: a failure at EACH step stops there, names it, and leaves the rest pending. */
    @Test fun failureAtEachStepStopsThereAndExplainsIt() {
        for (mode in ChannelMode.values()) for (via in LaunchVia.values()) {
            val plan = ConnectPlan.steps(mode, via)
            for ((i, step) in plan.withIndex()) {
                if (step == StepId.PAIR) continue                                  // PAIR hands over to the person: its own test
                val dev = FakeDevice(none, failAt = step)
                val out = ConnectPlan.run(mode, via, dev)
                val tag = "$mode/$via fail@$step"
                assertEquals(tag, ConnectStatus.FAILED, out.status)
                assertEquals(tag, step, out.failedStep)
                assertEquals(tag, plan.take(i + 1), dev.ran)                          // nothing after the failing step ran
                assertEquals(tag, StepState.FAILED, out.steps[i].state)
                assertEquals(tag, "boom", out.steps[i].detail)
                assertTrue(tag, out.steps.drop(i + 1).all { it.state == StepState.PENDING })
                assertTrue(tag, out.steps.take(i).all { it.state == StepState.DONE })
                assertTrue(tag, out.explanation!!.startsWith("Stopped at \"${step.label}\": "))
                assertTrue(tag, out.explanation!!.contains("(boom)"))
            }
        }
    }

    @Test fun pairingHandsOverToThePerson() {
        val dev = FakeDevice(none, waitAt = StepId.PAIR)
        val out = ConnectPlan.run(EMBEDDED_ONLY, LaunchVia.ADB, dev)
        assertEquals(ConnectStatus.WAITING_FOR_USER, out.status)
        assertEquals(StepId.PAIR, out.failedStep)
        assertEquals(StepState.WAITING, out.steps.first { it.id == StepId.PAIR }.state)
        assertEquals(listOf(StepId.DEV_OPTIONS, StepId.WIRELESS_DEBUG, StepId.PAIR), dev.ran)
        assertTrue(out.explanation!!.contains("type the 6-digit code into the notification"))
        // a pairing that could not even start says what to do instead
        val broke = ConnectPlan.run(EMBEDDED_ONLY, LaunchVia.ADB, FakeDevice(none, failAt = StepId.PAIR))
        assertEquals(ConnectStatus.FAILED, broke.status)
        assertTrue(broke.explanation!!.contains("Allow notifications"))
    }

    @Test fun anExecutorThatThrowsIsAFailedStepNotACrash() {
        val dev = object : ConnectExecutor {
            override fun facts() = none
            override fun run(step: StepId): StepResult = throw IllegalStateException("socket closed")
        }
        val out = ConnectPlan.run(SHIZUKU, LaunchVia.ADB, dev)
        assertEquals(ConnectStatus.FAILED, out.status)
        assertEquals(StepId.SHIZUKU_RUNNING, out.failedStep)
        assertEquals("socket closed", out.steps[0].detail)
    }

    @Test fun everyStepHasAnExplanationNamingTheStep() {
        for (s in StepId.values()) for (w in listOf(true, false)) {
            val e = ConnectPlan.explain(s, "x", w)
            assertTrue("$s", e.startsWith("Stopped at \"${s.label}\": "))
            assertTrue("$s", e.length > 40)
        }
    }

    @Test fun theRunLogsEachStepAndNeverTheCode() {
        val log = ChannelLog()
        ConnectPlan.run(EMBEDDED_ONLY, LaunchVia.ADB, FakeDevice(none, failAt = StepId.ADB_CONNECT), log)
        val text = log.asText()
        assertTrue(text.contains("[connect] Developer options on: ok"))
        assertTrue(text.contains("Connect embedded adb (mDNS): FAILED boom"))
        assertFalse(text.contains("Round trip"))                                     // never reached
    }

    @Test fun launchViaParsing() {
        assertEquals(LaunchVia.ADB, LaunchVia.parse(null))
        assertEquals(LaunchVia.SHIZUKU, LaunchVia.parse("shizuku"))
        assertEquals(LaunchVia.ADB, LaunchVia.parse("nonsense"))
        assertNotNull(LaunchVia.values().firstOrNull { it.id == "adb" })
    }
}
