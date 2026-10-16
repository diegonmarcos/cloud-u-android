package com.diegonmarcos.superapp.appstore

import android.content.ActivityNotFoundException
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
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

    /**
     * The plan as a dialog over the Phone page: [plan] and a Close. [onDismiss] drops it. Each action is
     * a tap; the dialog itself acts on nothing.
     */
    @Composable
    fun PlanDialog(plan: AppInventory.Plan, onDismiss: () -> Unit) {
        val ctx = LocalContext.current
        AlertDialog(
            onDismissRequest = onDismiss,
            modifier = Modifier.testTag(TAG),
            title = { Text(ctx.getString(R.string.store_import_title), color = Ink.WHITE.ink()) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(top = sdp(StoreDensity.S8))) {
                    Plan(ctx, plan)
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text(ctx.getString(R.string.store_close)) } },
        )
    }

    const val TAG = "store-import-plan"

    @Composable
    private fun ColumnScope.Plan(ctx: Context, plan: AppInventory.Plan) {
        Line(ctx.getString(R.string.store_import_summary, plan.installed.size, plan.store.size, plan.manual.size),
            StoreDensity.T_BODY, bold = true)
        // Fleet members the phone lacks are the Cloud page's (constellation install / update
        // all): this page neither lists them as declared nor installs them. One line says so.
        if (plan.ours.isNotEmpty()) Line(ctx.getString(R.string.store_import_ours_cloud_page, plan.ours.size), StoreDensity.T_CAPTION)

        // Counted SEPARATELY: these install fine from Play's servers, but the
        // app itself may refuse to run without Google Play as its installer.
        if (plan.needIntegrity.isNotEmpty()) {
            Line(ctx.getString(R.string.store_import_need_integrity, plan.needIntegrity.size), StoreDensity.T_CAPTION, bold = true)
            plan.needIntegrity.forEach { Line(it.pkg + "  ·  " + ctx.getString(R.string.store_phone_integrity_badge), StoreDensity.T_CAPTION, mono = true) }
        }
        val missing = plan.direct.size
        val play = plan.store.size + plan.manual.size
        if (missing > 0) Verb(ctx.getString(R.string.store_import_install_missing, missing, play), "$TAG:install-missing") {
            val app = ctx.applicationContext
            val cfg = PhoneAppActions.resolver(PhoneAppActions.sources(app))
            Toast.makeText(ctx, ctx.getString(R.string.store_phone_install_all_start, missing, play), Toast.LENGTH_LONG).show()
            thread(name = "store-import-missing") { installMissing(app, cfg, plan) }
        }
        if (plan.direct.isNotEmpty()) {
            Heading(ctx.getString(R.string.store_import_direct, plan.direct.size))
            plan.direct.forEach { Line(it.pkg, StoreDensity.T_CAPTION, mono = true) }
            Verb(ctx.getString(R.string.store_import_install_direct, plan.direct.size), "$TAG:install-direct") {
                val app = ctx.applicationContext
                val cfg = PhoneAppActions.resolver(PhoneAppActions.sources(app))
                Toast.makeText(ctx, ctx.getString(R.string.store_import_installing, plan.direct.size), Toast.LENGTH_SHORT).show()
                // One at a time: each install may raise the system confirm sheet.
                thread(name = "store-import-direct") {
                    for (e in plan.direct) ExternalInstall.run(app, cfg, SourceResolver.resolve(cfg, e.pkg))
                }
            }
        }
        if (plan.store.isNotEmpty()) {
            Heading(ctx.getString(R.string.store_import_store, plan.store.size))
            for (link in plan.store) Row(verticalAlignment = Alignment.CenterVertically) {
                Line(link.entry.pkg, StoreDensity.T_CAPTION, mono = true, modifier = Modifier.weight(1f))
                Verb(ctx.getString(R.string.store_import_open_in, link.label), "$TAG:open:${link.entry.pkg}", Modifier) {
                    // Aimed at the store that installed it on the old phone; if
                    // that store is not on this one, say so rather than letting
                    // another market handler pick the app up.
                    try { ctx.startActivity(link.intent) } catch (e: ActivityNotFoundException) {
                        Toast.makeText(ctx, ctx.getString(R.string.store_import_no_store, link.label), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        if (plan.manual.isNotEmpty()) {
            Heading(ctx.getString(R.string.store_import_manual, plan.manual.size))
            plan.manual.forEach {
                Line(it.pkg + "  ·  " + (it.origin ?: ctx.getString(R.string.store_import_no_origin)), StoreDensity.T_CAPTION, mono = true)
            }
        }
        if (plan.installed.isNotEmpty()) {
            Heading(ctx.getString(R.string.store_import_installed, plan.installed.size))
            plan.installed.forEach { Line(it.pkg, StoreDensity.T_CAPTION, mono = true) }
        }
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

    @Composable
    private fun Heading(t: String) = Text(t, color = 0xFFED8936.toInt().ink(), fontSize = StoreDensity.T_META.sp,
        fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = sdp(StoreDensity.S12), bottom = sdp(StoreDensity.S4)))

    @Composable
    private fun Line(t: String, size: Float, bold: Boolean = false, mono: Boolean = false, modifier: Modifier = Modifier) =
        Text(t, color = Ink.WHITE.ink(), fontSize = size.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            fontFamily = if (mono) FontFamily.Monospace else null, modifier = modifier)

    @Composable
    private fun Verb(label: String, tag: String, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) =
        StoreBar.Button(label, Ink.BLUE_FILL, onClick, modifier, tag)
}
