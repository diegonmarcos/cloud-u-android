package com.diegonmarcos.cloudme

import android.app.Application
import android.content.pm.PackageManager
import com.diegonmarcos.superapp.perms.PermState
import com.diegonmarcos.superapp.perms.Perms
import com.diegonmarcos.superapp.uikit.KitState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Configs > Perms: the baked declaration, the manifest, and the start-up / Grant all walks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MePermsTest {

    private fun rt(id: String, s: PermState) = PermRow(id, id, "", RowKind.RUNTIME, "android.permission.$id", s)
    private fun sp(id: String, s: PermState) = PermRow(id, id, "", RowKind.SPECIAL, "", s)
    private fun hc(p: String, s: PermState) = PermRow(p, p, "", RowKind.HEALTH, "android.permission.health.$p", s)
    private val fresh = AskMemory(0, emptySet())

    @Test fun `every declared permission is in the merged manifest and every entry is a catalogue row`() {
        val ctx = RuntimeEnvironment.getApplication()
        val manifest = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
        val d = MePermDecl.baked
        assertTrue("nothing declared", d.entries.isNotEmpty() && d.healthRead.isNotEmpty() && d.healthWrite.isNotEmpty())
        val cat = Perms.catalogue(ctx)
        for ((id, _) in d.entries) {
            val e = cat.byId(id); assertTrue("$id is not a libs:perms row", e != null)
            if (e!!.permission.isNotEmpty()) assertTrue("${e.permission} not in the manifest", e.permission in manifest)
        }
        for (p in d.health + d.install.map { it.perm }) assertTrue("$p not in the manifest", p in manifest)
    }

    @Test fun `rows list every declared permission once, health rows not applicable without Health Connect`() {
        val ctx = RuntimeEnvironment.getApplication()
        val d = MePermDecl.baked
        val rows = MePerms.rows(ctx, null, healthAvailable = false, healthGranted = emptySet())
        assertEquals(d.entries.size + d.health.size + d.install.size, rows.size)
        assertTrue(rows.filter { it.kind == RowKind.HEALTH }.all { it.state == PermState.NOT_APPLICABLE })
    }

    @Test fun `start-up asks denied runtime ones in one batch, never a permanently denied one`() {
        val steps = MePermPlan.startup(listOf(rt("A", PermState.DENIED), rt("B", PermState.PERMANENTLY_DENIED), rt("C", PermState.DENIED), rt("D", PermState.GRANTED)), fresh)
        assertEquals(listOf(PermStep.Runtime(listOf("android.permission.A", "android.permission.C"))), steps)
    }

    @Test fun `start-up asks Health Connect until it was declined twice`() {
        val rows = listOf(hc("READ_STEPS", PermState.DENIED), hc("WRITE_STEPS", PermState.GRANTED))
        assertEquals(listOf(PermStep.Health(setOf("android.permission.health.READ_STEPS"))), MePermPlan.startup(rows, AskMemory(1, emptySet())))
        assertTrue(MePermPlan.startup(rows, AskMemory(MePermPlan.HEALTH_MAX_ASKS, emptySet())).isEmpty())
    }

    @Test fun `start-up opens a special page once, never again`() {
        val rows = listOf(sp("battery_opt", PermState.DENIED), sp("overlay", PermState.GRANTED), sp("samsung", PermState.UNKNOWN))
        assertEquals(listOf(PermStep.Settings("battery_opt")), MePermPlan.startup(rows, fresh))
        assertTrue(MePermPlan.startup(rows, AskMemory(0, setOf("battery_opt"))).isEmpty())
    }

    @Test fun `nothing missing asks nothing`() {
        assertTrue(MePermPlan.startup(listOf(rt("A", PermState.GRANTED), hc("READ_STEPS", PermState.GRANTED), sp("x", PermState.NOT_APPLICABLE)), fresh).isEmpty())
    }

    @Test fun `grant all walks every missing one, whatever was asked before`() {
        val rows = listOf(rt("A", PermState.DENIED), rt("B", PermState.PERMANENTLY_DENIED), hc("READ_SLEEP", PermState.DENIED),
            sp("battery_opt", PermState.DENIED), sp("overlay", PermState.GRANTED), sp("x", PermState.NOT_APPLICABLE))
        assertEquals(listOf(
            PermStep.Runtime(listOf("android.permission.A")),
            PermStep.Health(setOf("android.permission.health.READ_SLEEP")),
            PermStep.Settings("B"),
            PermStep.Settings("battery_opt"),
        ), MePermPlan.grantAll(rows))
    }

    @Test fun `a row button opens the dialog, a settings page, Health Connect, or nothing`() {
        val h1 = hc("READ_STEPS", PermState.DENIED); val h2 = hc("READ_SLEEP", PermState.DENIED)
        val all = listOf(h1, h2)
        assertEquals(PermStep.Runtime(listOf("android.permission.A")), MePermPlan.forRow(rt("A", PermState.DENIED), all))
        assertEquals(PermStep.Settings("A"), MePermPlan.forRow(rt("A", PermState.PERMANENTLY_DENIED), all))
        assertEquals(PermStep.Health(setOf(h1.permission, h2.permission)), MePermPlan.forRow(h1, all))
        assertNull(MePermPlan.forRow(PermRow("i", "i", "", RowKind.INSTALL, "p", PermState.GRANTED), all))
        assertNull(MePermPlan.forRow(sp("x", PermState.NOT_APPLICABLE), all))
    }

    @Test fun `the banner names missing runtime ones and counts Health Connect`() {
        assertEquals(listOf("A", "Health Connect (2)"), MePermPlan.banner(listOf(rt("A", PermState.DENIED), hc("R1", PermState.DENIED), hc("R2", PermState.PERMANENTLY_DENIED), sp("s", PermState.DENIED))))
        assertEquals("Read active calories burned", MePermPlan.healthLabel("android.permission.health.READ_ACTIVE_CALORIES_BURNED"))
    }

    // ── the page (MePermView) ──

    @Test fun `each state has its chip, green granted, amber missing, grey not applicable`() {
        assertEquals(PermChip.GRANTED, MePermView.chip(rt("A", PermState.GRANTED)))
        assertEquals(PermChip.MISSING, MePermView.chip(rt("A", PermState.DENIED)))
        assertEquals(PermChip.MISSING, MePermView.chip(rt("A", PermState.PERMANENTLY_DENIED)))
        assertEquals(PermChip.NOT_APPLICABLE, MePermView.chip(sp("x", PermState.NOT_APPLICABLE)))
        assertEquals(KitState.OK, PermChip.GRANTED.kit); assertEquals(KitState.WARN, PermChip.MISSING.kit); assertEquals(KitState.IDLE, PermChip.NOT_APPLICABLE.kit)
    }

    @Test fun `progress counts runtime and special rows and Health Connect once, not install or not applicable`() {
        val rows = listOf(rt("A", PermState.GRANTED), rt("B", PermState.DENIED), sp("s", PermState.GRANTED), sp("n", PermState.NOT_APPLICABLE),
            sp("u", PermState.UNKNOWN), hc("R1", PermState.GRANTED), hc("R2", PermState.DENIED), hc("R3", PermState.DENIED),
            PermRow("i", "i", "", RowKind.INSTALL, "p", PermState.GRANTED))
        val p = MePermView.progress(rows)
        assertEquals(MePermView.Progress(2, 4), p)
        assertEquals("2 of 4 granted", p.label); assertEquals(2, p.missing)
        assertEquals(MePermView.Progress(1, 1), MePermView.progress(listOf(hc("R1", PermState.GRANTED), hc("R2", PermState.GRANTED))))
    }

    @Test fun `Health Connect is one state, missing while any type is, not applicable without it`() {
        assertEquals(PermState.DENIED, MePermView.healthState(listOf(hc("R1", PermState.GRANTED), hc("R2", PermState.DENIED))))
        assertEquals(PermState.GRANTED, MePermView.healthState(listOf(hc("R1", PermState.GRANTED))))
        assertEquals(PermState.NOT_APPLICABLE, MePermView.healthState(listOf(hc("R1", PermState.NOT_APPLICABLE))))
    }

    @Test fun `unused permissions sit collapsed under Not used yet, after the four sections`() {
        val unused = rt("U", PermState.DENIED).copy(used = false)
        val rows = listOf(rt("A", PermState.DENIED), unused, sp("s", PermState.GRANTED), hc("R1", PermState.DENIED),
            PermRow("i", "i", "", RowKind.INSTALL, "p", PermState.GRANTED))
        val g = MePermView.groups(rows)
        assertEquals(listOf("Runtime", "Special access", "Health Connect", "Granted at install", MePermView.NOT_USED), g.map { it.title })
        assertEquals(listOf("A"), g[0].rows.map { it.id })
        assertEquals(listOf(unused), g.last().rows); assertTrue(g.last().collapsed)
        assertTrue(g.dropLast(1).none { it.collapsed })
        assertEquals(listOf("Runtime"), MePermView.groups(listOf(rt("A", PermState.GRANTED))).map { it.title })
    }

    @Test fun `the row line is the reason, or how to fix a permanent denial, or why it does not apply`() {
        val r = PermRow("a", "A", "why", RowKind.RUNTIME, "p", PermState.DENIED, "detail")
        assertEquals("why", MePermView.reasonLine(r))
        assertEquals("Denied twice: allow it in Settings", MePermView.reasonLine(r.copy(state = PermState.PERMANENTLY_DENIED)))
        assertEquals("detail", MePermView.reasonLine(r.copy(state = PermState.NOT_APPLICABLE)))
        assertEquals("Open", MePermView.actionWord(PermStep.Settings("a"))); assertEquals("Grant", MePermView.actionWord(PermStep.Runtime(listOf("p"))))
    }

    @Test fun `the banner says what is missing in one sentence`() {
        assertEquals("", MePermView.bannerText(emptyList()))
        assertEquals("Notifications is not granted", MePermView.bannerText(listOf("Notifications")))
        assertEquals("5 permissions missing: A, B, C and 2 more", MePermView.bannerText(listOf("A", "B", "C", "D", "E")))
    }

    @Test fun `every declared row has a fleet icon and the unused ones are flagged`() {
        val d = MePermDecl.baked
        for (e in d.entries) assertTrue("${e.id} has no libs:ui-icons icon", com.diegonmarcos.superapp.uiicons.UiIcons.has(e.icon))
        for (i in d.install) assertTrue("${i.perm} has no libs:ui-icons icon", com.diegonmarcos.superapp.uiicons.UiIcons.has(i.icon))
        assertTrue(com.diegonmarcos.superapp.uiicons.UiIcons.has(d.healthIcon))
        assertTrue("nothing flagged unused", d.entries.any { !it.used }); assertTrue(d.entries.any { it.used })
    }
}
