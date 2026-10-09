package com.diegonmarcos.superapp.appstore

import com.diegonmarcos.superapp.appstore.PermsGrant.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Store > Access > Android Perms: filter, A-Z sort, the grant plan and its summary, against a fake channel. */
class PermsGrantTest {

    private val cam = "android.permission.CAMERA"
    private val mic = "android.permission.RECORD_AUDIO"
    private val usage = "android.permission.PACKAGE_USAGE_STATS"
    private val overlay = "android.permission.SYSTEM_ALERT_WINDOW"
    private val files = "android.permission.MANAGE_EXTERNAL_STORAGE"
    private val internet = "android.permission.INTERNET"

    private val kinds = mapOf(cam to 1, mic to 1, usage to 2, overlay to 2, files to 2, internet to 0)
    private val kindOf: (String) -> Kind = { PermsGrant.kind(it, kinds[it] ?: -1) }

    private fun app(pkg: String, label: String, ours: Boolean, declared: List<String>, granted: Set<String> = emptySet()) =
        PermsGrant.App(pkg, label, ours, declared, granted)

    private val zed = app("x.zed", "Zed", true, listOf(cam, internet))
    private val alpha = app("x.alpha", "alpha", true, listOf(cam, mic, usage, overlay, files), setOf(mic))
    private val mid = app("x.mid", "Mid", true, listOf(mic))
    private val stranger = app("com.third", "Aaa Third", false, listOf(cam, mic))
    private val all = listOf(zed, alpha, mid, stranger)

    private class FakeChannel(var isUp: Boolean = true, val reply: (String) -> String? = { "" }) : PermsGrant.GrantChannel {
        val ran = ArrayList<String>()
        override fun up() = isUp
        override fun exec(command: String): String? { ran += command; return reply(command) }
    }

    @Test fun `filter All keeps every app, a permission keeps only its declarers`() {
        assertEquals(4, PermsGrant.view(all, PermsGrant.ALL).size)
        assertEquals(listOf("com.third", "x.alpha", "x.zed"), PermsGrant.view(all, cam).map { it.pkg })
        assertEquals(listOf("x.mid"), PermsGrant.view(listOf(zed, mid), mic).map { it.pkg })
    }

    @Test fun `options start with All and list only permissions worth a filter`() {
        val o = PermsGrant.filterOptions(all, kindOf)
        assertEquals(PermsGrant.ALL, o.first())
        assertTrue(cam in o && mic in o && usage in o)
        assertFalse(internet in o)
        assertEquals(o.size, o.distinct().size)
    }

    @Test fun `dropdown search narrows by name and keeps All`() {
        val o = PermsGrant.filterOptions(all, kindOf)
        assertEquals(listOf(PermsGrant.ALL, cam), PermsGrant.search(o, "camera"))
        assertEquals(o, PermsGrant.search(o, "  "))
    }

    @Test fun `apps are sorted alphabetically by name, case-insensitive`() {
        assertEquals(listOf("Aaa Third", "alpha", "Mid", "Zed"), PermsGrant.view(all, PermsGrant.ALL).map { it.label })
    }

    @Test fun `plan holds only fleet-signed packages and only shell-grantable permissions`() {
        val p = PermsGrant.plan(all, kindOf)
        assertTrue(p.steps.none { it.pkg == "com.third" })
        assertTrue(p.steps.none { it.perm == internet || it.perm == usage || it.perm == overlay })
        assertEquals(setOf("x.alpha:$cam", "x.alpha:$files", "x.zed:$cam", "x.mid:$mic"), p.steps.map { it.pkg + ":" + it.perm }.toSet())
        assertEquals(1, p.alreadyGranted)                       // alpha already holds RECORD_AUDIO
        assertEquals("Grant 4 permissions to 3 fleet apps?", p.confirmText())
    }

    @Test fun `commands - pm grant for runtime, appops for app-ops`() {
        val steps = PermsGrant.plan(listOf(alpha), kindOf).steps.associateBy { it.perm }
        assertEquals("pm grant x.alpha $cam", steps.getValue(cam).command())
        assertEquals("appops set x.alpha MANAGE_EXTERNAL_STORAGE allow", steps.getValue(files).command())
    }

    @Test fun `special access goes to needs-you with its settings shortcut, never granted`() {
        val p = PermsGrant.plan(listOf(alpha), kindOf)
        assertEquals(setOf(usage, overlay), p.needsYou.map { it.perm }.toSet())
        assertEquals("android.settings.USAGE_ACCESS_SETTINGS", p.needsYou.first { it.perm == usage }.action)
        val ch = FakeChannel(); PermsGrant.run(p, ch)
        assertTrue(ch.ran.none { "PACKAGE_USAGE_STATS" in it || "SYSTEM_ALERT_WINDOW" in it })
    }

    @Test fun `grant button is disabled with its message when the channel is down, and nothing runs`() {
        assertEquals("needs Wireless Dbg/channel up", PermsGrant.disabledReason(false))
        assertNull(PermsGrant.disabledReason(true))
        val ch = FakeChannel(isUp = false)
        val s = PermsGrant.run(PermsGrant.plan(all, kindOf), ch)
        assertTrue(ch.ran.isEmpty())
        assertEquals(0, s.granted)
    }

    @Test fun `summary counts granted, already granted, need you, and failures with reasons`() {
        val p = PermsGrant.plan(all, kindOf)
        val ch = FakeChannel { if ("x.zed" in it) "Exception occurred: Operation not allowed" else "" }
        val log = ArrayList<String>()
        val s = PermsGrant.run(p, ch, log::add)
        assertEquals(3, s.granted); assertEquals(1, s.alreadyGranted); assertEquals(2, s.needsYou)
        assertEquals(1, s.failures.size)
        assertEquals("x.zed", s.failures[0].step.pkg)
        assertTrue("Exception" in s.failures[0].reason)
        assertEquals("3 granted, 1 already granted, 2 need you, 1 failed", s.text())
        assertEquals(4, log.size)
        assertTrue(log.count { it.startsWith("granted ") } == 3)
        assertEquals(4, ch.ran.size)
    }

    @Test fun `no answer from the channel is a failure, not a grant`() {
        val s = PermsGrant.run(PermsGrant.plan(listOf(mid), kindOf), FakeChannel { null })
        assertEquals(0, s.granted); assertEquals("no channel answered", s.failures.single().reason)
    }
}
