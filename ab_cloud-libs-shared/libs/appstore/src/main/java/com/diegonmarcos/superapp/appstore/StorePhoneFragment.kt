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
import com.diegonmarcos.superapp.updater.UpdateProgress
import kotlin.concurrent.thread

/**
 * Store ▸ Phone Apps (#563, #564, #565, #571) — every app that BELONGS on this
 * phone, grouped by the host's central classification exactly as
 * [StoreCloudFragment] groups the fleet: what is installed, PLUS every fleet
 * app the constellation manifest declares, PLUS every external app the
 * install-source map declares. On a fresh phone the list is therefore full,
 * with every row saying "not installed" and how it installs.
 *
 * Each row carries a state line — installed version, update available, not
 * installed, or the 'needs Play' badge — and its buttons: [PhoneAppActions.of]
 * for an installed app (Update, Open, Stop, Remove, App info, origin) and
 * [PhoneAppActions.forMissing] for one that is not (Install, origin). Install
 * and Update route through the ONE path per kind: fleet → [FleetInstall],
 * external → [ExternalInstall] over its declared ladder, Play-only → the Play
 * page, never a download.
 *
 * Above the rows: the Cloud tab's own top bar ([StoreBar]). Check all probes
 * every row (fleet: [Fleet.status]; external: [SourceResolver.check]); Install
 * all and Update all walk the rows that can be served without any other store,
 * and say how many were skipped because they need Play. Then Export / Import
 * of the app inventory ([AppInventory]); an import shows its plan
 * ([StoreImport]) before anything acts.
 */
class StorePhoneFragment : Fragment() {

    private class Row(val pkg: String, val label: String, val shelf: AppStoreHost.Shelf?,
                      val fleetApp: Fleet.App?, val external: SourceResolver.External?,
                      val installed: Boolean, val actions: List<PhoneAppActions.Action>) {
        /** This store can put it on the phone by itself. */
        val direct: Boolean get() = (fleetApp != null && !fleetApp.blocked) || (external?.needsPlay == false)
    }

    private val cDim = 0x99FFFFFF.toInt()
    private val cHead = 0xFFED8936.toInt()
    private val cUp = 0xFF48BB78.toInt()
    private val cUpd = 0xFFF6AD55.toInt()
    private val cMiss = 0xFF9F7AEA.toInt()
    private val cBadge = 0xFFE53E3E.toInt()
    private var list: LinearLayout? = null
    private var rows: List<Row> = emptyList()
    private var cfg: SourceResolver.Config? = null
    private val states = HashMap<String, SourceResolver.Check>()
    private val stateViews = HashMap<String, TextView>()

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
        // The SAME bar the Cloud tab draws. #571: Install all and Update all are
        // real verbs here now — they walk every row this store can serve itself
        // (fleet path, vendor APK, F-Droid) and report the rows that need Play.
        col.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            StoreBar.render(this@StorePhoneFragment, this, StoreBar.Verbs(
                checkAll = { checkAll() }, installAll = { installAll() }, updateAll = { updateAll() }))
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

    private fun reload(then: (() -> Unit)? = null) {
        val ctx = requireContext()
        val into = list ?: return
        into.removeAllViews()
        into.addView(caption(ctx, ctx.getString(R.string.store_phone_loading)))
        // Enumerating, classifying and probing a few hundred packages is
        // PackageManager IPC — off the main thread, the #261 lesson.
        val app = ctx.applicationContext
        thread(name = "store-phone-apps") {
            val built = runCatching { rows(app) }
            into.post {
                if (!isAdded) return@post
                into.removeAllViews()
                built.onSuccess { rows = it; render(ctx, into, it); then?.invoke() }
                    .onFailure { into.addView(caption(ctx, ctx.getString(R.string.store_phone_list_failed, it.message))) }
            }
        }
    }

    override fun onDestroyView() { list = null; stateViews.clear(); super.onDestroyView() }

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

