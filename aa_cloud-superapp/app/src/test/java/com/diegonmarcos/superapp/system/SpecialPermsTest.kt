package com.diegonmarcos.superapp.system

import com.diegonmarcos.superapp.system.SpecialPerms.Facts
import com.diegonmarcos.superapp.system.SpecialPerms.Kind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Configs › Permissions rows + first-run: the raw platform facts → granted / not granted. */
@RunWith(RobolectricTestRunner::class)
class SpecialPermsTest {

    private val us = "com.diegonmarcos.superapp"
    private fun f(sdk: Int, can: Boolean = false, role: Boolean? = null, home: String? = null) = Facts(sdk, us, can, role, home)

    @Test fun installUnknown_followsCanRequestPackageInstalls() {
        assertFalse(SpecialPerms.granted(Kind.INSTALL_UNKNOWN, f(34, can = false)))
        assertTrue(SpecialPerms.granted(Kind.INSTALL_UNKNOWN, f(34, can = true)))
        // Below O there is no per-source toggle: the manifest permission is the grant.
        assertTrue(SpecialPerms.granted(Kind.INSTALL_UNKNOWN, f(25, can = false)))
    }

    @Test fun home_usesRoleOn29Plus_andResolveActivityBelow() {
        assertTrue(SpecialPerms.granted(Kind.HOME, f(34, role = true)))
        // The role is authoritative on 29+, even if a stale resolve says us.
        assertFalse(SpecialPerms.granted(Kind.HOME, f(34, role = false, home = us)))
        assertTrue(SpecialPerms.granted(Kind.HOME, f(28, home = us)))
        assertFalse(SpecialPerms.granted(Kind.HOME, f(28, home = "com.android.launcher3")))
        // RoleManager without ROLE_HOME (null) → the CATEGORY_HOME resolve.
        assertTrue(SpecialPerms.granted(Kind.HOME, f(30, role = null, home = us)))
        assertFalse(SpecialPerms.granted(Kind.HOME, f(30, role = null, home = null)))
    }

    @Test fun stateText_saysGrantedOrNot() {
        for (k in Kind.values()) {
            assertTrue(SpecialPerms.stateText(k, true).startsWith("✓"))
            assertTrue(SpecialPerms.stateText(k, false).startsWith("◯"))
        }
    }

    private fun app(): File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "build.json").isFile && File(it, "app/src/main/AndroidManifest.xml").isFile }

    @Test fun declaredInBuildJsonAndManifest() {
        val root = app()
        val special = JSONObject(File(root, "build.json").readText())
            .getJSONObject("ui").getJSONObject("permissions").getJSONArray("special")
        assertEquals(Kind.values().map { it.name }, (0 until special.length()).map { special.getJSONObject(it).getString("key") })
        for (i in 0 until special.length()) {
            val o = special.getJSONObject(i)
            assertEquals(SpecialPerms.label(Kind.valueOf(o.getString("key"))), o.getString("label"))
        }
        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android.permission.REQUEST_INSTALL_PACKAGES"))
        // The SuperApp must be a HOME candidate for ROLE_HOME to be requestable.
        assertTrue(manifest.contains("android.intent.category.HOME"))
    }

    @Test fun permissionsPage_rendersTheseRows_andRechecksOnResume() {
        val src = File(app(), "app/src/main/java/com/diegonmarcos/superapp/configs/PermissionsFragment.kt").readText()
        assertTrue("rows come from SpecialPerms", src.contains("SpecialPerms.granted(k, f)"))
        assertTrue("one-tap action requests the grant", src.contains("SpecialPerms.request(requireActivity(), k)"))
        assertTrue("re-checked on resume", Regex("override fun onResume\\(\\)[\\s\\S]{0,400}SpecialPerms.snapshot").containsMatchIn(src))
    }
}
