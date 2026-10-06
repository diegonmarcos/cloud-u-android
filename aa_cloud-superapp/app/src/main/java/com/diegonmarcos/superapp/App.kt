package com.diegonmarcos.superapp
import com.diegonmarcos.superapp.system.Trace
import com.diegonmarcos.superapp.system.CrashLogger
import com.diegonmarcos.superapp.system.AppProcessUptime
import com.diegonmarcos.superapp.battery.PowerStateReceiver
import com.diegonmarcos.superapp.battery.BatterySessionWorker
import com.diegonmarcos.superapp.battery.BatterySessionStats

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.work.Configuration as WorkManagerConfiguration
import com.diegonmarcos.superapp.devcontrol.DevControlServer
import com.diegonmarcos.superapp.notificationcenter.BadgeServices
import com.google.android.material.color.DynamicColors

/**
 * Application entry point — runs BEFORE any Activity. Wires:
 *  • Trace + CrashLogger (capture inflation/onCreate failures)
 *  • Material 3 DynamicColors — on Android 12+ the theme adapts to the
 *    system wallpaper palette. Older devices fall back to the brand
 *    palette declared in colors.xml + themes.xml.
 *  • Force night-mode — the app's whole visual identity is dark
 *    purple→black gradient, and the system long-press tooltip pill
 *    picks `tooltip_frame_dark` (black bg, white text) instead of
 *    `tooltip_frame_light` (white bg) when night mode is on. Same
 *    knob fixes the pill colour without us re-implementing it.
 */
class App : Application(), WorkManagerConfiguration.Provider {
    /**
     * On-demand WorkManager initialization. The vendored HeliBoard
     * (libs:keyboard) merge drops androidx.startup's auto-init
     * `WorkManagerInitializer`, so the default provider never initializes
     * WorkManager — every `WorkManager.getInstance(...)` then throws
     * "WorkManager is not initialized properly". That crashed the launcher at
     * MainActivity → Updater.start, and silently broke
     * BatterySessionWorker.schedule (swallowed by its runCatching).
     *
     * Implementing Configuration.Provider + removing the default initializer in
     * the manifest is the canonical on-demand setup: the FIRST getInstance()
     * call lazily initializes WorkManager with this config. Repairs both the
     * updater and the battery worker, deterministically, regardless of the
     * manifest-merge outcome.
     */
    override val workManagerConfiguration: WorkManagerConfiguration
        get() = WorkManagerConfiguration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        // Force night mode BEFORE super so AppCompatDelegate picks it up
        // on the very first inflation.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate()
        // The island's haptics are the lib's; "Vibration on tap" (Configs -> Launcher -> Others) still gates them.
        com.diegonmarcos.superapp.bottomnav.FleetHaptics.enabled = { ctx ->
            runCatching { com.diegonmarcos.superapp.settings.LauncherSettingsPrefs(ctx).toggle("haptics") }.getOrDefault(true)
        }
        // #841 the Store's Commits / CI-CD feeds read the fleet git-proxy with the
        // SAME Authelia bearer every other fleet call here sends (libs:ops
        // DaguPrefs, as OpsClient does). Read lazily per request; never logged.
        com.diegonmarcos.superapp.appstore.FeedViewer.fleetBearer = {
            runCatching { com.diegonmarcos.superapp.ops.dagu.DaguPrefs(this).bearerToken }.getOrDefault("")
        }
        // Privileged plane re-arm on every launch (unique, KEEP): BOOT_COMPLETED is
        // delayed or dropped on some OEMs, and the first successful connect right
        // after the one-time pairing must not wait for a reboot. Cheap when
        // already connected (autoConnect short-circuits).
        runCatching {
            androidx.work.WorkManager.getInstance(this).enqueueUniqueWork(
                "privileged-plane", androidx.work.ExistingWorkPolicy.KEEP,
                androidx.work.OneTimeWorkRequestBuilder<com.diegonmarcos.superapp.system.PrivilegedPlaneWorker>().build())
            // #290: the platform clears Wireless Debugging mid-session; the keeper
            // re-arms it and reconnects the channel while the owner's keep-alive
            // switch is on, and stands down entirely when it is off.
            com.diegonmarcos.superapp.system.WirelessDebugKeeper.sync(
                this, com.diegonmarcos.superapp.system.WirelessDebugKeepAlive.Trigger.PERIODIC)
        }