    /**
     * The DECLARED list: installed launchable apps ∪ fleet apps (kind app) ∪
     * the resolver's external apps, each with its actions and its local state.
     * Sorted: shelf order, then label; unshelved last.
     */
    private fun rows(ctx: Context): List<Row> {
        val pm = ctx.packageManager
        val fleetList = Fleet.parse(BuildConfig.CONSTELLATION_FLEET_B64)
        val fleet = PhoneAppActions.fleetByPackage(fleetList)
        val sources = PhoneAppActions.sources(ctx)
        val resolver = PhoneAppActions.resolver(sources).also { cfg = it }
        val shellReady = ShellChannels.active(ctx) != null
        val declared = LinkedHashMap<String, String>()
        declared.putAll(AppInventory.launchable(ctx))
        fleetList.filter { it.kind == "app" }.forEach { declared.putIfAbsent(it.pkg, it.label) }
        resolver.apps.values.forEach { declared.putIfAbsent(it.pkg, it.label) }
        val shelves = AppStoreHost.classify(ctx, declared)
        states.clear()
        return declared.map { (pkg, label) ->
            val installed = runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess
            val fa = fleet[pkg]
            val ext = if (fa == null) SourceResolver.resolve(resolver, pkg) else null
            val actions = if (installed) PhoneAppActions.of(ctx, pkg, fa, shellReady, sources)
                          else PhoneAppActions.forMissing(ctx, ext ?: SourceResolver.resolve(resolver, pkg), fa, sources, resolver)
            states[pkg] = when {
                ext != null -> SourceResolver.local(ctx, ext)
                installed -> SourceResolver.installed(ctx, pkg)?.let { SourceResolver.Check.Installed(it.first, it.second, null, SourceResolver.Note.NONE) }
                    ?: SourceResolver.Check.Unknown(null, "")
                else -> SourceResolver.Check.NotInstalled(SourceResolver.VIA_FLEET, needsPlay = false)
            }
            Row(pkg, label, shelves[pkg], fa, ext, installed, actions)
        }.sortedWith(compareBy({ it.shelf?.order ?: UNSHELVED }, { it.label.lowercase() }))
    }

