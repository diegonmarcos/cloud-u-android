package com.diegonmarcos.clouddrive.mounts

import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import org.robolectric.RuntimeEnvironment
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Mount lifetime: the declared policy parses from THIS repository's build.json, the permission
 * checker asks for exactly what is missing, and the keeper is a sticky started service that does
 * not stop with the task (so destroying every Activity leaves the mounts up).
 */
@RunWith(RobolectricTestRunner::class)
class MountKeeperTest {
    private val root = File(System.getProperty("user.dir")).let { if (File(it, "build.json").isFile) it else it.parentFile }

    @Test fun policy_parses_from_build_json() {
        val decl = Json.parseToJsonElement(File(root, "build.json").readText()).jsonObject["mount_keeper"]!!.toString()
        val p = MountPolicy.parse(decl)
        assertTrue(p.enabled)
        assertTrue(p.backoffInitialSeconds in 1..p.backoffMaxSeconds)
        assertTrue(p.healthIntervalSeconds >= 15)
        assertTrue("samsung guide declared", p.guideFor("SAMSUNG") != null)
        assertEquals(MountKeeper.policy, MountPolicy.parse(decl))   // the baked copy is the same declaration
    }

    @Test fun absent_policy_means_keeper_off() { assertFalse(MountPolicy.parse("").enabled) }

    private val policy = MountPolicy(enabled = true, oemBatteryGuides = listOf(MountPolicy.OemGuide("samsung", "t", "s")))
    private fun facts(sdk: Int = 34, notif: Boolean = true, battery: Boolean = true, maker: String = "Google", oem: Boolean = false) =
        MountPermissions.Facts(sdk, notif, battery, maker, oem)

    @Test fun perms_all_ok() { assertEquals(emptyList<MountPermissions.Need>(), MountPermissions.missing(facts(), policy)) }
    @Test fun perms_notifications_only_from_api33() {
        assertEquals(listOf(MountPermissions.Need.NOTIFICATIONS), MountPermissions.missing(facts(notif = false), policy))
        assertEquals(emptyList<MountPermissions.Need>(), MountPermissions.missing(facts(sdk = 32, notif = false), policy))
    }
    @Test fun perms_battery_and_samsung_guide() {
        assertEquals(
            listOf(MountPermissions.Need.BATTERY_OPTIMIZATION, MountPermissions.Need.OEM_BATTERY_GUIDE),
            MountPermissions.missing(facts(battery = false, maker = "samsung"), policy),
        )
        assertEquals(emptyList<MountPermissions.Need>(), MountPermissions.missing(facts(maker = "samsung", oem = true), policy))
    }

    @Test fun keeper_is_sticky_foreground_and_survives_task_removal() {
        val ctx = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildService(MountKeeperService::class.java, Intent(ctx, MountKeeperService::class.java)).create()
        val svc = controller.get()
        assertEquals(Service.START_STICKY, svc.onStartCommand(Intent(ctx, MountKeeperService::class.java), 0, 1))
        assertTrue("foreground with a notification", shadowOf(svc).isForegroundStopped.not() && shadowOf(svc).lastForegroundNotification != null)
        svc.onTaskRemoved(Intent())                 // the app's task / every Activity is gone
        assertFalse("the keeper must not stop itself with the UI", shadowOf(svc).isStoppedBySelf)
        val info = ctx.packageManager.getServiceInfo(ComponentName(ctx, MountKeeperService::class.java), PackageManager.GET_META_DATA)
        assertEquals("stopWithTask must be false", 0, info.flags and ServiceInfo.FLAG_STOP_WITH_TASK)
        assertFalse(info.exported)
        controller.destroy()
    }
}
