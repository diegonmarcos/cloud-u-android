package com.diegonmarcos.cloudaccount

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.util.Log
import com.diegonmarcos.superapp.adbdebug.HostShell
import com.diegonmarcos.superapp.appstore.StoreImport
import com.diegonmarcos.superapp.profile.AccountDebugApi
import com.diegonmarcos.superapp.profile.AccountHost
import com.diegonmarcos.superapp.profile.AccountMigrate
import com.diegonmarcos.superapp.settings.AccountVault

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
            // Redesign task 3: the debug API's `tabs` op answers this app's five islands and their pages.
            nav = { navJson() }
            autoBackupChanged = { AutoBackupWorker.schedule(it) }
        }
        // #874 the vault's Secrets section is enforced by this app's setup provider (`<package>.fleetsetup`) and by
        // AccountData.Provider: install the grant-aware authorizer and seed the defaults the fleet relied on, once.
        runCatching { AccountVault.install(this) }.onFailure { Log.w(TAG, "vault grants not installed: ${it.javaClass.simpleName}") }
        // #874 take in what SuperApp kept (Dagu bearer, DNS preset, WireGuard tunnel, mail login), only where the vault is empty.
        kotlin.concurrent.thread(name = "account-migrate") {
            runCatching { AccountMigrate.run(this) }.onFailure { Log.w(TAG, "migration from SuperApp failed: ${it.javaClass.simpleName}") }
        }
        // A legacy device id renamed by a declared alias (galaxy -> galaxy-s21) is migrated now, before any page draws it.
        runCatching { com.diegonmarcos.superapp.profile.AccountDevice.migrateAtStart(this) }
            .onFailure { Log.w(TAG, "device alias migration failed: ${it.javaClass.simpleName}") }
        // Account's OWN uid-2000 channel (spec 2.4): embedded adb pairing, Shizuku as the fallback.
        // Re-armed after every boot; the runbook's shell and store steps run over it.
        // HostShellService keeps the debug server up only while Settings ▸ Debug API allows it.
        HostShell.debugServerAllowed = { com.diegonmarcos.superapp.profile.DebugApiSwitch.enabled(it) }
        runCatching { HostShell.install(this) }.onFailure { Log.w(TAG, "shell channel not armed", it) }
        // /api/account/... on the fleet debug server, as in SuperApp.
        runCatching { AccountDebugApi.register(this) }
            .onFailure { Log.w(TAG, "account debug API not registered", it) }
        // Settings ▸ Debug API off keeps the loopback server stopped (it is started by DebugInitProvider).
        runCatching { com.diegonmarcos.superapp.profile.DebugApiSwitch.apply(this) }
        // Settings ▸ Auto-backup: re-arm (or cancel) the daily job.
        runCatching { AutoBackupWorker.schedule(this) }.onFailure { Log.w(TAG, "auto-backup not scheduled", it) }
    }

    companion object {
        private const val TAG = "CloudAccount"

        /** build.json::ui.bottom_nav + ui.sections as baked: [{id, label, pages:[{id, label}]}]. */
        fun navJson(): org.json.JSONArray = org.json.JSONArray().also { a ->
            MainActivity.NAV.bottomSections().forEach { s ->
                a.put(org.json.JSONObject().put("id", s.id).put("label", s.label)
                    .put("pages", org.json.JSONArray(s.pages.map { p -> org.json.JSONObject().put("id", p.id).put("label", p.label) })))
            }
        }
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
