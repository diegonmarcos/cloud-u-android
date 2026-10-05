package com.diegonmarcos.cloudstore

import android.app.Application
import android.util.Log
import com.diegonmarcos.superapp.appstore.AppStoreHost
import com.diegonmarcos.superapp.appstore.ConstellationWorker
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
            // shows the error without the "open DNS settings" button.
            dnsPageExtras = emptyMap()
            runsFleetPass = { true }
        }
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
