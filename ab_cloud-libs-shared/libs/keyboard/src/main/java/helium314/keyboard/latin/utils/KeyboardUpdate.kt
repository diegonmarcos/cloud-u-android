// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.util.Base64
import helium314.keyboard.latin.BuildConfig
import org.json.JSONObject

/**
 * Config ▸ Update (#776): the fleet's update management for this app, and Restart.
 *
 * This app compiles no libs:updater - #763's lib-classes ratchet forbids a new app edge to
 * a `logic` lib - so update management is the fleet Store's row for this package, which
 * already does version check, cached download, install and auto-update. Where that row
 * lives is build.json::update (BuildConfig.UPDATE_B64), never a literal here.
 */
object KeyboardUpdate {
    private const val TAG = "KeyboardUpdate"

    private val config: JSONObject by lazy {
        runCatching { JSONObject(String(Base64.decode(BuildConfig.UPDATE_B64, Base64.DEFAULT), Charsets.UTF_8)) }
            .getOrDefault(JSONObject())
    }

    /** False in an app that declares no update block: the page is not shown at all. */
    val enabled get() = config.optString("store_package").isNotEmpty()

    /** Opens the Store on Cloud Constellation; the release asset when the Store is not installed. */
    fun openStore(context: Context) {
        val intent = Intent()
            .setClassName(config.optString("store_package"), config.optString("store_activity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        config.optJSONObject("store_extras")?.let { e -> e.keys().forEach { intent.putExtra(it, e.getString(it)) } }
        try {
            context.startActivity(intent)
            Log.i(TAG, "opened the Store at ${intent.extras?.keySet()?.associateWith { intent.extras?.getString(it) }}")
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "the Store (${intent.component}) is not installed, opening the release asset instead")
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(config.optString("fallback_url")))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { Log.e(TAG, "no app can open ${config.optString("fallback_url")}", it) }
        }
    }

    /**
     * Restarts the keyboard process. The IME is bound by the system, which restarts and
     * re-binds a bound service whose process died - the same mechanism LatinIME's
     * RestartAfterDeviceUnlockReceiver relies on when it kills its own process after the
     * first unlock - so this works while it is the active keyboard. Settings is reopened
     * so the tap does not just make the screen vanish; the log is dumped first because the
     * in-memory buffer dies with the process.
     */
    fun restart(activity: Activity) {
        Log.w(TAG, "restart requested from Config ▸ Update, killing pid ${Process.myPid()}")
        runCatching { LogTakeout.dump(activity) }
        activity.packageManager.getLaunchIntentForPackage(activity.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ?.let { runCatching { activity.startActivity(it) } }
        activity.finishAffinity()
        Process.killProcess(Process.myPid())
    }
}
