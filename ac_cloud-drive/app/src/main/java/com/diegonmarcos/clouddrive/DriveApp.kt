package com.diegonmarcos.clouddrive

import android.app.Application
import com.diegonmarcos.clouddrive.startup.StartupGuard

/**
 * #1106 the Application exists for one reason: the crash handler must be installed before anything
 * else in the process runs, so a startup failure reaches the report screen instead of the
 * "keeps stopping" dialog. Nothing else initialises here — every other initialiser stays in
 * MainActivity.onCreate, each behind StartupGuard.step.
 */
class DriveApp : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching { StartupGuard.install(this) }
    }
}