        // Retry any profile edit that never reached the server. The profile is
        // the out-of-band contact channel for exactly the situations where the
        // update chain is broken, so "it failed once and was never sent again"
        // is the one outcome it must not have. No-op when nothing is queued.
        runCatching { com.diegonmarcos.superapp.profile.ProfileSync.flush(this) }

        // #751 The DNS choice holds without the mesh: the WireGuard engine carries
        // it in the VPN slot, but nothing starts the engine after a reboot or a
        // killed process, so the launcher's own start hands it back. Only for an
        // explicit choice, and never over a running firewall (it keeps the slot).
        // #794 ...and then CHECKS it took: a choice Android does not resolve with
        // is never silent. Missing VPN consent (never given, or dropped by an
        // engine reinstall) raises the alert that opens the DNS page's one-tap
        // consent; an engine update or reinstall re-runs the same check, since
        // it kills the process that held the DNS-only tunnel.
        val dnsCheck = Runnable {
            runCatching {
                com.diegonmarcos.superapp.network.FleetDns.syncAndCheck(this,
                    raiseNow = !com.diegonmarcos.superapp.firewall.FirewallController.isEnabled(this))
            }.onFailure { android.util.Log.w("App", "fleet DNS not handed to the engine or not checked", it) }
        }
        if (com.diegonmarcos.superapp.network.FleetDns.Prefs(this).chosen) Thread(dnsCheck).start()
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(this, object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context, i: android.content.Intent) {
                    if (i.data?.schemeSpecificPart == com.diegonmarcos.superapp.net.AidlBackend.ENGINE_PKG &&
                        com.diegonmarcos.superapp.network.FleetDns.Prefs(c).chosen) Thread(dnsCheck).start()
                }
            }, android.content.IntentFilter().apply {
                addAction(android.content.Intent.ACTION_PACKAGE_REPLACED)
                addAction(android.content.Intent.ACTION_PACKAGE_ADDED)
                addDataScheme("package")
            }, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        }

        // Tell libs:appstore what it cannot know: this app's entry Activity,
        // its notification icon, how this launcher routes a tap, and the
        // host-side toggle that gates the periodic check. The store moved out
        // of the app, so these are the host's to supply.
        com.diegonmarcos.superapp.appstore.AppStoreHost.apply {
            launchActivity = MainActivity::class.java
            notificationIcon = R.drawable.ic_stat_notify
            launchExtras = mapOf("shortcut_action" to "page:config/store-cloud")
            // #831 a DNS failure on a Store row opens the same page the DNS alert does.
            dnsPageExtras = mapOf("shortcut_action" to "page:config/dns")
            // #563: both Store pages group by the launcher's own taxonomy.
            classify = com.diegonmarcos.superapp.apps.StoreShelves::of
            periodicCheckAllowed = { ctx ->
                com.diegonmarcos.superapp.settings.LauncherSettingsPrefs(ctx).toggle("fleet_check")
            }
            // #865 Cloud Store runs the unattended fleet pass once it is
            // installed, and updates SuperApp with the rest of the fleet. Read
            // on every check, so installing or removing Cloud Store moves the
            // pass without a restart (the worker stands down in doWork).
            runsFleetPass = { ctx -> !com.diegonmarcos.superapp.apps.CloudStoreHandoff.installed(ctx) }
        }
        // #867 Account is a library now (libs:account); what it cannot reach in the launcher is supplied here.
        com.diegonmarcos.superapp.profile.AccountHost.apply {
            palette = { ctx -> com.diegonmarcos.superapp.ui.LauncherPalette.kit(ctx) }
            iconFor = { ctx, name -> com.diegonmarcos.superapp.launcher.Sections.iconResFor(ctx, name) }
            route = { activity, route ->
                (activity as? com.diegonmarcos.superapp.launcher.TileGridFragment.TileClickListener)
                    ?.also { it.onTileClicked(route) } != null
            }
            tap = { view -> com.diegonmarcos.superapp.ui.Haptics.tap(view) }
            mesh = com.diegonmarcos.superapp.network.AccountMesh
            classify = com.diegonmarcos.superapp.settings.ImportConfigsFragment::classify
            refusal = com.diegonmarcos.superapp.settings.ImportConfigsFragment::refusal
            // #867 Cloud Account owns the imported configs once installed; an empty blob here reads through to it.
            readThrough = true
        }
        // #867 first run with Cloud Account installed: copy its configs, profile and S/R/L files over once
        // (never over anything this app already holds). Off the main thread: it is a provider call.
        Thread({
            runCatching { com.diegonmarcos.superapp.profile.AccountData.migrate(applicationContext) }
                .onFailure { android.util.Log.w("App", "account copy failed", it) }
        }, "account-migrate").start()
        // #831/#860/#866 a Store download host resolves the way the active preset
        // says, and a failure names the resolvers tried (libs:appstore StoreDns,
        // shared with Cloud Store); the preset and the lookup come from FleetDns.
        val dnsCtx = applicationContext
        com.diegonmarcos.superapp.appstore.StoreDns.apply {
            presetOf = { c ->
                val p = com.diegonmarcos.superapp.network.FleetDns.effective(
                    com.diegonmarcos.superapp.network.FleetDns.decl,
                    com.diegonmarcos.superapp.network.FleetDns.Prefs(c).preset)
                com.diegonmarcos.superapp.appstore.StoreDns.Preset(p.label, p.kind == com.diegonmarcos.superapp.network.FleetDns.KIND_MIRROR, p.servers, p.fallback)
            }
            query = com.diegonmarcos.superapp.network.FleetDns::query
            timeoutMs = com.diegonmarcos.superapp.network.FleetDns.decl.timeoutMs
            networkSummary = com.diegonmarcos.superapp.network.FleetDns::resolverSummary
        }
        com.diegonmarcos.superapp.appstore.StoreDns.start(dnsCtx)
        // Capture process-start time before anything else so About →
        // Battery & Usage can report the real uptime.
        AppProcessUptime.initOnce()
        // Fleet token is DECLARATIVE: BuildConfig.FLEET_TOKEN is baked from the
        // sops secret (build.sh exports SUPERAPP_FLEET_TOKEN from the vault, the
        // same value the cloud-superapp-mcp env is rendered with). Seed it into
        // the slot DevControlPrefs/FleetToken read, BEFORE DevControlServer.start
        // captures the token, so there is no random per-install token and no
        // "regenerate". Empty (unsigned dev build) falls back to the adopt/mint
        // path unchanged.
        runCatching {
            val declared = BuildConfig.FLEET_TOKEN
            if (declared.isNotBlank())
                com.diegonmarcos.superapp.devtools.DevControlPrefs(this).adopt(declared)
        }
        // DevControlServer FIRST so even if anything downstream
        // crashes I can still curl /logcat / /trace / /crashes from
        // this device's shell to debug.
        runCatching { DevControlServer.start(this) }
        runCatching { Trace.install(this) }
        runCatching { CrashLogger.install(this) }
        runCatching { DynamicColors.applyToActivitiesIfAvailable(this) }
        // #515: `detectVersionBump()` used to run here and push an
        // "Updated to vc:N" line into core.NotificationStore. It was the
        // SECOND entry for an event that already had a producer —
        // PackageInstallerReceiver.surface() records every install result
        // into the same store, under the same source "Updater", from the
        // install callback that actually knows the outcome. See the
        // deleted function's rationale in
        // a0_docs/eng-specs/superapp-notification-centre-duplicate.md.
        // #515: this used to be `KdeStatusService.start(this)` — ONE service,
        // named by hand. That hand-written line is why the KDE badge was the
        // only one of Diego's three still standing after an update: it was the
        // only badge with any restart path at all. Quickmarks, Media and Alerts
        // are all owned by FloatingNavService, which nothing here started.
        //
        // Now the declaration decides. ensureAll re-ensures every producer
        // build.json::ui.notification_center marks persistent, which is the
        // same set BadgeRestartReceiver ensures on MY_PACKAGE_REPLACED /
        // BOOT_COMPLETED — one list, one mechanism, cold start and update
        // converging on the same services running.
        runCatching { BadgeServices.ensureAll(this) }
        // #775: /api/overlays — what is drawn vs. what each switch says.
        runCatching { com.diegonmarcos.superapp.floatingnav.OverlaysDebugApi.register(this) }
        // #777: /api/notify/{groups,alerts} — the four shade groups and the fleet alerts.
        runCatching { com.diegonmarcos.superapp.notificationcenter.NotifyDebugApi.register(this) }
        // #777: the Alerts group is drawn from the store, so it comes back
        // (silently) after a reboot or an update cleared the shade.
        runCatching { com.diegonmarcos.superapp.notificationcenter.AlertsNotifier.refresh(this) }
        // #812 the Store badge (Notify ▸ Store): the auto chain reports how many
        // updates still wait; 0, or opening the Store, clears it. Never ongoing.
        com.diegonmarcos.superapp.appstore.StoreAuto.onPending = { c, n ->
            com.diegonmarcos.superapp.notificationcenter.StoreBadgeNotifier.update(c, n)
        }
        // #778: /api/account/* — Account's tabs, profiles, runtime, drift and their actions.
        runCatching { com.diegonmarcos.superapp.profile.AccountDebugApi.register(this) }
        // #794: /api/net/dns/overview — the DNS page as JSON: preset in effect or not, every app's path, every server.
        runCatching { com.diegonmarcos.superapp.network.DnsOverview.register(this) }
        // Schedule the periodic battery-session tick (15 min cadence).
        // Idempotent — KEEP policy ensures re-scheduling on every cold
        // start is a no-op. Without this the discharge anchor only
        // updates when the user OPENS a battery surface, so the rate
        // appears to "start computing just now" hours after an actual
        // unplug. With it, the worker runs even when the SuperApp is
        // backgrounded / process-killed and the transition-detection
        // path in BatterySessionStats.read catches plug/unplug events
        // at ≤15 min granularity even when PowerStateReceiver is
        // suppressed by Samsung Sleeping Apps.
        runCatching { BatterySessionWorker.schedule(this) }
        // Constellation AppStore — periodic fleet check across every
        // constellation APK (Configs → Constellation). Notifies when siblings
        // have GHCR updates; install stays user-initiated. Idempotent (KEEP).
        runCatching { com.diegonmarcos.superapp.appstore.ConstellationWorker.start(this) }
        // #774 /api/store/{cache,stage,download,install,clear,auto} on the fleet
        // debug server — the Store's stages, verifiable with the screen locked.
        runCatching { com.diegonmarcos.superapp.appstore.StoreDebugApi.register(this) }
        // HeliBoard (libs:keyboard) is vendored WITHOUT its own Application —
        // our .App wins the manifest merge (tools:replace android:name), so the
        // keyboard's app-level init never ran. That left Settings /
        // SubtypeSettings.prefs null → Configs→Keyboard (SettingsActivity) AND
        // LatinIME crashed with "parameter prefs is null". Replicate HeliBoard
        // App.onCreate's synchronous init here so both work.
        Trace.i("App", "Application.onCreate done — pid=${android.os.Process.myPid()}")
    }


}
