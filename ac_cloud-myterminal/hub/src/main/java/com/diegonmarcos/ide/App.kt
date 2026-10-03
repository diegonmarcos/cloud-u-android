package com.diegonmarcos.ide

import android.app.Application
import com.diegonmarcos.ide.update.Updater
import com.diegonmarcos.superapp.devtools.AppDebugServer

/** Hub application object. Kept minimal — the broker surfaces (provider +
 *  service) are component-scoped, not tied to a long-lived Application. Starts
 *  the periodic in-app updater (idempotent; data-driven from
 *  build.json::release.auto_update). */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppProcessUptime.initOnce()
        Updater.start(this)
        // #787 the shell wake lock's state for the architect: GET /api/terminal[/wakelock].
        CloudWakeLock.STATE.update(IdePrefs.wakeLockWanted(this), 0, 0)
        AppDebugServer.route("terminal", listOf(
            AppDebugServer.Op("wakelock", "", "#787 the open-shell wake lock (also at /api/terminal): "
                + "held, since (epoch ms or null), sessions, wanted (Configs toggle), wifi_lock"),
        )) { op, _ -> if (op == "" || op == "wakelock") CloudWakeLock.STATE.json(false) else null }
    }
}
