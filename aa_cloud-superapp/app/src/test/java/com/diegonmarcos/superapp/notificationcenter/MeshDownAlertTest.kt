package com.diegonmarcos.superapp.notificationcenter

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.core.FleetAlerts
import com.diegonmarcos.superapp.network.MeshDownModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #1120 The mesh-down alert is a normal alert with one button, built by the shared badge model (#1113), and it clears itself. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MeshDownAlertTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()

    private fun alert(key: String) = AlertStore.Alert("id", ctx.packageName, "t", "x", FleetAlerts.ERROR, "page:config/wg", key, 1L)

    @Test fun `the mesh alert carries exactly one Reconnect button, with an icon, from BadgeModel`() {
        val acts = MeshDownAlert.actionsFor(ctx, alert(MeshDownAlert.DEDUPE))
        assertEquals(listOf("reconnect"), acts.map { it.id })
        assertTrue("an icon-less button is a bug", acts.single().icon != 0)
        assertEquals("Reconnect", acts.single().label)
    }

    @Test fun `no other alert gets a button`() {
        assertTrue(MeshDownAlert.actionsFor(ctx, alert("backup.failed")).isEmpty())
    }

    @Test fun `stored under one dedupe key and withdrawn by it - the auto-clear`() {
        AlertStore.clear(ctx)
        AlertStore.add(ctx, ctx.packageName, FleetAlerts.Alert("Mesh down", "x", FleetAlerts.ERROR, "page:config/wg", MeshDownAlert.DEDUPE))
        AlertStore.add(ctx, ctx.packageName, FleetAlerts.Alert("Mesh down again", "y", FleetAlerts.ERROR, "page:config/wg", MeshDownAlert.DEDUPE))
        assertEquals("a repeat replaces, never stacks", 1, AlertStore.all(ctx).size)
        assertTrue(AlertStore.withdraw(ctx, ctx.packageName, MeshDownAlert.DEDUPE))
        assertTrue(AlertStore.all(ctx).isEmpty())
    }

    @Test fun `the Reconnect intent is a broadcast to our own receiver`() {
        val saved = org.robolectric.Shadows.shadowOf(MeshDownAlert.reconnectIntent(ctx))
        assertTrue(saved.isBroadcastIntent)
        assertEquals(MeshDownAlert.ReconnectReceiver::class.java.name, saved.savedIntent.component!!.className)
    }

    @Test fun `raised it shows in the notification centre with a Reconnect button, cleared it is gone`() {
        AlertStore.clear(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val r = MeshDownModel.Reading(true, true, false, "wg-mesh", listOf(MeshDownModel.PeerReading("gcp-proxy", null)), 300L)
        MeshDownAlert.raise(ctx, r, 1_000L)
        val child = nm.activeNotifications.firstOrNull { it.tag?.startsWith("alert:") == true }
        assertNotNull("the alert is not in the notification centre", child)
        assertEquals("Mesh down - gcp-proxy unreachable", child!!.notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(listOf("reconnect"), BadgeModel.of(child.notification).map { it.id })
        MeshDownAlert.clear(ctx)
        assertTrue("it clears itself", nm.activeNotifications.none { it.tag?.startsWith("alert:") == true })
        assertTrue(AlertStore.all(ctx).isEmpty())
    }
}
