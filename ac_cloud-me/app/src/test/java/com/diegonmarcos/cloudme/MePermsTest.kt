package com.diegonmarcos.cloudme

import android.app.Application
import android.content.pm.PackageManager
import com.diegonmarcos.superapp.perms.PermState
import com.diegonmarcos.superapp.perms.Perms
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
}
