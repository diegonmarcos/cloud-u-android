package com.diegonmarcos.superapp.notificationcenter

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.core.FleetAlerts
import com.diegonmarcos.superapp.floatingnav.FloatingNavService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowContentProvider

/**
 * #777 — the four shade groups and the fleet Alerts channel, against the REAL
 * declaration baked into this build (BuildConfig.UI_NOTIFICATION_CENTER_B64),
 * the real FleetAlerts client (libs:core) and the real collector provider.
 *
 * Cross-app: the raise goes ContentResolver → the manifest-declared
 * FleetAlertsProvider, with the calling package set to a FOREIGN app, which is
 * exactly what Android hands the provider when another fleet app calls it.
 * Robolectric instantiates that provider lazily, on the call — the in-JVM
 * analogue of Android starting the stopped SuperApp process for it; the
 * manifest test pins the attributes that make that happen on a device
 * (exported, signature-permission guarded, the authority callers name).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NotifyGroupsAlertsTest {

    private lateinit var ctx: Application
    private lateinit var nm: NotificationManager
    private var svc: ServiceController<FloatingNavService>? = null

    private val raiser = "org.example.fleet.raiser"

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        for (p in listOf("fleet_alerts", "notify_groups", "badge_customization", "floatingnav_prefs"))
            ctx.getSharedPreferences(p, Context.MODE_PRIVATE).edit().clear().commit()
        nm = ctx.getSystemService(NotificationManager::class.java)
        nm.cancelAll()
    }

    @After fun tearDown() { svc?.destroy() }

    private fun group(id: String) = NotifyGroups.declared.first { it.id == id }
    private fun posted(): List<Notification> = shadowOf(nm).allNotifications
    private fun isSummary(n: Notification) = n.flags and Notification.FLAG_GROUP_SUMMARY != 0

    /** The collector as a FOREIGN fleet app reaches it. */
    private fun collectorCalledBy(pkg: String) {
        val p = ctx.contentResolver.acquireContentProviderClient(FleetAlerts.AUTHORITY)!!.localContentProvider!!
        Shadow.extract<ShadowContentProvider>(p).setCallingPackage(pkg)
    }

    // ── the groups ──────────────────────────────────────────────────────

    @Test fun `exactly the four declared groups, in the brief's order and membership`() {
        assertEquals(listOf("live", "actions", "media", "alerts", "store"), NotifyGroups.declared.map { it.id })
        assertEquals("#812 the Store badge", listOf(StoreBadgeNotifier.BADGE_ID), group("store").members)
        assertEquals(listOf("markets_prices", "health_activity", "weather_today"), group("live").members)
        assertEquals(listOf("floating_nav_quick_actions", "kde_status"), group("actions").members)
        assertEquals(listOf("media_now_playing"), group("media").members)
        assertEquals(listOf(AlertsNotifier.BADGE_ID), group("alerts").members)
        assertEquals("only the alerts group is the alerts group", listOf("alerts"),
            NotifyGroups.declared.filter { it.alerts }.map { it.id })
        // Every member is a declared badge — a typo would be a member nothing posts.
        val badges = BadgeDeclaration.badges(BadgeServices.declared).map { it.id }.toSet()
        for (g in NotifyGroups.declared) for (m in g.members) assertTrue("$m in ${g.id}", m in badges)
    }

    @Test fun `attach gives each member its group's key and posts that group's summary first`() {
        for (g in NotifyGroups.declared.filterNot { it.alerts }) for (m in g.members) {
            val n = NotifyGroups.attach(ctx, NotificationCompat.Builder(ctx, "x").setSmallIcon(1), m).build()
            assertEquals("$m → ${g.id}", NotifyGroups.key(g), n.group)
            assertTrue("summary of ${g.id} posted",
                posted().any { isSummary(it) && it.group == NotifyGroups.key(g) })
        }
        // Control: a producer in no group is left alone.
        val loose = NotifyGroups.attach(ctx, NotificationCompat.Builder(ctx, "x").setSmallIcon(1), "nope").build()
        assertNull(loose.group)
    }

    @Test fun `a real producer posts into its group - Quick Actions lands in G2`() {
        svc = Robolectric.buildService(FloatingNavService::class.java).create().startCommand(0, 1)
        shadowOf(Looper.getMainLooper()).idle()
        val quick = shadowOf(svc!!.get()).lastForegroundNotification
        assertNotNull("FloatingNavService posted its Quick Actions badge", quick)
        assertEquals("floating_nav", quick!!.channelId)
        assertEquals(NotifyGroups.key(group("actions")), quick.group)
        assertTrue(posted().any { isSummary(it) && it.group == NotifyGroups.key(group("actions")) })
    }

    @Test fun `a group switched off switches its members off and takes its summary down`() {
        val markets = BadgeServices.declared.first { it.id == "markets_prices" }
        assertTrue("control: on by default", BadgeCustomization.isEnabled(ctx, markets))
        NotifyGroups.postSummary(ctx, group("live"))
        assertTrue(posted().any { isSummary(it) && it.group == NotifyGroups.key(group("live")) })

        NotifyGroups.setEnabled(ctx, group("live"), false)
        assertFalse(BadgeCustomization.isEnabled(ctx, markets))
        assertFalse("its service is no longer wanted",
            BadgeServices.wanted(ctx, MarketsBadgeService::class.java.name))
        assertFalse(posted().any { isSummary(it) && it.group == NotifyGroups.key(group("live")) })
        // Other groups untouched.
        val kde = BadgeServices.declared.first { it.id == "kde_status" }
        assertTrue(BadgeCustomization.isEnabled(ctx, kde))
    }

    @Test fun `order is the owner's and survives a re-read`() {
        NotifyGroups.move(ctx, group("alerts"), -3)
        assertEquals(listOf("alerts", "live", "actions", "media", "store"), NotifyGroups.ordered(ctx).map { it.id })
        NotifyGroups.move(ctx, group("live"), +1)
        assertEquals(listOf("alerts", "actions", "live", "media", "store"), NotifyGroups.ordered(ctx).map { it.id })
    }

    // ── mock data is gone ───────────────────────────────────────────────

    @Test fun `no sample alerts - an empty store posts nothing and the mock feed is not baked`() {
        assertTrue(AlertsNotifier.refresh(ctx).isEmpty())
        assertTrue(posted().none { it.group == NotifyGroups.key(group("alerts")) })
        assertFalse("UI_NOTIFICATION_INFOS_B64 (the sample feed) must not be in BuildConfig",
            BuildConfig::class.java.fields.any { it.name == "UI_NOTIFICATION_INFOS_B64" })
    }

    // ── cross-app delivery ──────────────────────────────────────────────

    @Test fun `an alert raised by another fleet app lands in the store and the shade under THAT app`() {
        collectorCalledBy(raiser)
        val d = FleetAlerts.raise(ctx, FleetAlerts.Alert(
            title = "Backup failed", text = "3 files", severity = FleetAlerts.WARN,
            deepLink = "page:config/store-cloud", dedupeKey = "backup"))
        assertEquals(FleetAlerts.Delivery.SUPERAPP, d)
        val stored = AlertStore.all(ctx).single()
        assertEquals("the caller's package, from the platform — never the payload", raiser, stored.app)
        assertEquals("Backup failed", stored.title)
        assertEquals(FleetAlerts.WARN, stored.severity)

        val key = NotifyGroups.key(group("alerts"))
        val child = posted().single { !isSummary(it) && it.group == key }
        assertEquals("Backup failed", child.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(AlertsNotifier.CHANNEL_ID, child.channelId)
        assertNotNull("a tap goes somewhere", child.contentIntent)
        assertNotNull("a swipe removes it from the store", child.deleteIntent)
        assertTrue(posted().any { isSummary(it) && it.group == key })
    }

    @Test fun `same dedupe key replaces, withdraw removes, another app's key is its own`() {
        collectorCalledBy(raiser)
        FleetAlerts.raise(ctx, FleetAlerts.Alert("1 update available", dedupeKey = "updates"))
        FleetAlerts.raise(ctx, FleetAlerts.Alert("2 updates available", dedupeKey = "updates"))
        assertEquals(listOf("2 updates available"), AlertStore.all(ctx).map { it.title })

        AlertStore.add(ctx, "org.example.other", FleetAlerts.Alert("theirs", dedupeKey = "updates"))
        assertEquals(2, AlertStore.all(ctx).size)

        FleetAlerts.withdraw(ctx, "updates")
        assertEquals("only the caller's own alert is withdrawn", listOf("theirs"), AlertStore.all(ctx).map { it.title })
    }

    @Test fun `no collector reachable - the alert falls back to the app's own notification`() {
        val d = FleetAlerts.raise(ctx, FleetAlerts.Alert("Save failed", "disk full", FleetAlerts.ERROR, dedupeKey = "save"),
            authority = "org.example.no.such.collector")
        assertEquals(FleetAlerts.Delivery.LOCAL, d)
        assertTrue("nothing reached the SuperApp store", AlertStore.all(ctx).isEmpty())
        val n = shadowOf(nm).getNotification("save", 0xA1E7)
        assertNotNull("posted locally, tagged by its dedupe key", n)
        assertEquals(FleetAlerts.FALLBACK_CHANNEL, n.channelId)
    }

    @Test fun `errors ring on the urgent channel, the rest on Alerts`() {
        collectorCalledBy(raiser)
        FleetAlerts.raise(ctx, FleetAlerts.Alert("Cannot update", severity = FleetAlerts.ERROR))
        FleetAlerts.raise(ctx, FleetAlerts.Alert("FYI", severity = FleetAlerts.INFO))
        val byTitle = posted().filterNot { isSummary(it) }.associateBy { it.extras.getString(Notification.EXTRA_TITLE) }
        assertEquals(AlertsNotifier.CHANNEL_URGENT, byTitle["Cannot update"]!!.channelId)
        assertEquals(AlertsNotifier.CHANNEL_ID, byTitle["FYI"]!!.channelId)
    }

    @Test fun `filters hide without deleting, Clear all empties store and shade`() {
        collectorCalledBy(raiser)
        FleetAlerts.raise(ctx, FleetAlerts.Alert("a", severity = FleetAlerts.INFO))
        FleetAlerts.raise(ctx, FleetAlerts.Alert("b", severity = FleetAlerts.ERROR))
        AlertStore.setSeverityMuted(ctx, FleetAlerts.INFO, true)
        assertEquals(listOf("b"), AlertsNotifier.refresh(ctx).map { it.title })
        assertEquals("muted is still stored", 2, AlertStore.all(ctx).size)

        AlertStore.setAppMuted(ctx, raiser, true)
        assertTrue(AlertsNotifier.refresh(ctx).isEmpty())
        assertTrue(posted().none { it.group == NotifyGroups.key(group("alerts")) })

        assertEquals(2, AlertStore.clear(ctx))
        AlertsNotifier.refresh(ctx)
        assertTrue(AlertStore.all(ctx).isEmpty())
    }

    @Test fun `the alerts group switched off keeps alerts out of the shade`() {
        collectorCalledBy(raiser)
        FleetAlerts.raise(ctx, FleetAlerts.Alert("x"))
        assertTrue(posted().any { it.group == NotifyGroups.key(group("alerts")) })
        NotifyGroups.setEnabled(ctx, group("alerts"), false)
        assertTrue(posted().none { it.group == NotifyGroups.key(group("alerts")) })
        assertEquals("still stored", 1, AlertStore.all(ctx).size)
    }

    @Test fun `the Alerts badge reads IDLE with nothing to show and LIVE once an alert is in the shade`() {
        val b = BadgeServices.declared.first { it.id == AlertsNotifier.BADGE_ID }
        assertEquals(BadgeServices.State.IDLE, BadgeServices.status(ctx, b).state)
        collectorCalledBy(raiser)
        FleetAlerts.raise(ctx, FleetAlerts.Alert("x"))
        assertEquals(BadgeServices.State.LIVE, BadgeServices.status(ctx, b).state)
    }

    // ── the contract other apps depend on ───────────────────────────────

    @Test fun `the collector is exported, signature-guarded, and at the authority FleetAlerts names`() {
        val info = ctx.packageManager.resolveContentProvider(FleetAlerts.AUTHORITY, 0)
        assertNotNull("no provider at ${FleetAlerts.AUTHORITY}", info)
        assertEquals(FleetAlertsProvider::class.java.name, info!!.name)
        assertTrue(info.exported)
        val perm = "com.diegonmarcos.cloud.permission.CONSTELLATION_DATA"
        assertEquals(perm, info.readPermission)
        assertEquals(perm, info.writePermission)
        assertEquals(FleetAlerts.FALLBACK_CHANNEL, AlertsNotifier.CHANNEL_ID)
    }

    @Test fun `a provider call with no calling package is refused`() {
        val p = FleetAlertsProvider()
        // Not attached: no context, no caller → nothing stored, no crash.
        assertNull(p.call(FleetAlerts.METHOD_RAISE, null, FleetAlerts.Alert("x").toBundle()))
        assertTrue(AlertStore.all(ctx).isEmpty())
    }

    // ── #812 the Store badge ──────────────────────────────────────────────

    private fun storeBadge() = posted().firstOrNull { it.channelId == StoreBadgeNotifier.CHANNEL_ID }

    @Test fun `812 the Store badge appears with the pending count and clears - never ongoing`() {
        val decl = BadgeServices.declared.first { it.id == StoreBadgeNotifier.BADGE_ID }
        assertFalse("declared non-persistent", decl.persistent)
        assertTrue("no service keeps it alive", decl.service.isBlank())
        assertTrue(StoreBadgeNotifier.update(ctx, 7))
        val n = storeBadge()
        assertNotNull("posted while updates wait", n)
        assertEquals(7, n!!.number)
        assertTrue(n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty().contains("7 Store updates"))
        assertEquals("never sticky", 0, n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR))
        assertEquals(NotifyGroups.key(group("store")), n.group)
        // The updates are gone (or the Store was opened): it clears.
        assertFalse(StoreBadgeNotifier.update(ctx, 0))
        assertNull(storeBadge())
        // Control: switched off on the Notify page, it is never posted.
        BadgeCustomization.set(ctx, decl, BadgeCustomization.KEY_ENABLED, false)
        assertFalse(StoreBadgeNotifier.update(ctx, 3))
        assertNull(storeBadge())
    }
}
