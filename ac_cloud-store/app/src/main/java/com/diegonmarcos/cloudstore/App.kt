package com.diegonmarcos.cloudstore

import android.app.Application
import android.util.Log
import com.diegonmarcos.superapp.appstore.AppStoreHost
import com.diegonmarcos.superapp.appstore.ConstellationWorker
import com.diegonmarcos.superapp.appstore.FeedViewer
import com.diegonmarcos.superapp.appstore.FleetBearer
import com.diegonmarcos.cloudlib.sysdns.FleetDnsBridge
import com.diegonmarcos.superapp.appstore.StoreDns
import com.diegonmarcos.superapp.apps.StoreShelves
import com.diegonmarcos.superapp.appstore.StoreDebugApi

/**
 * #865 Cloud Store: hosts libs:appstore as its own app.
 *
 * The store code lives once, in ab_cloud-libs-shared/libs/appstore, and
 * cloud-superapp hosts the same code. This class supplies only what a host
 * must: where a notification tap lands, which icon it uses, and the claim on
 * the unattended fleet pass. Cloud Store always runs that pass; SuperApp
 * stands down once this app is installed (see [AppStoreHost.runsFleetPass]).
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppStoreHost.apply {
            launchActivity = MainActivity::class.java
            notificationIcon = R.drawable.ic_stat_notify
            launchExtras = mapOf(MainActivity.EXTRA_TAB to MainActivity.TAB_CLOUD)
            // No DNS page of its own: a row that fails on name resolution
            // offers SuperApp's DNS page (the same extras SuperApp's App.kt
            // puts on its launcher), when SuperApp is installed.
            dnsPagePackage = "com.diegonmarcos.superapp"
            dnsPageExtras = mapOf("shortcut_action" to BuildConfig.DNS_HANDOFF_ACTION)
            runsFleetPass = { true }
            com.diegonmarcos.superapp.updater.UpdaterHost.apply {
                // #894 the pass summary and result alerts open Cloud Store itself, on the fleet tab.
                alertLink = "intent:#Intent;action=com.diegonmarcos.cloudstore.OPEN;package=com.diegonmarcos.cloudstore;S.tab=cloud;end"
                storeName = "Cloud Store"
            }
            // #866 the same shelves as SuperApp's Store pages (libs:appstore StoreShelves,
            // over the one taxonomy SuperApp's build.json declares).
            classify = StoreShelves::of
        }
        // #860/#866/#874 downloads resolve through the same StoreDns path as SuperApp:
        // this process's fleet DNS bridge. Cloud Store has no DNS page, so the bridge's
        // default plan applies: Mirror — Android's resolver on each underlying network,
        // then the uid's default.
        FleetDnsBridge.start(this, com.diegonmarcos.cloudlib.sysdns.BuildConfig.BRIDGE_PORT)
        StoreDns.apply {
            resolve = FleetDnsBridge::resolve
            resolverLabel = { FleetDnsBridge.label }
            tried = { FleetDnsBridge.lastFailure }
            forget = FleetDnsBridge::forget
        }
        StoreDns.start(this)
        com.diegonmarcos.superapp.devtools.AppDebugServer.dnsVia = { FleetDnsBridge.label }
        // #866 the fleet bearer for the Commits / CI-CD feeds: SuperApp's token over its
        // CONSTELLATION_DATA provider when SuperApp is installed, else the token typed
        // into this app's Settings tab. Read per request; never logged.
        FeedViewer.fleetBearer = { runCatching { FleetBearer.resolve(this) }.getOrDefault("") }
        // #894 Cloud Store's OWN uid-2000 channel (embedded adb pairing, Shizuku as the fallback):
        // Fleet.commit and the self-update install through it first, no prompt.
        runCatching { com.diegonmarcos.cloudstore.shell.CloudStoreShell.install(this) }
            .onFailure { Log.w(TAG, "shell channel not armed", it) }
        // Periodic fleet check + the Wi-Fi trigger + the auto chain, which
        // updates every constellation app (SuperApp included) and this one last.
        runCatching { ConstellationWorker.start(this) }
            .onFailure { Log.w(TAG, "fleet pass not scheduled", it) }
        // /api/store/... on the fleet debug server, as in SuperApp (#774).
        runCatching { StoreDebugApi.register(this) }
            .onFailure { Log.w(TAG, "store debug API not registered", it) }
    }

    private companion object { const val TAG = "CloudStore" }
}
