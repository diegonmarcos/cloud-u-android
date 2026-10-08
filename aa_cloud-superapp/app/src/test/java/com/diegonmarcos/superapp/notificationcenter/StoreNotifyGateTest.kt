package com.diegonmarcos.superapp.notificationcenter

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.core.FleetAlerts
import com.diegonmarcos.superapp.core.StoreNotifyGate
import com.diegonmarcos.superapp.updater.BatchForeground
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowContentProvider

/**
 * #894 Store, install and update notifications are Cloud Store's alone: THIS package posts none.
 *
 * The guard that fails when any of them can be posted from the SuperApp. It runs as the SuperApp
 * (Robolectric's package is this app's applicationId) against the real libs:core FleetAlerts, the
 * real collector provider and the real libs:updater foreground holder. The static twin, which
 * also catches a NEW poster added to libs:updater / libs:appstore without the gate, is
 * ac_cloud-store/test/test-store-notify-gate.sh.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StoreNotifyGateTest {

    private lateinit var ctx: Application
    private lateinit var nm: NotificationManager

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("fleet_alerts", Context.MODE_PRIVATE).edit().clear().commit()
        nm = ctx.getSystemService(NotificationManager::class.java)
        nm.cancelAll()
    }

    private fun posted(): List<Notification> = shadowOf(nm).allNotifications

    @Test fun `this test runs as the SuperApp and the gate is closed for it, open for Cloud Store`() {
        assertEquals(StoreNotifyGate.SUPERAPP_PKG, ctx.packageName)
        assertFalse(StoreNotifyGate.mayPost(ctx))
        assertTrue(StoreNotifyGate.mayPost(StoreNotifyGate.STORE_PKG))
        assertTrue("another fleet app keeps its own notices", StoreNotifyGate.mayPost("com.diegonmarcos.cloudnav"))
    }

    @Test fun `a Store or update alert raised from the SuperApp is dropped - no collector, no local fallback`() {
        for (key in listOf("store:updates_installed", "updater:pass_summary", "updater:Install failed", "self-update")) {
            val d = FleetAlerts.raise(ctx, FleetAlerts.Alert("Updates", "3 installed", dedupeKey = key))
            assertEquals(key, FleetAlerts.Delivery.DROPPED, d)
        }
        assertTrue(posted().isEmpty())
        assertTrue(AlertStore.all(ctx).isEmpty())
    }

    @Test fun `a Store alert another app hands the collector is refused, so it stays in that app`() {
        val p = ctx.contentResolver.acquireContentProviderClient(FleetAlerts.AUTHORITY)!!.localContentProvider!!
        Shadow.extract<ShadowContentProvider>(p).setCallingPackage(StoreNotifyGate.STORE_PKG)
        for (key in listOf("store:updates_installed", "updater:pass_summary", "self-update")) {
            val r = p.call(FleetAlerts.METHOD_RAISE, null,
                FleetAlerts.Alert("Updates installed", dedupeKey = key).toBundle())
            assertFalse(key, r!!.getBoolean(FleetAlerts.KEY_OK))
        }
        assertTrue(AlertStore.all(ctx).isEmpty())
        // ... while an ordinary fleet alert is still collected.
        val ok = p.call(FleetAlerts.METHOD_RAISE, null, FleetAlerts.Alert("Backup failed", dedupeKey = "backup").toBundle())
        assertTrue(ok!!.getBoolean(FleetAlerts.KEY_OK))
        assertEquals(1, AlertStore.all(ctx).size)
    }

    @Test fun `no batch download service is held from the SuperApp`() {
        assertFalse(BatchForeground.allowed(ctx))
        BatchForeground.begin(ctx)
        assertNull("no foreground service was started", shadowOf(ctx).nextStartedService)
        BatchForeground.end(ctx)
        assertTrue(posted().isEmpty())
    }

    @Test fun `the retirement clears what an older build posted, collected and queued`() {
        // What an old SuperApp left: its Store badge, a pass summary it collected, channels of its own.
        nm.createNotificationChannel(NotificationChannel(StoreRetirement.BADGE_CHANNEL, "Store updates", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(BatchForeground.CHANNEL, "Store downloads", NotificationManager.IMPORTANCE_LOW))
        nm.notify(StoreRetirement.BADGE_NOTIFICATION_ID, NotificationCompat.Builder(ctx, StoreRetirement.BADGE_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Store").build())
        nm.notify("updater:pass_summary", 0xA1E7, NotificationCompat.Builder(ctx, "fleet_alerts")
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Updates").build())
        AlertStore.add(ctx, StoreNotifyGate.STORE_PKG, FleetAlerts.Alert("kept", dedupeKey = "backup"))
        ctx.getSharedPreferences("fleet_alerts", Context.MODE_PRIVATE).edit().putString("alerts",
            org.json.JSONArray().put(AlertStore.all(ctx).first().json())
                .put(AlertStore.Alert("old", StoreNotifyGate.STORE_PKG, "Old summary", "", FleetAlerts.INFO, "", "updater:pass_summary", 1L).json())
                .toString()).commit()
        assertEquals(2, AlertStore.all(ctx).size)

        StoreRetirement.run(ctx)

        assertTrue("no notification of the retired kinds is left", posted().none { it.channelId == StoreRetirement.BADGE_CHANNEL })
        assertNull(nm.getNotificationChannel(StoreRetirement.BADGE_CHANNEL))
        assertNull(nm.getNotificationChannel(BatchForeground.CHANNEL))
        assertEquals("only the ordinary alert stays", listOf("kept"), AlertStore.all(ctx).map { it.title })
    }
}
