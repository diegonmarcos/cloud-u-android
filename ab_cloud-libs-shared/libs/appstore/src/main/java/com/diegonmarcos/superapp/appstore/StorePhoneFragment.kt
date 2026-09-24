package com.diegonmarcos.superapp.appstore

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.adbdebug.ShellChannels
import com.diegonmarcos.superapp.updater.Fleet
import kotlin.concurrent.thread

/**
 * Store ▸ Phone Apps (#563, #564) — every launchable APK on the device,
 * grouped by the host's central classification exactly as
 * [StoreCloudFragment] groups the fleet.
 *
 * Each row's buttons are [PhoneAppActions.of]: Update (Cloud fleet apps only,
 * through [FleetInstall], the Cloud tab's own path), Open, Stop, Remove,
 * App info, and the origin store read from the real installer. Which of them
 * work is decided there from the device's own answers; a button that cannot
 * work is drawn dimmed and says why when tapped.
 *
 * Above the rows (#565): the Cloud tab's own top bar ([StoreBar] — one
 * declaration, two pages), then Export / Import of the installed-apps
 * inventory ([AppInventory]); an import shows its plan ([StoreImport]) before
 * anything acts.
 */
class StorePhoneFragment : Fragment() {

    private class Row(val pkg: String, val label: String, val shelf: AppStoreHost.Shelf?,
                      val fleetApp: Fleet.App?, val actions: List<PhoneAppActions.Action>)

    private val cDim = 0x99FFFFFF.toInt()
    private val cHead = 0xFFED8936.toInt()
    private var list: LinearLayout? = null

