package com.diegonmarcos.superapp.system

import com.diegonmarcos.superapp.system.SpecialPerms.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** First-run ask: order (install unknown apps first, then home), Skip, and "don't ask again" after 2 skips. */
class FirstRunPermsTest {

    private val none: (Kind) -> Boolean = { false }

    @Test fun installUnknownComesFirst_thenHome() {
        val p = FirstRunPerms.Planner()
        assertEquals(listOf(Kind.INSTALL_UNKNOWN, Kind.HOME), FirstRunPerms.ORDER)
        assertEquals(Kind.INSTALL_UNKNOWN, p.next(none))
        p.markShown(Kind.INSTALL_UNKNOWN)
        assertEquals(Kind.HOME, p.next(none))
        p.markShown(Kind.HOME)
        assertNull("each step once per launch", p.next(none))
    }

    @Test fun grantedStepsAreNotAsked() {
        val p = FirstRunPerms.Planner()
        assertEquals(Kind.HOME, p.next { it == Kind.INSTALL_UNKNOWN })
        assertNull(p.next { true })
    }

    @Test fun laterLaunch_asksAgainWhileMissing() {
        val p = FirstRunPerms.Planner()
        p.markShown(Kind.INSTALL_UNKNOWN); p.markShown(Kind.HOME)
        p.newLaunch()
        assertEquals(Kind.INSTALL_UNKNOWN, p.next(none))
    }

    @Test fun twoSkips_meanDontAskAgain() {
        val p = FirstRunPerms.Planner()
        assertEquals(1, p.skip(Kind.INSTALL_UNKNOWN))
        assertFalse(p.dontAskAgain(Kind.INSTALL_UNKNOWN))
        p.newLaunch()
        assertEquals(Kind.INSTALL_UNKNOWN, p.next(none))
        assertEquals(2, p.skip(Kind.INSTALL_UNKNOWN))
        assertTrue(p.dontAskAgain(Kind.INSTALL_UNKNOWN))
        p.newLaunch()
        assertEquals("skipped twice → straight to home", Kind.HOME, p.next(none))
        p.skip(Kind.HOME); p.skip(Kind.HOME); p.newLaunch()
        assertNull(p.next(none))
    }

    @Test fun persistedSkipsSurviveARestart() {
        val p = FirstRunPerms.Planner(mutableMapOf(Kind.INSTALL_UNKNOWN to 2))
        assertEquals(Kind.HOME, p.next(none))
    }

    @Test fun shellAsksOnResume() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "app/src/main/AndroidManifest.xml").isFile }
        val src = File(root, "app/src/main/java/com/diegonmarcos/superapp/ShellActivity.kt").readText()
        assertTrue(Regex("override fun onResume\\(\\)[\\s\\S]{0,300}FirstRunPerms.maybeAsk\\(this\\)").containsMatchIn(src))
    }
}
