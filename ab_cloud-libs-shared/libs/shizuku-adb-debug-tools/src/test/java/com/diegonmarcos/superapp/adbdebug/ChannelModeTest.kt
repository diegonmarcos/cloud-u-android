package com.diegonmarcos.superapp.adbdebug

import com.diegonmarcos.superapp.adbdebug.ChannelMode.AUTO
import com.diegonmarcos.superapp.adbdebug.ChannelMode.EMBEDDED_ONLY
import com.diegonmarcos.superapp.adbdebug.ChannelMode.SHIZUKU
import com.diegonmarcos.superapp.adbdebug.ChannelMode.LOCAL_SERVER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The owner's "Privileged channel": which channels run commands, which may only launch the server. */
class ChannelModeTest {

    private val ladder = listOf("embedded-adb", "local-server", "shizuku")

    @Test fun modeToChannelOrder() {
        assertEquals(listOf("local-server"), ChannelSelector.execOrder(LOCAL_SERVER, ladder))
        assertEquals(ladder, ChannelSelector.execOrder(AUTO, ladder))
        assertEquals(listOf("embedded-adb"), ChannelSelector.execOrder(EMBEDDED_ONLY, ladder))
        assertEquals(listOf("shizuku"), ChannelSelector.execOrder(SHIZUKU, ladder))
        assertEquals(emptyList<String>(), ChannelSelector.bootstrapSources(SHIZUKU, ladder))
        assertEquals(listOf("embedded-adb", "shizuku"), ChannelSelector.bootstrapSources(LOCAL_SERVER, ladder))
        assertEquals(emptyList<String>(), ChannelSelector.bootstrapSources(EMBEDDED_ONLY, ladder))
    }

    @Test fun parseAndDefaults() {
        assertEquals(LOCAL_SERVER, ChannelMode.parse(null, LOCAL_SERVER))
        assertEquals(AUTO, ChannelMode.parse("auto", LOCAL_SERVER))
        assertEquals(EMBEDDED_ONLY, ChannelMode.parse("embedded", LOCAL_SERVER))
        assertEquals(SHIZUKU, ChannelMode.parse("shizuku", LOCAL_SERVER))
        assertEquals(4, ChannelMode.values().size)
        assertEquals(AUTO, ChannelMode.parse("garbage", AUTO))
    }

    /** A fake world: the server is down or up; embedded adb is connected; every exec is logged. */
    private class World(var serverUp: Boolean, val adbUp: Boolean, val relaunchWorks: Boolean = true) {
        val execLog = ArrayList<String>()      // commands run over a channel, as "channel:command"
        val relaunchVia = ArrayList<String>()  // channels handed to the relaunch
        var shizukuUp = false
        fun usable(n: String) = when (n) { "local-server" -> serverUp; "embedded-adb" -> adbUp; "shizuku" -> shizukuUp; else -> false }
        fun relaunch(sources: List<String>): Boolean {
            relaunchVia += sources
            val via = sources.firstOrNull { usable(it) } ?: return false
            execLog += "$via:launch-server"    // the ONE command adb may run in server mode
            if (relaunchWorks) serverUp = true
            return serverUp
        }
        fun select(mode: ChannelMode) = ChannelSelector.select(mode, listOf("embedded-adb", "local-server", "shizuku"),
            { it }, ::usable, ::relaunch)
    }

    @Test fun serverModeUsesTheServerAndNeverExecsOverAdb() {
        val w = World(serverUp = true, adbUp = true)
        assertEquals("local-server", w.select(LOCAL_SERVER))
        assertTrue("a live server needs no relaunch", w.execLog.isEmpty() && w.relaunchVia.isEmpty())
    }

    @Test fun serverModeRelaunchesADeadServerThroughAdbOnlyThenSwitchesToIt() {
        val w = World(serverUp = false, adbUp = true)
        assertEquals("local-server", w.select(LOCAL_SERVER))          // the answer is the server, never embedded-adb
        assertEquals(listOf("embedded-adb:launch-server"), w.execLog)  // adb ran the bootstrap line and nothing else
        assertEquals(listOf("embedded-adb", "shizuku"), w.relaunchVia)
    }

    @Test fun serverModeIsDownWhenNeitherTheServerNorTheBootstrapWorks() {
        assertNull(World(serverUp = false, adbUp = false).select(LOCAL_SERVER))
        // adb is up but cannot start the server: still down, adb is NOT used to run commands instead
        val w = World(serverUp = false, adbUp = true, relaunchWorks = false)
        assertNull(w.select(LOCAL_SERVER))
        assertEquals(listOf("embedded-adb:launch-server"), w.execLog)
    }