    // #565 export / import. Registered at construction, as the Activity Result
    // API requires; the system picker owns where the file lives.
    private val exportDoc = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) exportTo(uri)
    }
    private val importDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFrom(uri)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext()
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(ctx, 14); setPadding(p, p, p, p)
        }
        // The SAME bar the Cloud tab draws. Check all re-reads this phone;
        // Install all and Update all would need a privileged channel no
        // foreign package gives us (#225), so they are drawn disabled with the
        // reason under them - per-app Update on a fleet row is the real path.
        col.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            StoreBar.render(this@StorePhoneFragment, this, StoreBar.Verbs(
                checkAll = { reload() }, installAll = { reload() }, updateAll = null,
                disabledReason = R.string.store_phone_bar_disabled))
        })
        col.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(fileBtn(ctx, ctx.getString(R.string.store_export)) { exportDoc.launch(EXPORT_NAME) })
            addView(fileBtn(ctx, ctx.getString(R.string.store_import)) { importDoc.launch(IMPORT_TYPES) })
        })
        col.addView(caption(ctx, ctx.getString(R.string.store_phone_caption)))
        val rowsView = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        list = rowsView
        col.addView(rowsView)
        return ScrollView(ctx).apply { addView(col) }
    }

    // Reloaded on every return: Remove and App info leave for a system
    // screen, and what they changed must not be drawn stale.
    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val ctx = requireContext()
        val into = list ?: return
        into.removeAllViews()
        into.addView(caption(ctx, ctx.getString(R.string.store_phone_loading)))
        // Enumerating, classifying and probing a few hundred packages is
        // PackageManager IPC — off the main thread, the #261 lesson.
        val app = ctx.applicationContext
        thread(name = "store-phone-apps") {
            val rows = runCatching { rows(app) }
            into.post {
                if (!isAdded) return@post
                into.removeAllViews()
                rows.onSuccess { render(ctx, into, it) }
                    .onFailure { into.addView(caption(ctx, ctx.getString(R.string.store_phone_list_failed, it.message))) }
            }
        }
    }

    override fun onDestroyView() { list = null; super.onDestroyView() }

    /** Every launchable app, fleet included, as [AppInventory] JSON. */
    private fun exportTo(uri: Uri) {
        val app = requireContext().applicationContext
        thread(name = "store-export") {
            val result = runCatching {
                val entries = AppInventory.entriesFor(app, AppInventory.launchable(app))
                app.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(AppInventory.toJson(entries).toByteArray()) }
                entries.size
            }
            toastLater(app, result.fold({ app.getString(R.string.store_export_done, it) },
                { app.getString(R.string.store_file_failed, it.message) }))
        }
    }

    /** Read a file, diff it against this phone, show the plan. Acts on nothing. */
    private fun importFrom(uri: Uri) {
        val app = requireContext().applicationContext
        thread(name = "store-import") {
            val result = runCatching {
                val text = app.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
                val wanted = AppInventory.parse(text)
                val pm = app.packageManager
                val installed = wanted.map { it.pkg }.filter { runCatching { pm.getPackageInfo(it, 0) }.isSuccess }.toSet()
                AppInventory.plan(wanted, installed, AppInventory.fleetPackages(), PhoneAppActions.sources(app))
            }
            view?.post {
                if (!isAdded) return@post
                result.onSuccess { StoreImport.show(this, it) }
                    .onFailure { Toast.makeText(app, app.getString(R.string.store_file_failed, it.message), Toast.LENGTH_LONG).show() }
            }
        }
    }

    /** Launchable packages with their actions, classified and sorted: shelf
     *  order, then label; unshelved last. */
    private fun rows(ctx: Context): List<Row> {
        val pm = ctx.packageManager
        val fleet = PhoneAppActions.fleetByPackage(Fleet.parse(BuildConfig.CONSTELLATION_FLEET_B64))
        val sources = PhoneAppActions.sources(ctx)
        val shellReady = ShellChannels.active(ctx) != null
        val labels = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .associate { it.activityInfo.packageName to it.loadLabel(pm).toString() }
        val shelves = AppStoreHost.classify(ctx, labels)
        return labels.map { (pkg, label) ->
            Row(pkg, label, shelves[pkg], fleet[pkg], PhoneAppActions.of(ctx, pkg, fleet[pkg], shellReady, sources))
        }.sortedWith(compareBy({ it.shelf?.order ?: UNSHELVED }, { it.label.lowercase() }))
    }

    private fun render(ctx: Context, into: LinearLayout, rows: List<Row>) {
        into.addView(caption(ctx, ctx.getString(R.string.store_phone_count, rows.size)))
        var heading: String? = null
        for (r in rows) {
            val here = r.shelf?.heading ?: if (heading != null) ctx.getString(R.string.store_phone_other) else null
            if (here != null && here != heading) into.addView(TextView(ctx).apply {
                text = here; textSize = 12f; setTextColor(cHead)
                setPadding(0, dp(ctx, 10), 0, dp(ctx, 4))
            })
            heading = here
            into.addView(row(ctx, r))
        }
    }

    private fun row(ctx: Context, r: Row) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(0xFF1C1C24.toInt())
        setPadding(dp(ctx, 12), dp(ctx, 6), dp(ctx, 6), dp(ctx, 6))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(ctx, 2), 0, dp(ctx, 2)) }
        addView(TextView(ctx).apply {
            text = r.label; textSize = 14f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt()); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        addView(TextView(ctx).apply {
            text = r.pkg; textSize = 11f; typeface = Typeface.MONOSPACE; setTextColor(cDim)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
        })
        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 4), 0, 0)
        }
        r.actions.forEach { a -> buttons.addView(btn(ctx, a) { act(ctx, r, a) }) }
        addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(buttons) })
    }

    private fun act(ctx: Context, r: Row, a: PhoneAppActions.Action) {
        a.disabledReason?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); return }
        when (a.kind) {
            PhoneAppActions.Kind.UPDATE -> {
                val app = r.fleetApp ?: return
                Toast.makeText(ctx, ctx.getString(R.string.store_phone_updating, r.label), Toast.LENGTH_SHORT).show()
                thread(name = "store-phone-update-${app.id}") {
                    FleetInstall.run(ctx, app)?.let { msg -> toastLater(ctx, ctx.getString(R.string.store_phone_failed, r.label, msg)) }
                }
            }
            PhoneAppActions.Kind.STOP -> thread(name = "store-phone-stop") {
                // Package names are [A-Za-z0-9._] by the platform's own rule,
                // so the id is safe in a shell word as it stands.
                val out = ShellChannels.active(ctx)?.exec(ctx, "am force-stop ${r.pkg} 2>&1 && echo OK")
                toastLater(ctx, if (out?.contains("OK") == true) ctx.getString(R.string.store_phone_stopped, r.label)
                                else ctx.getString(R.string.store_phone_failed, r.label, out?.trim() ?: ctx.getString(R.string.store_phone_why_no_shell)))
            }
            else -> a.intent?.let { i ->
                runCatching { startActivity(i) }
                    .onFailure { Toast.makeText(ctx, ctx.getString(R.string.store_phone_failed, a.label, it.message), Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun toastLater(ctx: Context, msg: String) =
        view?.post { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
    private fun caption(ctx: Context, t: String) = TextView(ctx).apply {
        text = t; textSize = 12f; setTextColor(cDim); setPadding(0, 0, 0, dp(ctx, 8))
    }
    /** Dimmed, not hidden, when it cannot work here: the reason is one tap away. */
    private fun btn(ctx: Context, a: PhoneAppActions.Action, onClick: () -> Unit) = TextView(ctx).apply {
        text = a.label; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        setTextColor(0xFFFFFFFF.toInt()); setBackgroundColor(0xFF2A2A33.toInt())
        alpha = if (a.disabledReason == null) 1f else 0.4f
        contentDescription = a.disabledReason?.let { "${a.label}: $it" } ?: a.label
        setPadding(dp(ctx, 10), dp(ctx, 7), dp(ctx, 10), dp(ctx, 7))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, dp(ctx, 4), 0) }
        isClickable = true; setOnClickListener { onClick() }
    }

    private fun fileBtn(ctx: Context, label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        setTextColor(0xFFFFFFFF.toInt()); setBackgroundColor(0xFF2B6CB0.toInt())
        setPadding(dp(ctx, 10), dp(ctx, 7), dp(ctx, 10), dp(ctx, 7))
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { setMargins(dp(ctx, 3), dp(ctx, 2), dp(ctx, 3), dp(ctx, 8)) }
        isClickable = true; setOnClickListener { onClick() }
    }

    private companion object {
        const val UNSHELVED = "￿"
        const val EXPORT_NAME = "cloud-sa-apps.json"
        // A .json picked from Downloads is as often octet-stream as json.
        val IMPORT_TYPES = arrayOf("application/json", "application/octet-stream", "text/plain")
    }
}