    private fun render(ctx: Context, into: LinearLayout, rows: List<Row>) {
        stateViews.clear()
        val missing = rows.count { !it.installed }
        val play = rows.count { !it.installed && !it.direct }
        into.addView(caption(ctx, ctx.getString(R.string.store_phone_count, rows.size) + "  ·  " +
            ctx.getString(R.string.store_phone_count_missing, missing, play)))
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
        // The state line: version / update / not installed / needs Play. Tagged
        // with the package so a test reads the rendered verdict, not this source.
        addView(TextView(ctx).apply {
            tag = STATE_TAG_PREFIX + r.pkg; textSize = 11f
            stateViews[r.pkg] = this
            paint(ctx, this, states[r.pkg] ?: SourceResolver.Check.Unknown(null, ""), r)
        })
        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 4), 0, 0)
        }
        r.actions.forEach { a -> buttons.addView(btn(ctx, a) { act(ctx, r, a) }) }
        addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(buttons) })
    }

    /** One [SourceResolver.Check] → the row's state line and colour. */
    private fun paint(ctx: Context, tv: TextView, s: SourceResolver.Check, r: Row) {
        fun via(k: String?) = when (k) {
            SourceResolver.KIND_VENDOR -> ctx.getString(R.string.store_phone_source_vendor)
            SourceResolver.KIND_FDROID -> ctx.getString(R.string.store_phone_source_fdroid)
            SourceResolver.KIND_PLAY -> ctx.getString(R.string.store_phone_source_play)
            SourceResolver.VIA_FLEET -> ctx.getString(R.string.store_phone_source_fleet)
            else -> ""
        }
        val ladder = r.external?.sources?.joinToString(" → ") { via(it.kind) } ?: via(SourceResolver.VIA_FLEET)
        val (text, colour) = when (s) {
            is SourceResolver.Check.NotInstalled ->
                (if (s.needsPlay) ctx.getString(R.string.store_phone_state_needs_play, ctx.getString(R.string.store_phone_badge_needs_play))
                 else ctx.getString(R.string.store_phone_state_not_installed, ladder)) to (if (s.needsPlay) cBadge else cMiss)
            is SourceResolver.Check.Installed -> {
                val note = when (s.note) {
                    SourceResolver.Note.NONE -> if (s.via == null) "" else "  ·  " + via(s.via)
                    SourceResolver.Note.NO_FEED -> "  ·  " + ctx.getString(R.string.store_phone_note_no_feed)
                    SourceResolver.Note.PLAY_MANAGES -> "  ·  " + ctx.getString(R.string.store_phone_note_play)
                    SourceResolver.Note.UNDECLARED -> "  ·  " + ctx.getString(R.string.store_phone_note_undeclared)
                    SourceResolver.Note.NOT_COMPARABLE -> "  ·  " + ctx.getString(R.string.store_phone_note_not_comparable)
                }
                (ctx.getString(R.string.store_phone_state_installed, s.versionName, s.versionCode) + note) to cUp
            }
            is SourceResolver.Check.UpdateAvailable ->
                ctx.getString(R.string.store_phone_state_update, s.versionName, s.remote, via(s.via)) to cUpd
            is SourceResolver.Check.Unknown ->
                ctx.getString(R.string.store_phone_state_unknown, s.versionName ?: "—", s.reason) to cDim
        }
        tv.text = text; tv.setTextColor(colour)
    }

    /** Check all: rebuild the rows, then probe every one off the main thread. */
    private fun checkAll() = reload {
        val ctx = requireContext(); val app = ctx.applicationContext
        val resolver = cfg ?: return@reload
        for (r in rows) {
            stateViews[r.pkg]?.let { tv -> tv.text = ctx.getString(R.string.store_phone_checking); tv.setTextColor(cDim) }
            thread(name = "store-phone-check-${r.pkg}") {
                val s = runCatching {
                    r.fleetApp?.let { SourceResolver.ofFleet(Fleet.status(app, it)) }
                        ?: SourceResolver.check(app, resolver, r.external ?: SourceResolver.resolve(resolver, r.pkg))
                }.getOrElse { SourceResolver.Check.Unknown(null, it.message ?: it.javaClass.simpleName) }
                view?.post { if (isAdded) { states[r.pkg] = s; stateViews[r.pkg]?.let { paint(ctx, it, s, r) } } }
            }
        }
    }

    /** Install all: every declared app not on the phone that this store can serve itself. */
    private fun installAll() {
        val ctx = requireContext()
        val targets = rows.filter { !it.installed && it.direct }
        val play = rows.count { !it.installed && !it.direct }
        if (targets.isEmpty()) { Toast.makeText(ctx, ctx.getString(R.string.store_phone_batch_none), Toast.LENGTH_LONG).show(); return }
        Toast.makeText(ctx, ctx.getString(R.string.store_phone_install_all_start, targets.size, play), Toast.LENGTH_LONG).show()
        batch(ctx, targets)
    }

    /** Update all: every row Check all found an update for. Never runs a probe itself. */
    private fun updateAll() {
        val ctx = requireContext()
        val targets = rows.filter { states[it.pkg] is SourceResolver.Check.UpdateAvailable && it.direct }
        if (targets.isEmpty()) { Toast.makeText(ctx, ctx.getString(R.string.store_phone_update_none), Toast.LENGTH_LONG).show(); return }
        Toast.makeText(ctx, ctx.getString(R.string.store_phone_batch_start, targets.size), Toast.LENGTH_SHORT).show()
        batch(ctx, targets)
    }

    /** Sequential — each install may raise the system confirm sheet. */
    private fun batch(ctx: Context, targets: List<Row>) {
        val app = ctx.applicationContext
        thread(name = "store-phone-batch") {
            var failed = 0
            targets.forEachIndexed { i, r ->
                UpdateProgress.beginBatch(r.label, i + 1, targets.size)
                installOne(app, r)?.let { failed++; toastLater(app, app.getString(R.string.store_phone_failed, r.label, it)) }
            }
            UpdateProgress.endBatch()
            toastLater(app, app.getString(R.string.store_phone_batch_done, targets.size - failed, failed))
            view?.post { if (isAdded) reload() }
        }
    }

    /** The ONE path per kind. Blocking. */
    private fun installOne(app: Context, r: Row): String? {
        val resolver = cfg ?: PhoneAppActions.resolver(PhoneAppActions.sources(app))
        // Not `fleetApp?.let { } ?: external`: a fleet install that SUCCEEDS
        // returns null, and that elvis would have gone on to run the external path.
        val fleetApp = r.fleetApp
        return if (fleetApp != null) FleetInstall.run(app, fleetApp)
               else ExternalInstall.run(app, resolver, r.external ?: SourceResolver.resolve(resolver, r.pkg))
    }

    private fun act(ctx: Context, r: Row, a: PhoneAppActions.Action) {
        a.disabledReason?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); return }
        when (a.kind) {
            PhoneAppActions.Kind.INSTALL, PhoneAppActions.Kind.UPDATE -> {
                Toast.makeText(ctx, ctx.getString(
                    if (a.kind == PhoneAppActions.Kind.INSTALL) R.string.store_phone_installing else R.string.store_phone_updating, r.label),
                    Toast.LENGTH_SHORT).show()
                val app = ctx.applicationContext
                thread(name = "store-phone-install-${r.pkg}") {
                    installOne(app, r)?.let { msg -> toastLater(app, app.getString(R.string.store_phone_failed, r.label, msg)) }
                    view?.post { if (isAdded) reload() }
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

    companion object {
        /** The state line of a row is tagged [STATE_TAG_PREFIX] + package. */
        const val STATE_TAG_PREFIX = "store-phone-state:"
        private const val UNSHELVED = "￿"
        private const val EXPORT_NAME = "cloud-sa-apps.json"
        // A .json picked from Downloads is as often octet-stream as json.
        private val IMPORT_TYPES = arrayOf("application/json", "application/octet-stream", "text/plain")
    }
}
