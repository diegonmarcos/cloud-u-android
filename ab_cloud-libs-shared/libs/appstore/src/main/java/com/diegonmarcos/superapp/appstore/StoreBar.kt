package com.diegonmarcos.superapp.appstore

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.adbdebug.PackageVerifier
import com.diegonmarcos.superapp.adbdebug.ShellAccess
import com.diegonmarcos.superapp.adbdebug.WirelessDebugging
import com.diegonmarcos.superapp.updater.AutoUpdatePrefs
import com.diegonmarcos.superapp.updater.UpdateProgress
import kotlin.concurrent.thread

/**
 * THE Store top bar (#565) — one declaration, drawn by both Store tabs. It was
 * StoreCloudFragment.renderHeader; Phone Apps needed the same bar, and a second
 * copy is how two bars drift (#228).
 *
 * Only three verbs belong to the page: Check all, Install all, Update all. The
 * page passes each as a lambda, or null when it cannot do it — Phone Apps has no
 * privileged channel to install or update a package it does not own (#225), so
 * it passes null and the bar draws that button disabled with [Verbs.disabledReason]
 * under it. Every other control (auto-update, Wi-Fi-only, Play Protect, Wireless
 * Debugging, Developer options) is device state, identical on both tabs.
 *
 * Each control is tagged with its [Item] id, so a test can read the rendered bar
 * rather than this source.
 */
object StoreBar {

    const val TAG = "store-bar"

    enum class Item { CHECK, INSTALL, UPDATE, AUTO_UPDATE, WIFI_ONLY, PLAY_PROTECT, WIRELESS_DEBUG, DEV_OPTIONS }

    class Verbs(
        val checkAll: () -> Unit,
        val installAll: (() -> Unit)?,
        val updateAll: (() -> Unit)?,
        /** Why a null verb is null. Drawn under the bar whenever one is. */
        val disabledReason: Int = 0,
    )

    private val cDim = 0x99FFFFFF.toInt()

