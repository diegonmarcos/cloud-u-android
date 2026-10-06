package com.diegonmarcos.superapp.updater

import com.diegonmarcos.superapp.updater.PassLedger.Outcome
import com.diegonmarcos.superapp.updater.PassLedger.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #894 One summary per pass, not one alert per app; the same outcome is not re-raised. */
class PassLedgerTest {

    private fun pass(vararg r: Triple<Outcome, String, String>): State =
        r.fold(PassLedger.begin(State(), 1_000L)) { s, (o, pkg, v) -> PassLedger.record(s, o, pkg, v, "why") }

    @Test fun `a pass of installs and taps is ONE title`() {
        val s = pass(Triple(Outcome.INSTALLED, "a.cloudc3", "1"), Triple(Outcome.INSTALLED, "a.cloudnav", "2"),
            Triple(Outcome.NEEDS_TAP, "a.cloudcalc", "3"))
        assertEquals("2 updated, 1 needs a tap", PassLedger.title(s))
    }

    @Test fun `several taps read as plural, failures are counted`() {
        val s = pass(Triple(Outcome.NEEDS_TAP, "a.x", "1"), Triple(Outcome.NEEDS_TAP, "a.y", "1"), Triple(Outcome.FAILED, "a.z", "1"))
        assertEquals("2 need a tap, 1 failed", PassLedger.title(s))
    }

    @Test fun `a pass that did nothing says nothing`() {
        assertNull(PassLedger.title(PassLedger.begin(State(), 1L)))
        assertFalse(PassLedger.shouldRaise(PassLedger.begin(State(), 1L)))
    }

    @Test fun `a tap answered later moves the package from needs-a-tap to updated`() {
        var s = pass(Triple(Outcome.NEEDS_TAP, "a.cloudcalc", "3"))
        s = PassLedger.record(s, Outcome.INSTALLED, "a.cloudcalc", "3")
        assertEquals("1 updated", PassLedger.title(s))
        assertTrue(s.needTap.isEmpty())
    }

    @Test fun `a package is only ever in one bucket`() {
        var s = pass(Triple(Outcome.INSTALLED, "a.x", "1"))
        s = PassLedger.record(s, Outcome.FAILED, "a.x", "2", "boom")
        assertEquals(1, s.installed.size + s.needTap.size + s.failed.size)
    }

    @Test fun `the same summary is not raised twice, a changed one is`() {
        val s = pass(Triple(Outcome.NEEDS_TAP, "a.cloudcalc", "3"))
        assertTrue(PassLedger.shouldRaise(s))
        val shown = s.copy(raised = PassLedger.signature(s))
        // next pass: same package, same build, same reason
        val again = PassLedger.record(PassLedger.begin(shown, 9_000L), Outcome.NEEDS_TAP, "a.cloudcalc", "3")
        assertFalse("an identical outcome must not notify again", PassLedger.shouldRaise(again))
        // a new build of the same package is news
        val newer = PassLedger.record(PassLedger.begin(shown, 9_000L), Outcome.NEEDS_TAP, "a.cloudcalc", "4")
        assertTrue(PassLedger.shouldRaise(newer))
    }

    @Test fun `identical failures dedupe by package build and reason`() {
        val a = PassLedger.record(State(), Outcome.FAILED, "a.x", "1", "no  channel\n")
        val b = PassLedger.record(State(), Outcome.FAILED, "a.x", "1", "no channel")
        assertEquals(a.failed, b.failed)
        val c = PassLedger.record(State(), Outcome.FAILED, "a.x", "1", "other reason")
        assertTrue(a.failed != c.failed)
    }

    @Test fun `an emptied summary is withdrawn once`() {
        val shown = State(raised = "x||")
        assertTrue(PassLedger.shouldWithdraw(PassLedger.begin(shown, 1L)))
        assertFalse(PassLedger.shouldWithdraw(PassLedger.begin(State(), 1L)))
    }

    @Test fun `results outside the pass window are not part of it`() {
        val s = PassLedger.begin(State(), 1_000L)
        assertTrue(PassLedger.inWindow(s, 1_000L + PassLedger.WINDOW_MS))
        assertFalse(PassLedger.inWindow(s, 1_001L + PassLedger.WINDOW_MS))
        assertFalse(PassLedger.inWindow(State(), 5L))
    }

    @Test fun `the text names the apps that need you and the store to open`() {
        val s = pass(Triple(Outcome.NEEDS_TAP, "com.diegonmarcos.cloudcalc", "3"))
        assertEquals("cloudcalc. Open Cloud Store", PassLedger.text(s, "Cloud Store"))
    }
}