    @Test fun autoKeepsTheOldOrderAndRelaunchesFirst() {
        val up = World(serverUp = true, adbUp = true)
        assertEquals("embedded-adb", up.select(AUTO))
        val down = World(serverUp = false, adbUp = true)
        assertEquals("embedded-adb", down.select(AUTO))
        assertTrue("auto relaunches the server when it is down", down.serverUp)
        assertEquals("local-server", World(serverUp = true, adbUp = false).select(AUTO))
    }

    @Test fun embeddedOnlySkipsTheServer() {
        val w = World(serverUp = true, adbUp = false)
        assertNull(w.select(EMBEDDED_ONLY))
        assertTrue(w.relaunchVia.isEmpty())
        assertEquals("embedded-adb", World(serverUp = true, adbUp = true).select(EMBEDDED_ONLY))
    }

    @Test fun shizukuModeUsesOnlyTheShizukuBinder() {
        val w = World(serverUp = true, adbUp = true).apply { shizukuUp = true }
        assertEquals("shizuku", w.select(SHIZUKU))
        // Shizuku down: the server and adb are NOT used instead, and nothing is relaunched.
        val down = World(serverUp = true, adbUp = true)
        assertNull(down.select(SHIZUKU))
        assertTrue(down.relaunchVia.isEmpty() && down.execLog.isEmpty())
    }

    @Test fun allFourModesPickDifferentRoutesFromTheSameWorld() {
        fun route(m: ChannelMode) = World(serverUp = true, adbUp = true).apply { shizukuUp = true }.select(m)
        assertEquals(listOf("local-server", "embedded-adb", "shizuku", "embedded-adb"),
            listOf(LOCAL_SERVER, EMBEDDED_ONLY, SHIZUKU, AUTO).map(::route))
    }

    @Test fun shizukuStateAndLabels() {
        assertEquals(ShizukuState.NOT_INSTALLED, ShizukuState.of(installed = false, running = false, granted = false))
        assertEquals(ShizukuState.NOT_RUNNING, ShizukuState.of(installed = true, running = false, granted = false))
        assertEquals(ShizukuState.PERMISSION_NEEDED, ShizukuState.of(installed = true, running = true, granted = false))
        assertEquals(ShizukuState.UP, ShizukuState.of(installed = true, running = true, granted = true))
        assertEquals("Shizuku: up", ChannelSelector.shizukuLabel(ShizukuState.UP))
        assertEquals("Shizuku: not running", ChannelSelector.shizukuLabel(ShizukuState.NOT_RUNNING))
        assertEquals("Shizuku: permission needed", ChannelSelector.shizukuLabel(ShizukuState.PERMISSION_NEEDED))
        assertEquals("Shizuku: not installed", ChannelSelector.shizukuLabel(ShizukuState.NOT_INSTALLED))
    }

    @Test fun shizukuModeProbeAndTap() {
        class B(val st: ShizukuState, val answers: Boolean) : ControlStatus.Backend {
            override fun paired() = false
            override fun id() = if (answers) "shizuku" to "uid=2000(shell)" else null
            override fun shizukuState() = st
        }
        for (st in listOf(ShizukuState.NOT_INSTALLED, ShizukuState.NOT_RUNNING, ShizukuState.PERMISSION_NEEDED)) {
            val s = ControlStatus.Probe(B(st, answers = false)).get()
            assertEquals(st, s.shizuku)
            val tap = ControlStatus.channelAction(s, false, SHIZUKU)
            assertEquals(if (st == ShizukuState.PERMISSION_NEEDED) ControlStatus.Action.REQUEST_SHIZUKU else ControlStatus.Action.OPEN_SHIZUKU, tap)
        }
        val up = ControlStatus.Probe(B(ShizukuState.UP, answers = true)).get()
        assertEquals(ControlStatus.Channel.UP, up.state); assertEquals(ShizukuState.UP, up.shizuku)
        // "up" that never answered is not up
        assertEquals(ShizukuState.NOT_RUNNING, ControlStatus.Probe(B(ShizukuState.UP, answers = false)).get().shizuku)
    }

    @Test fun statusLabel() {
        assertEquals("Wireless Dbg: up (local-server)", ChannelSelector.label("local-server", down = false))
        assertEquals("Wireless Dbg: up (embedded-adb)", ChannelSelector.label("embedded-adb", down = false))
        assertEquals("Wireless Dbg: down - Reconnect", ChannelSelector.label(null, down = true))
        assertEquals("Wireless Dbg: not paired - Pair", ChannelSelector.label(null, down = false))
    }
}