    /** (Re)draws the bar into [into]. A toggle redraws it from the device's new state. */
    fun render(host: Fragment, into: LinearLayout, verbs: Verbs) {
        val ctx = host.requireContext()
        into.removeAllViews(); into.tag = TAG
        fun redraw() { if (host.isAdded) render(host, into, verbs) }
        // Exactly two rows of controls: the batch actions and the auto-update
        // switch on the first, the configs and the remaining switches on the
        // second. Check all refreshes what is on offer, changing nothing; Install
        // all takes only what is not on the device; Update all is every group in
        // one pass, never tab-scoped.
        val actionRow = row(ctx); val configRow = row(ctx)
        into.addView(actionRow); into.addView(configRow)

        actionRow.addView(btn(ctx, Item.CHECK, ctx.getString(R.string.store_bar_check_all), 0xFF2B6CB0.toInt(), verbs.checkAll))
        actionRow.addView(btn(ctx, Item.INSTALL, ctx.getString(R.string.store_bar_install_all), 0xFF2B6CB0.toInt(), verbs.installAll))
        actionRow.addView(btn(ctx, Item.UPDATE, ctx.getString(R.string.store_bar_update_all), 0xFF2B6CB0.toInt(), verbs.updateAll))
        val autoOn = AutoUpdatePrefs.enabled(ctx)
        actionRow.addView(btn(ctx, Item.AUTO_UPDATE, ctx.getString(R.string.store_bar_auto_update, onOff(ctx, autoOn)),
            if (autoOn) 0xFF2F855A.toInt() else 0xFF4A4A55.toInt()) {
            AutoUpdatePrefs.setEnabled(ctx, !autoOn)
            // Reconcile the periodic workers immediately: start() schedules
            // when enabled, cancels when disabled (both re-check the pref).
            com.diegonmarcos.superapp.updater.Updater.start(ctx)
            ConstellationWorker.start(ctx)
            toast(ctx, ctx.getString(R.string.store_bar_auto_update_toast, onOff(ctx, !autoOn)))
            redraw()
        })
        // Only gates the UNATTENDED passes: the buttons above are the user
        // asking, so they download on any network regardless of this.
        val wifiOnly = AutoUpdatePrefs.requireUnmetered(ctx)
        configRow.addView(btn(ctx, Item.WIFI_ONLY, ctx.getString(R.string.store_bar_wifi_only, onOff(ctx, wifiOnly)),
            if (wifiOnly) 0xFF2F855A.toInt() else 0xFF4A4A55.toInt()) {
            AutoUpdatePrefs.setRequireUnmetered(ctx, !wifiOnly)
            toast(ctx, ctx.getString(R.string.store_bar_wifi_only_toast, onOff(ctx, !wifiOnly)))
            redraw()
        })
        if (verbs.disabledReason != 0 &&
            (verbs.installAll == null || verbs.updateAll == null)) into.addView(caption(ctx, ctx.getString(verbs.disabledReason)))
        into.addView(caption(ctx, ctx.getString(
            if (AutoUpdatePrefs.canInstallSilently(ctx)) R.string.store_bar_silent_on else R.string.store_bar_silent_off)))

        // Play Protect's per-INSTALL scan prompt, which no installer can opt out
        // of from inside its own process. Writing the verifier settings needs
        // WRITE_SECURE_SETTINGS, so it goes through the shell channel (Shizuku /
        // embedded adb). Reading is unprivileged, so the label is always the
        // device's real state even with no channel present.
        val scan = PackageVerifier.state(ctx)
        configRow.addView(btn(ctx, Item.PLAY_PROTECT, ctx.getString(R.string.store_bar_play_protect, onOff(ctx, scan.on)),
            if (scan.on) 0xFF4A4A55.toInt() else 0xFF2F855A.toInt()) {
            toast(ctx, ctx.getString(R.string.store_bar_asking_shell))
            // setScanning binds Shizuku, which blocks - never on the main thread.
            thread(name = "play-protect-toggle") {
                val want = !scan.on
                fun apply(): PackageVerifier.Result = PackageVerifier.setScanning(ctx, want)
                fun report(r: PackageVerifier.Result) = into.post {
                    Toast.makeText(ctx, if (r.ok) r.state.describe() + " - via " + r.channel else r.output,
                        Toast.LENGTH_LONG).show()
                    redraw()
                }
                val first = apply()
                if (first.channel != "none") { report(first); return@thread }
                // No channel yet: START the flow that grants one instead of
                // telling the user to go find it.
                val msg = ShellAccess.ensure(ctx) { thread(name = "play-protect-retry") { report(apply()) } }
                into.post { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
            }
        })
        into.addView(caption(ctx, ctx.getString(
            if (!scan.on) R.string.store_bar_play_protect_off else R.string.store_bar_play_protect_on)))

        // Wireless Debugging, the switch every silent install rests on: the
        // embedded adb channel talks to the adbd this starts. [WirelessDebugging]
        // re-reads the setting, so the label is the device's answer, not ours.
        // The OS exposes no action for its sub-screen, so "Open" deep-links to
        // Developer options, where it lives.
        val wd = WirelessDebugging.isOn(ctx)
        configRow.addView(btn(ctx, Item.WIRELESS_DEBUG, ctx.getString(R.string.store_bar_wireless_debug, onOff(ctx, wd)),
            if (wd) 0xFF2F855A.toInt() else 0xFF4A4A55.toInt()) {
            toast(ctx, ctx.getString(R.string.store_bar_asking_shell))
            thread(name = "wireless-debug-toggle") {
                // busy = an install batch holds the lease; cutting the
                // channel underneath one strands a half-finished install.
                val st = UpdateProgress.state
                val busy = UpdateProgress.batchLabel != null ||
                    st is UpdateProgress.State.Downloading || st is UpdateProgress.State.Installing
                val r = WirelessDebugging.set(ctx, !wd, busy = busy)
                into.post {
                    Toast.makeText(ctx,
                        (if (r.ok) ctx.getString(R.string.store_bar_wireless_changed, onOff(ctx, r.on), r.channel)
                         else ctx.getString(R.string.store_bar_wireless_unchanged, r.channel, onOff(ctx, r.on))) +
                            "\n" + r.detail,
                        Toast.LENGTH_LONG).show()
                    redraw()
                }
            }
        })
        configRow.addView(btn(ctx, Item.DEV_OPTIONS, ctx.getString(R.string.store_bar_open), 0xFF7C3AED.toInt()) {
            val dev = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            host.startActivity(if (dev.resolveActivity(ctx.packageManager) != null) dev else Intent(Settings.ACTION_SETTINGS))
        })
        into.addView(caption(ctx, ctx.getString(
            if (wd) R.string.store_bar_wireless_on else R.string.store_bar_wireless_off)))
    }

    private fun onOff(ctx: Context, on: Boolean) = ctx.getString(if (on) R.string.store_on else R.string.store_off)
    private fun toast(ctx: Context, t: String) = Toast.makeText(ctx, t, Toast.LENGTH_SHORT).show()
    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
    private fun row(ctx: Context) = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
    private fun caption(ctx: Context, t: String) = TextView(ctx).apply {
        text = t; textSize = 12f; setTextColor(cDim); setPadding(0, 0, 0, dp(ctx, 8))
    }

    /** A null [onClick] is a verb this page cannot do: drawn, dimmed, not clickable. */
    private fun btn(ctx: Context, item: Item, label: String, bg: Int, onClick: (() -> Unit)?) = TextView(ctx).apply {
        tag = item
        text = label; gravity = Gravity.CENTER; textSize = 12f; typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(ctx, 8), dp(ctx, 7), dp(ctx, 8), dp(ctx, 7))
        setTextColor(0xFFFFFFFF.toInt())
        setBackgroundColor(if (onClick != null) bg else 0xFF3A3A44.toInt())
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        lp.setMargins(dp(ctx, 3), dp(ctx, 4), dp(ctx, 3), dp(ctx, 2)); layoutParams = lp
        isEnabled = onClick != null
        if (onClick != null) { isClickable = true; setOnClickListener { onClick() } } else alpha = 0.45f
    }
}
