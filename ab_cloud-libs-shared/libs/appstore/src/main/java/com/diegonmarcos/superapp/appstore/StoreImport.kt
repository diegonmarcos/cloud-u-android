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
import com.diegonmarcos.superapp.updater.UpdateProgress
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
 *
 * #570 "Install all missing": ours + direct in ONE [BatchInstall] pass (every
 * download before any install), store + manual skipped and counted as "need
 * Play". It is the plan diffed against THIS phone, so a second tap finds
 * nothing to do; nothing here removes a package.
 *
 * [pending] is the Account → Store hand-off: Fleet ▸ Apps ▸ "Apply list to
 * Store" puts the vault's declared inventory (the same #565 JSON) here, the
 * Phone page consumes it on resume and shows this plan — the file picker's
 * path without the file. Across processes it rides the OPEN Intent's `import`
 * extra ([EXTRA_IMPORT]) and lands here again.
 */
object StoreImport {

    const val EXTRA_IMPORT = "import"

    /** A declared inventory (AppInventory JSON) waiting for the Phone page. Read once. */
    @Volatile var pending: String? = null

    fun takePending(): String? = pending.also { pending = null }

    fun show(host: Fragment, plan: AppInventory.Plan) {
        val ctx = host.requireContext()
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(ctx, StoreDensity.S12); setPadding(p, dp(ctx, StoreDensity.S8), p, p)
        }
        col.addView(text(ctx, ctx.getString(R.string.store_import_summary,
            plan.installed.size, plan.store.size, plan.manual.size), StoreDensity.T_BODY, bold = true))
        // Fleet members the phone lacks are the Cloud page's (constellation install / update
        // all): this page neither lists them as declared nor installs them. One line says so.
        if (plan.ours.isNotEmpty()) col.addView(text(ctx, ctx.getString(R.string.store_import_ours_cloud_page, plan.ours.size), StoreDensity.T_CAPTION))

        // Counted SEPARATELY: these install fine from Play's servers, but the
        // app itself may refuse to run without Google Play as its installer.
        if (plan.needIntegrity.isNotEmpty()) {
            col.addView(text(ctx, ctx.getString(R.string.store_import_need_integrity, plan.needIntegrity.size), StoreDensity.T_CAPTION, bold = true))
            plan.needIntegrity.forEach { col.addView(text(ctx, it.pkg + "  ·  " + ctx.getString(R.string.store_phone_integrity_badge), StoreDensity.T_CAPTION, mono = true)) }
        }
        val missing = plan.direct.size
        val play = plan.store.size + plan.manual.size
        if (missing > 0) col.addView(button(ctx, ctx.getString(R.string.store_import_install_missing, missing, play)) {
            val app = ctx.applicationContext
            val cfg = PhoneAppActions.resolver(PhoneAppActions.sources(app))
            Toast.makeText(ctx, ctx.getString(R.string.store_phone_install_all_start, missing, play), Toast.LENGTH_LONG).show()
            thread(name = "store-import-missing") { installMissing(app, cfg, plan) }
        })
        if (plan.direct.isNotEmpty()) {
            col.addView(heading(ctx, ctx.getString(R.string.store_import_direct, plan.direct.size)))
            plan.direct.forEach { col.addView(text(ctx, it.pkg, StoreDensity.T_CAPTION, mono = true)) }
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
                addView(text(ctx, link.entry.pkg, StoreDensity.T_CAPTION, mono = true).apply {
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
                col.addView(text(ctx, it.pkg + "  ·  " + (it.origin ?: ctx.getString(R.string.store_import_no_origin)), StoreDensity.T_CAPTION, mono = true))
            }
        }
        if (plan.installed.isNotEmpty()) {
            col.addView(heading(ctx, ctx.getString(R.string.store_import_installed, plan.installed.size)))
            plan.installed.forEach { col.addView(text(ctx, it.pkg, StoreDensity.T_CAPTION, mono = true)) }
        }
        AlertDialog.Builder(host.requireActivity())
            .setTitle(R.string.store_import_title)
            .setView(ScrollView(ctx).apply { addView(col) })
            .setPositiveButton(R.string.store_close, null)
            .show()
    }

    /**
     * Every app the plan says this store can install itself — fleet members by
     * the fleet path, external ones by their declared ladder (vendor → F-Droid)
     * — through the one two-phase engine. `store` and `manual` are not targets:
     * a Play-only app keeps its "needs Play" hand-off. Blocking.
     */
    fun installMissing(app: Context, cfg: SourceResolver.Config, plan: AppInventory.Plan): List<BatchInstall.Outcome> {
        // Foreign apps only: a fleet member is the Cloud page's install, never this one's.
        val targets = plan.direct.map { e -> SourceResolver.resolve(cfg, e.pkg).let { BatchInstall.Target(e.pkg, it.label, null, it) } }
        val outcomes = BatchInstall.run(app, targets, BatchInstall.engine(cfg)) { phase, t, i, n ->
            UpdateProgress.beginBatch((if (phase == BatchInstall.Phase.DOWNLOAD) "\u2193 " else "") + t.label, i, n)
        }
        UpdateProgress.endBatch()
        val failed = outcomes.filter { !it.installed }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            failed.forEach { o -> Toast.makeText(app, app.getString(R.string.store_phone_failed, o.target.label,
                o.message ?: app.getString(R.string.store_cache_kept)), Toast.LENGTH_LONG).show() }
            Toast.makeText(app, app.getString(R.string.store_phone_batch_done, outcomes.size - failed.size, failed.size), Toast.LENGTH_LONG).show()
        }
        return outcomes
    }

    private fun dp(ctx: Context, v: Int) = StoreDensity.dp(ctx, v)
    private fun heading(ctx: Context, t: String) = text(ctx, t, StoreDensity.T_META, bold = true).apply {
        setTextColor(0xFFED8936.toInt()); setPadding(0, dp(ctx, StoreDensity.S12), 0, dp(ctx, StoreDensity.S4))
    }
    private fun text(ctx: Context, t: String, size: Float, bold: Boolean = false, mono: Boolean = false) = TextView(ctx).apply {
        text = t; textSize = size
        typeface = when { mono -> Typeface.MONOSPACE; bold -> Typeface.DEFAULT_BOLD; else -> Typeface.DEFAULT }
    }
    private fun button(ctx: Context, label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; textSize = StoreDensity.T_META; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        setTextColor(0xFFFFFFFF.toInt()); setBackgroundColor(0xFF2B6CB0.toInt())
        setPadding(dp(ctx, StoreDensity.S8), dp(ctx, StoreDensity.S6), dp(ctx, StoreDensity.S8), dp(ctx, StoreDensity.S6))
        isClickable = true; setOnClickListener { onClick() }
    }
}
