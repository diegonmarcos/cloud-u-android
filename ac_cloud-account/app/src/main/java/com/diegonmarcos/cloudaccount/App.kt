package com.diegonmarcos.cloudaccount

import android.app.Activity
import android.app.Application
import android.content.Intent
import com.diegonmarcos.superapp.appstore.StoreImport
import android.util.Log
import com.diegonmarcos.superapp.profile.AccountDebugApi
import com.diegonmarcos.superapp.profile.AccountHost

/**
 * #867 Cloud Account: hosts libs:account as its own app.
 *
 * The Account code lives once, in ab_cloud-libs-shared/libs/account, and cloud-superapp hosts the
 * same code. This class supplies only what a standalone host must: where the page's links go.
 * There is no launcher here, so no launcher palette, tab chrome, icon table or haptics (the
 * library's defaults stand) and no mesh tunnel (the Fleet tab reports it as not available).
 * This app OWNS the data, so the read-through to another host stays off.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AccountHost.apply {
            route = { activity, route -> open(activity, route) }
            readThrough = false
        }
        // /api/account/... on the fleet debug server, as in SuperApp.
        runCatching { AccountDebugApi.register(this) }
            .onFailure { Log.w(TAG, "account debug API not registered", it) }
    }

    companion object {
        private const val TAG = "CloudAccount"
        private const val SUPERAPP = "com.diegonmarcos.superapp"
        private const val STORE = "com.diegonmarcos.cloudstore"
        /** SuperApp's Store page (a launcher route, not one of this app's ui.sections). */
        private const val STORE_PAGE = "config/store"

        /**
         * Account links to launcher routes (`page:…`, `section:…`, `extapp:…`). With no launcher
         * here: the Store routes go to Cloud Store when it is installed; any other route opens
         * SuperApp, which owns the pages. False when neither is installed, and Account then says so.
         */
        fun open(activity: Activity, route: String): Boolean = runCatching {
            val pm = activity.packageManager
            val store = route.startsWith("extapp:cloud-store") || route.removePrefix("page:").startsWith(STORE_PAGE)
            val intent = if (store && pm.getLaunchIntentForPackage(STORE) != null)
                Intent("$STORE.OPEN").setPackage(STORE).putExtra("tab", if (route.endsWith("store-phone")) "phone" else "cloud")
                    // #570 Fleet ▸ Apps ▸ Apply list to Store: the declared inventory crosses to Cloud Store's Phone page.
                    .putExtra(StoreImport.EXTRA_IMPORT, StoreImport.takePending())
            else pm.getLaunchIntentForPackage(SUPERAPP)
            if (intent == null) false else { activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }
        }.getOrDefault(false)
    }
}
