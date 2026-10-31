package com.diegonmarcos.superapp.perms

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings

/**
 * #1360 first run asks for everything missing: the first time the user opens the app from the launcher, if any
 * declared grant (its own or a helper's) is missing, the app's Perms page opens over it with every missing row
 * highlighted and Grant all on top. Once per install. Off when the app asks itself (perms.first_run "own:..."), under
 * instrumentation or a test harness, and when `settings put global cloud_perms_first_run 0` (emulator walks).
 */
object PermsFirstRun {
    private const val PREFS = "perm_ask_tracker"  // the store libs:perms already declares in fleet-config (PermAskLog)
    private const val KEY = "first_run_asked_v1"
    const val GLOBAL_OFF = "cloud_perms_first_run"

    fun install(app: Application) {
        if (AppPerms.decl(app).firstRun != "libs:perms") return
        if (app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)) return
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity is PermsActivity) return
                app.unregisterActivityLifecycleCallbacks(this)
                if (!fromLauncher(activity.intent) || suppressed(activity)) return
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, true).apply()
                val own = AppPerms.missingOwn(activity).map { it.id }
                val helpers = AppPerms.missingHelpers(activity)
                if (own.isEmpty() && helpers.isEmpty()) return
                runCatching { activity.startActivity(PermsActivity.intent(activity, PermsActivity.FIRST_RUN, own + helpers.map { it.entry.id })) }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    fun fromLauncher(i: Intent?): Boolean = i != null && i.action == Intent.ACTION_MAIN && i.hasCategory(Intent.CATEGORY_LAUNCHER)

    private fun suppressed(ctx: Context): Boolean =
        ActivityManager.isRunningInTestHarness() || ActivityManager.isUserAMonkey() ||
            runCatching { Settings.Global.getInt(ctx.contentResolver, GLOBAL_OFF, 1) == 0 }.getOrDefault(false) ||
            runCatching { Class.forName("androidx.test.platform.app.InstrumentationRegistry"); true }.getOrDefault(false)
}
