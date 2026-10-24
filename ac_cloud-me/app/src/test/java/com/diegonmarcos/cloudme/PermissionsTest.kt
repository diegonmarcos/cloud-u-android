package com.diegonmarcos.cloudme

import android.app.Application
import android.content.pm.PackageManager
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #1150 Configs > Permissions and the start-up rule: ask ONCE, then a banner to the settings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PermissionsTest {

    private fun perm(name: String, required: Boolean = true) = DeclaredPermission(name, "android.permission.$name", required, 1)
    private fun state(p: DeclaredPermission, granted: Boolean, asked: Boolean, rationale: Boolean = false) = PermState(p, granted, asked, rationale)

    @Test
    fun `every permission the page lists is declared by the manifest`() {
        val ctx = RuntimeEnvironment.getApplication()
        val manifest = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
        val listed = Permissions.declared()
        assertTrue("no runtime permission is declared", listed.isNotEmpty())
        for (p in listed) assertTrue("${p.perm} is listed but not in the manifest", p.perm in manifest)
    }

    @Test
    fun `a missing required permission that was never asked for is asked for in one prompt`() {
        val d = Permissions.gate(listOf(state(perm("A"), granted = false, asked = false), state(perm("B"), granted = false, asked = false)))
        assertEquals(listOf("android.permission.A", "android.permission.B"), d.ask)
        assertTrue(d.banner.isEmpty())
    }

    @Test
    fun `once asked and still denied it is never asked again, the banner points at the settings`() {
        val d = Permissions.gate(listOf(state(perm("A"), granted = false, asked = true), state(perm("B"), granted = true, asked = true)))
        assertTrue("a denied permission is asked for again at start", d.ask.isEmpty())
        assertEquals(listOf("A"), d.banner)
    }

    @Test
    fun `granted and optional permissions ask for nothing and show no banner`() {
        val d = Permissions.gate(listOf(state(perm("A"), granted = true, asked = false), state(perm("B", required = false), granted = false, asked = false)))
        assertTrue(d.ask.isEmpty())
        assertTrue(d.banner.isEmpty())
    }

    @Test
    fun `blocked means denied after asking with no rationale left`() {
        assertTrue(state(perm("A"), granted = false, asked = true, rationale = false).blocked)
        assertFalse(state(perm("A"), granted = false, asked = true, rationale = true).blocked)
        assertFalse(state(perm("A"), granted = false, asked = false).blocked)
        assertFalse(state(perm("A"), granted = true, asked = true).blocked)
    }
}
