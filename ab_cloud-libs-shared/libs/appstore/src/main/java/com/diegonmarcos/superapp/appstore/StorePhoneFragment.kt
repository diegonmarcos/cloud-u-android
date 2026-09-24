package com.diegonmarcos.superapp.appstore

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.updater.Fleet
import kotlin.concurrent.thread

/**
 * Store ▸ Phone Apps (#563) — every launchable APK on the device that is NOT a
 * fleet member, grouped by the host's central classification exactly as
 * [StoreCloudFragment] groups the fleet.
 *
 * WHICH VERBS ARE REAL. No privileged channel is paired on the device
 * (#225/#280/#281): without adb or Shizuku this app can list, launch and open
 * a package's system settings screen, and it CANNOT install or uninstall a
 * package it does not own. So each row offers exactly Open and App info.
 * Uninstall, force-stop and permissions live on the system App info screen
 * and stay the user's own action there; installing and updating a third-party
 * app is its own store's job. A button here for any of those would be a
 * button that fails.
 */
class StorePhoneFragment : Fragment() {

    private class Row(val pkg: String, val label: String, val shelf: AppStoreHost.Shelf?)

    private val cDim = 0x99FFFFFF.toInt()
    private val cHead = 0xFFED8936.toInt()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext()
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(ctx, 14); setPadding(p, p, p, p)
        }
        col.addView(caption(ctx,
            "Every app on this phone that is not in the Cloud fleet. Open and App info " +
            "work on all of them; uninstalling is Android's own App info screen, and " +
            "installing is the app's own store - there is no privileged channel here to do either."))
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        list.addView(caption(ctx, "Loading…"))
        col.addView(list)
        // Enumerating and classifying a few hundred packages is PackageManager
        // IPC - off the main thread, the #261 lesson.
        val app = ctx.applicationContext
        thread(name = "store-phone-apps") {
            val rows = runCatching { rows(app) }
            list.post {
                if (!isAdded) return@post
                list.removeAllViews()
                rows.onSuccess { render(ctx, list, it) }
                    .onFailure { list.addView(caption(ctx, "Could not list apps: ${it.message}")) }
            }
        }
        return ScrollView(ctx).apply { addView(col) }
    }

    /** Launchable, non-fleet packages, classified and sorted: shelf order, then
     *  label; unshelved last. The fleet is excluded by its own manifest (every
     *  package and resigned-stock alt id it declares), not by a name prefix. */
    private fun rows(ctx: Context): List<Row> {
        val pm = ctx.packageManager
        val fleetPkgs = Fleet.parse(BuildConfig.CONSTELLATION_FLEET_B64)
            .flatMap { listOfNotNull(it.pkg, it.altId) }.toSet() + ctx.packageName
        val labels = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first !in fleetPkgs }
            .toMap()
        val shelves = AppStoreHost.classify(ctx, labels)
        return labels.map { (pkg, label) -> Row(pkg, label, shelves[pkg]) }
            .sortedWith(compareBy({ it.shelf?.order ?: UNSHELVED }, { it.label.lowercase() }))
    }

    private fun render(ctx: Context, into: LinearLayout, rows: List<Row>) {
        into.addView(caption(ctx, "${rows.size} apps"))
        var heading: String? = null
        for (r in rows) {
            val here = r.shelf?.heading ?: if (heading != null) OTHER else null
            if (here != null && here != heading) into.addView(TextView(ctx).apply {
                text = here; textSize = 12f; setTextColor(cHead)
                setPadding(0, dp(ctx, 10), 0, dp(ctx, 4))
            })
            heading = here
            into.addView(row(ctx, r))
        }
    }

    private fun row(ctx: Context, r: Row) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(0xFF1C1C24.toInt())
        setPadding(dp(ctx, 12), dp(ctx, 6), dp(ctx, 6), dp(ctx, 6))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(ctx, 2), 0, dp(ctx, 2)) }
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(ctx).apply {
                text = r.label; textSize = 14f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFFFFFFFF.toInt()); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            addView(TextView(ctx).apply {
                text = r.pkg; textSize = 11f; typeface = Typeface.MONOSPACE; setTextColor(cDim)
                maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
            })
        })
        addView(btn(ctx, "Open") {
            val launch = ctx.packageManager.getLaunchIntentForPackage(r.pkg)
            if (launch != null) startActivity(launch)
            else Toast.makeText(ctx, "No longer installed", Toast.LENGTH_SHORT).show()
        })
        addView(btn(ctx, "App info") {
            runCatching {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", r.pkg, null)))
            }.onFailure { Toast.makeText(ctx, "No settings screen", Toast.LENGTH_SHORT).show() }
        })
    }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
    private fun caption(ctx: Context, t: String) = TextView(ctx).apply {
        text = t; textSize = 12f; setTextColor(cDim); setPadding(0, 0, 0, dp(ctx, 8))
    }
    private fun btn(ctx: Context, label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        setTextColor(0xFFFFFFFF.toInt()); setBackgroundColor(0xFF2A2A33.toInt())
        setPadding(dp(ctx, 10), dp(ctx, 7), dp(ctx, 10), dp(ctx, 7))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(ctx, 4), 0, 0, 0) }
        isClickable = true; setOnClickListener { onClick() }
    }

    private companion object {
        const val UNSHELVED = "￿"
        const val OTHER = "Other"
    }
}
