package com.diegonmarcos.superapp.appstore

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.updater.Fleet
import kotlin.concurrent.thread

/**
 * The import summary (#565): what an inventory file means on this phone, shown
 * BEFORE anything acts. Nothing here runs on its own — each action is a tap:
 *
 *   ours    → one button, the Constellation install path Install all uses
 *             (Fleet.installAll, MISSING), over exactly the missing fleet apps.
 *   direct  → one button (#571): the apps whose declared ladder has a vendor
 *             or F-Droid rung, installed by ExternalInstall — no other store.
 *   store   → one button per app that OPENS its origin store's page. A foreign
 *             app with no direct rung is never installed from here; its store
 *             does that, with the user in front of it.
 *   manual  → listed with whatever origin the file recorded. Nothing to press.
 */
object StoreImport {

    fun show(host: Fragment, plan: AppInventory.Plan) {
        val ctx = host.requireContext()
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(ctx, 16); setPadding(p, dp(ctx, 8), p, p)
        }
        col.addView(text(ctx, ctx.getString(R.string.store_import_summary,
            plan.installed.size, plan.ours.size + plan.store.size, plan.manual.size), 13f, bold = true))

        if (plan.ours.isNotEmpty()) {
            col.addView(heading(ctx, ctx.getString(R.string.store_import_ours, plan.ours.size)))
            plan.ours.forEach { col.addView(text(ctx, it.pkg, 11f, mono = true)) }
            col.addView(button(ctx, ctx.getString(R.string.store_import_install_ours, plan.ours.size)) {
                val want = plan.ours.map { it.pkg }.toSet()
                val apps = Fleet.parse(BuildConfig.CONSTELLATION_FLEET_B64)
                    .filter { it.pkg in want || (it.altId ?: "") in want }
                Toast.makeText(ctx, ctx.getString(R.string.store_import_installing, apps.size), Toast.LENGTH_SHORT).show()
                thread(name = "store-import-install") { Fleet.installAll(ctx, apps, Fleet.Mode.MISSING) }
            })
        }
        if (plan.direct.isNotEmpty()) {
            col.addView(heading(ctx, ctx.getString(R.string.store_import_direct, plan.direct.size)))
            plan.direct.forEach { col.addView(text(ctx, it.pkg, 11f, mono = true)) }
            col.addView(button(ctx, ctx.getString(R.string.store_import_install_direct, plan.direct.size)) {
                val app = ctx.applicationContext
                val cfg = PhoneAppActions.resolver(PhoneAppActions.sources(app))
                Toast.makeText(ctx, ctx.getString(R.string.store_import_installing, plan.direct.size), Toast.LENGTH_SHORT).show()
                // One at a time: each install may raise the system confirm sheet.
                thread(name = "store-import-direct") {
                    for (e in plan.direct) ExternalInstall.run(app, cfg, SourceResolver.resolve(cfg, e.pkg))
                }
            })
        }
        if (plan.store.isNotEmpty()) {
            col.addView(heading(ctx, ctx.getString(R.string.store_import_store, plan.store.size)))
            for (link in plan.store) col.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(text(ctx, link.entry.pkg, 11f, mono = true).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(button(ctx, ctx.getString(R.string.store_import_open_in, link.label)) {
                    // Aimed at the store that installed it on the old phone; if
                    // that store is not on this one, say so rather than letting
                    // another market handler pick the app up.
                    try { host.startActivity(link.intent) } catch (e: ActivityNotFoundException) {
                        Toast.makeText(ctx, ctx.getString(R.string.store_import_no_store, link.label), Toast.LENGTH_LONG).show()
                    }
                })
            })
        }
        if (plan.manual.isNotEmpty()) {
            col.addView(heading(ctx, ctx.getString(R.string.store_import_manual, plan.manual.size)))
            plan.manual.forEach {
                col.addView(text(ctx, it.pkg + "  ·  " + (it.origin ?: ctx.getString(R.string.store_import_no_origin)), 11f, mono = true))
            }
        }
        if (plan.installed.isNotEmpty()) {
            col.addView(heading(ctx, ctx.getString(R.string.store_import_installed, plan.installed.size)))
            plan.installed.forEach { col.addView(text(ctx, it.pkg, 11f, mono = true)) }
        }
        AlertDialog.Builder(host.requireActivity())
            .setTitle(R.string.store_import_title)
            .setView(ScrollView(ctx).apply { addView(col) })
            .setPositiveButton(R.string.store_close, null)
            .show()
    }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
    private fun heading(ctx: Context, t: String) = text(ctx, t, 12f, bold = true).apply {
        setTextColor(0xFFED8936.toInt()); setPadding(0, dp(ctx, 12), 0, dp(ctx, 4))
    }
    private fun text(ctx: Context, t: String, size: Float, bold: Boolean = false, mono: Boolean = false) = TextView(ctx).apply {
        text = t; textSize = size
        typeface = when { mono -> Typeface.MONOSPACE; bold -> Typeface.DEFAULT_BOLD; else -> Typeface.DEFAULT }
    }
    private fun button(ctx: Context, label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        setTextColor(0xFFFFFFFF.toInt()); setBackgroundColor(0xFF2B6CB0.toInt())
        setPadding(dp(ctx, 10), dp(ctx, 7), dp(ctx, 10), dp(ctx, 7))
        isClickable = true; setOnClickListener { onClick() }
    }
}
