package com.diegonmarcos.superapp.appstore

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.widget.Toast
import com.diegonmarcos.superapp.updater.UpdateProgress

/**
 * #859 Stop works on the host's own row too. The guard it replaces (added in
 * a0fe00263 with the row's Stop) refused with "Stopping SuperApp would close
 * this store" — a UX note, not a protection, so it is gone. The one real
 * hazard is an install in flight: then a brief notice says it resumes on the
 * next start (StoreAuto persists its chain), and Stop still goes ahead.
 *
 * Stopping self: every task of this app is finished and removed, then the
 * process is killed, so the next launch is a normal cold start.
 */
object SelfStop {

    fun isSelf(ctx: Context, pkg: String) = pkg == ctx.packageName

    /** An install, download or auto chain is running in this process. */
    fun installRunning(): Boolean {
        val st = UpdateProgress.state
        return StoreAuto.isRunning() || UpdateProgress.batchLabel != null ||
            st is UpdateProgress.State.Downloading || st is UpdateProgress.State.Installing
    }

    /** Stop this app. Never refuses; [activity] may be null. */
    fun stop(ctx: Context, activity: Activity?) {
        val app = ctx.applicationContext
        val delay = if (installRunning()) {
            Toast.makeText(app, app.getString(R.string.store_self_stop_install_notice), Toast.LENGTH_SHORT).show()
            1500L
        } else 0L
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching {
                app.getSystemService(ActivityManager::class.java)?.appTasks?.forEach { it.finishAndRemoveTask() }
            }
            runCatching { activity?.finishAndRemoveTask() }
            Process.killProcess(Process.myPid())
        }, delay)
    }
}
