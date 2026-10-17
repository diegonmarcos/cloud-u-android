package com.diegonmarcos.superapp.launcher

import android.content.Context
import android.content.Intent

/** Opening a companion app that is already installed: the part of ShellActivity.launchExternalApp that does not ask the user anything. */
object ExternalAppLaunch {

    /** Candidate packages, most-specific first: the fork, the hub, then the stock-resigned alt id (ExternalApp.altPackage). */
    fun candidates(app: Sections.ExternalApp, forkKey: String?): List<String> =
        listOfNotNull(forkKey?.let { app.forks[it] }, app.hubPackage, app.altPackage).filter { it.isNotBlank() }

    /** Start the first candidate that has a launch intent. [deepTarget] rides as `shortcut_action` and makes the start single-top. True = one started. */
    fun launchInstalled(ctx: Context, app: Sections.ExternalApp, forkKey: String?, deepTarget: String?): Boolean {
        for (pkg in candidates(app, forkKey)) {
            val launch = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: continue
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (deepTarget != null) {
                launch.putExtra("shortcut_action", deepTarget)
                launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val ok = runCatching {
                val lbl = runCatching {
                    ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
                }.getOrDefault(app.label)
                com.diegonmarcos.superapp.apptabs.AppTabPrefs(ctx).recordExternalApp(pkg, lbl)
                ctx.startActivity(launch); true
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }
}

/** What the second toolbar pill (the Dynamic Island) does, from ui.dynamic_island_action (BuildConfig): `app:<pkg>`, `extapp:<id>[/fork]`, `shortcut:<id>`. */
object DynamicIslandAction {

    fun dispatch(
        ctx: Context, launchExternalApp: (String) -> Unit, shortcut: (String) -> Unit,
        action: String = com.diegonmarcos.superapp.BuildConfig.UI_DYNAMIC_ISLAND_ACTION,
    ) {
        val parts = action.split(":", limit = 2)
        when (parts.getOrNull(0)) {
            "app" -> {
                val pkg = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return
                val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
                if (intent != null) ctx.startActivity(intent)
                else android.widget.Toast.makeText(ctx, "$pkg not installed", android.widget.Toast.LENGTH_SHORT).show()
            }
            "extapp" -> parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { launchExternalApp(it) }
            "shortcut" -> parts.getOrNull(1)?.let { shortcut(it) }
            else -> {}
        }
    }
}
