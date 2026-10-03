package com.diegonmarcos.superapp.profile

import android.content.Context
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.appstore.StoreStages
import com.diegonmarcos.superapp.core.FleetConfig
import com.diegonmarcos.superapp.updater.Fleet
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * #783 The WHOLE fleet in Configs ▸ Account: every fleet app's own configuration, read and applied
 * through the libs:core [FleetConfig] contract (`<package>.fleetconfig`, declared once in
 * libs:core assets/fleet-config.json), filed under the vault section [SECTION]:
 *
 *     settings › <app id> › <store file> › <key>          a value
 *     settings › <app id> › <store file> › _types › <key>  its int/long/float type
 *     settings › <app id> › _schema                        the app's config schema version
 *
 * so the same three files (S server, R runtime, L local) and the same [AccountDrift] compare it
 * field by field — Runtime reads it, Drift diffs it, server → runtime applies it, runtime →
 * declared writes it into L. `_schema` is reported for every app that answers, so an app with
 * nothing to migrate is still on the record as present — which is what [plan] installs from.
 *
 * NEW PHONE ([plan] / [run]): every app the declared copy names is installed through the Store's
 * own stages when it is missing, then given its declared configuration, then the cockpit
 * sections (mail, mesh, drive, ai, about) are pushed as before. Each app's outcome is journaled
 * with the hash of what was applied, so a second run skips what already landed (resumable) and
 * applying the same file twice changes nothing (idempotent).
 */
object AccountFleet {

    const val SECTION = "settings"
    const val SCHEMA = "_schema"
    private val SEP = AccountDrift.SEP

    fun owns(path: String) = path.startsWith(SECTION + SEP)

    // ── paths (pure) ─────────────────────────────────────────────────────

    /** One app's export (wire format) as R paths. */
    fun flatten(appId: String, export: JSONObject): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        val base = SECTION + SEP + appId
        out[base + SEP + SCHEMA] = export.optInt("schema_version", 1)
        val stores = export.optJSONObject("stores") ?: return out
        for (file in stores.keys().asSequence().sorted()) {
            val o = stores.getJSONObject(file)
            for (k in o.keys().asSequence().sorted()) {
                val v = o.get(k)
                if (k == FleetConfig.TYPES && v is JSONObject) v.keys().forEach { t -> out[base + SEP + file + SEP + k + SEP + t] = v.get(t) }
                else out[base + SEP + file + SEP + k] = v
            }
        }
        return out
    }

    /** A declared app subtree (`settings › id`) as the wire format FleetConfig imports. */
    fun body(subtree: JSONObject): JSONObject {
        val stores = JSONObject()
        subtree.keys().forEach { k -> if (k != SCHEMA) subtree.optJSONObject(k)?.let { stores.put(k, it) } }
        return JSONObject().put("schema_version", subtree.optInt(SCHEMA, 1)).put("stores", stores)
    }

    /**
     * SERVER → RUNTIME for settings paths, grouped per app (one import per app, not per field).
     * [server] supplies each value's declared type even when only the value drifted.
     */
    fun bodies(items: List<Pair<String, Any>>, server: JSONObject?): Map<String, JSONObject> {
        val out = LinkedHashMap<String, JSONObject>()
        val decl = server?.optJSONObject(SECTION)
        for ((path, v) in items) {
            val seg = path.split(SEP)
            if (seg.size < 4 || seg[0] != SECTION) continue
            val (id, file) = seg[1] to seg[2]
            val stores = out.getOrPut(id) {
                JSONObject().put("schema_version", decl?.optJSONObject(id)?.optInt(SCHEMA, 1) ?: 1).put("stores", JSONObject())
            }.getJSONObject("stores")
            val store = stores.optJSONObject(file) ?: JSONObject().also { stores.put(file, it) }
            if (seg[3] == FleetConfig.TYPES) continue                     // carried with its value below
            store.put(seg[3], v)
            decl?.optJSONObject(id)?.optJSONObject(file)?.optJSONObject(FleetConfig.TYPES)?.optString(seg[3])?.takeIf { it.isNotEmpty() }?.let { t ->
                (store.optJSONObject(FleetConfig.TYPES) ?: JSONObject().also { store.put(FleetConfig.TYPES, it) }).put(seg[3], t)
            }
        }
        return out
    }

    /**
     * #790 [body] as the Account reads it: plus the settings the cockpit's `agent_auth` [a] derives
     * from it, so the terminals' credentials store is declared wherever the profile declares the
     * tokens (`ai › tokens`), and Drift, server → runtime and the new-phone migration apply it like
     * any other app setting. A value the profile declares at that key itself wins; a `pending` app
     * subtree becomes a declared one ([schema] of the app); a null or blank token adds nothing.
     * Never mutates [body]; returns it as is when there is nothing to add.
     */
    fun derive(body: JSONObject?, a: VaultCockpit.AgentAuth?, schema: (String) -> Int): JSONObject? {
        if (body == null || a == null || a.store.isBlank()) return body
        val values = a.env.mapNotNull { (name, path) ->
            (path.fold(body as Any?) { o, k -> (o as? JSONObject)?.opt(k) } as? String)?.trim()?.takeIf { it.isNotEmpty() }?.let { name to it }
        }
        if (values.isEmpty()) return body
        val out = AccountDrift.copy(body)
        val settings = out.optJSONObject(SECTION) ?: JSONObject().also { out.put(SECTION, it) }
        for (app in a.apps) {
            val sub = settings.optJSONObject(app)?.takeUnless { it.optBoolean("pending") }
                ?: JSONObject().put(SCHEMA, schema(app)).also { settings.put(app, it) }
            val store = sub.optJSONObject(a.store) ?: JSONObject().also { sub.put(a.store, it) }
            for ((k, v) in values) if (!store.has(k)) store.put(k, v)
        }
        return out
    }

    /** True when [path] holds a value the manifest classes `secret` — drawn masked, whatever its name. */
    fun isSecret(m: FleetConfig.Manifest, path: String): Boolean {
        val seg = path.split(SEP)
        if (seg.size < 4 || seg[0] != SECTION || seg[3] == FleetConfig.TYPES) return false
        val app = m.apps[seg[1]] ?: return true          // an app this build does not know: fail closed
        val store = m.storeOfFile(app.pkg, seg[2]) ?: return true
        return m.secret(store, seg[3], app.id)
    }

    // ── the drift's view of the fleet ────────────────────────────────────

    /** One [AccountDrift.App] per fleet app, owning `settings › <id>`; an id the cockpit also
     *  declares (mail, keyboard, drive) is ONE app holding both its cockpit sections and its settings. */
    fun driftApps(cockpit: List<AccountDrift.App>, m: FleetConfig.Manifest, labels: Map<String, String>): List<AccountDrift.App> {
        val out = LinkedHashMap<String, AccountDrift.App>()
        cockpit.forEach { out[it.id] = it }
        m.apps.keys.forEach { id ->
            val mine = SECTION + SEP + id
            out[id] = out[id]?.let { it.copy(sections = it.sections + mine) } ?: AccountDrift.App(id, labels[id] ?: id, listOf(mine))
        }
        return out.values.toList()
    }

    // ── reading (Android) ────────────────────────────────────────────────

    fun manifest(ctx: Context) = FleetConfig.manifest(ctx)

    fun fleetApps(): List<Fleet.App> = Fleet.parse(BuildConfig.CONSTELLATION_FLEET_B64)

    private fun installed(ctx: Context, pkg: String) = runCatching { ctx.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    /** Every fleet app's live configuration through its contract. BLOCKS (provider calls): call on IO. */
    fun reads(ctx: Context): List<AccountRuntime.AppRead> {
        val m = manifest(ctx)
        val labels = fleetApps().associate { it.id to it.label }
        return m.apps.values.map { app ->
            val label = labels[app.id] ?: app.id
            val cov = m.coverage(app)
            val covLine = "coverage ${cov.covered.size}/${cov.total} (${cov.percent}%)"
            if (app.pkg != ctx.packageName && !installed(ctx, app.pkg))
                return@map AccountRuntime.AppRead(app.id, label, AccountRuntime.Status.NOT_INSTALLED, app.pkg, emptyMap())
            // The app's declared fields, as Runtime counts them: one per migrating store file it can hold.
            val fields = cov.covered.mapNotNull { m.stores[it] }.flatMap { s -> s.filesFor(app.pkg).map { SECTION + SEP + app.id + SEP + it } }
            when (val r = FleetConfig.export(ctx, app.pkg)) {
                is FleetConfig.Reply.Ok -> {
                    val values = flatten(app.id, r.json)
                    AccountRuntime.AppRead(app.id, label, AccountRuntime.Status.REACHABLE,
                        "${values.size - 1} values · $covLine", values,
                        fields = fields, unread = fields.filter { f -> values.keys.none { it.startsWith(f + SEP) } })
                }
                is FleetConfig.Reply.Unreachable -> AccountRuntime.AppRead(app.id, label, AccountRuntime.Status.NOT_REPORTING, r.why, emptyMap())
                is FleetConfig.Reply.Refused -> AccountRuntime.AppRead(app.id, label, AccountRuntime.Status.NOT_REPORTING, r.why, emptyMap())
            }
        }
    }

    /** [reads] folded into the cockpit's reads: one entry per id, the best status, both value sets. */
    fun merge(cockpit: List<AccountRuntime.AppRead>, fleet: List<AccountRuntime.AppRead>): List<AccountRuntime.AppRead> {
        val out = LinkedHashMap<String, AccountRuntime.AppRead>()
        cockpit.forEach { out[it.id] = it }
        for (f in fleet) {
            val c = out[f.id]
            out[f.id] = if (c == null) f else c.copy(
                status = if (c.status == AccountRuntime.Status.REACHABLE || f.status == AccountRuntime.Status.REACHABLE) AccountRuntime.Status.REACHABLE else c.status,
                detail = listOf(c.detail, f.detail).filter { it.isNotBlank() }.joinToString(" · "),
                values = c.values + f.values,
                fields = c.fields + f.fields, missing = c.missing + f.missing, unread = c.unread + f.unread,
            )
        }
        return out.values.toList()
    }

    /** Apply settings [items] (path → server value), one import per app. Report lines, ✓ or ✗. BLOCKS. */
    fun push(ctx: Context, items: List<Pair<String, Any>>, server: JSONObject?): List<String> {
        val m = manifest(ctx)
        return bodies(items, server).map { (id, body) ->
            val app = m.apps[id] ?: return@map "✗ $id: not a fleet app this build knows"
            line(id, FleetConfig.import(ctx, app.pkg, body))
        }
    }

    private fun line(id: String, r: FleetConfig.Reply): String = when (r) {
        is FleetConfig.Reply.Ok -> r.json.optString(FleetConfig.KEY_ERROR).takeIf { it.isNotBlank() }?.let { "✗ $id: $it" }
            ?: "✓ $id: ${r.json.optInt("written")} written" + refusedOf(r.json).let { if (it == 0) "" else ", $it refused" }
        is FleetConfig.Reply.Unreachable -> "✗ $id: ${r.why}"
        is FleetConfig.Reply.Refused -> "✗ $id: ${r.why}"
    }

    private fun refusedOf(j: JSONObject): Int {
        val files = j.optJSONObject("files") ?: return 0
        return files.keys().asSequence().sumOf { f -> files.optJSONObject(f)?.let { it.optJSONArray("refused")?.length() ?: (if (it.has(FleetConfig.KEY_ERROR)) 1 else 0) } ?: 0 }
    }

    // ── the new phone ────────────────────────────────────────────────────

    data class Step(val id: String, val label: String, val pkg: String, val installed: Boolean, val values: Int, val action: String, val sha: String)

    const val INSTALL_APPLY = "install, then apply"
    const val APPLY = "apply"
    const val DONE = "done"
    const val NOTHING = "nothing declared"

    /**
     * What a migration of [declared] would do, per fleet app, in manifest order (the SuperApp
     * first): install-then-apply, apply, done (journal holds this exact subtree), or nothing.
     * Pure over its inputs so the JVM suite pins it.
     */
    fun plan(m: FleetConfig.Manifest, declared: JSONObject?, labels: Map<String, String>,
             installed: (String) -> Boolean, journal: (String) -> String?): List<Step> {
        val settings = declared?.optJSONObject(SECTION)
        return m.apps.values.map { app ->
            // A `pending` app (the vault declares it, no values folded in yet) is not installed on a guess.
            val sub = settings?.optJSONObject(app.id)?.takeUnless { it.optBoolean("pending") }
            val sha = sub?.let { sha(it) }.orEmpty()
            val values = sub?.let { AccountDrift.leaves(JSONObject().put("x", it)).size - (if (it.has(SCHEMA)) 1 else 0) } ?: 0
            val inst = installed(app.pkg)
            val action = when {
                sub == null -> NOTHING
                journal(app.id)?.startsWith("$sha|✓") == true && inst -> DONE
                !inst -> INSTALL_APPLY
                else -> APPLY
            }
            Step(app.id, labels[app.id] ?: app.id, app.pkg, inst, values, action, sha)
        }
    }

    fun sha(o: JSONObject): String =
        MessageDigest.getInstance("SHA-256").digest(AccountDrift.canonical(o).toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    private const val JOURNAL = "account_migrate"
    private fun journal(ctx: Context) = ctx.getSharedPreferences(JOURNAL, Context.MODE_PRIVATE)

    /** The plan for this phone against [declared]. */
    fun plan(ctx: Context, declared: JSONObject?): List<Step> {
        val j = journal(ctx)
        return plan(manifest(ctx), declared, fleetApps().associate { it.id to it.label },
            { it == ctx.packageName || installed(ctx, it) }, { j.getString(it, null) })
    }

    fun planJson(steps: List<Step>): JSONArray = JSONArray(steps.map { s ->
        JSONObject().put("id", s.id).put("label", s.label).put("installed", s.installed).put("values", s.values).put("action", s.action)
    })

    @Volatile var progress: JSONObject = JSONObject().put("state", "idle"); private set

    /**
     * Run the migration: every step of [plan] in order, each app's outcome journaled. [after] runs
     * once every app is done (the cockpit push). Re-running resumes: done steps are skipped.
     * BLOCKS (installs, provider calls): call on IO.
     */
    fun run(ctx: Context, declared: JSONObject?, after: () -> String): JSONObject {
        val steps = plan(ctx, declared)
        val fleet = fleetApps().associateBy { it.id }
        val settings = declared?.optJSONObject(SECTION)
        val results = JSONArray()
        progress = JSONObject().put("state", "running").put("total", steps.size).put("results", results)
        for ((i, s) in steps.withIndex()) {
            progress.put("at", i + 1).put("app", s.id)
            val outcome = when (s.action) {
                NOTHING, DONE -> s.action
                else -> {
                    var ok = s.installed
                    var installNote = ""
                    if (!ok) {
                        val app = fleet[s.id]
                        val st = app?.let { runCatching { StoreStages.auto(ctx, it) }.getOrNull() }
                        ok = installed(ctx, s.pkg)
                        installNote = if (ok) "installed · " else "✗ install: ${st?.text ?: "not in the Store roster"}"
                    }
                    if (!ok) installNote
                    else installNote + line(s.id, FleetConfig.import(ctx, s.pkg, body(settings!!.getJSONObject(s.id)))).also { l ->
                        journal(ctx).edit().putString(s.id, "${s.sha}|$l").apply()
                    }
                }
            }
            results.put(JSONObject().put("id", s.id).put("action", s.action).put("result", outcome))
        }
        val cockpit = runCatching { after() }.getOrElse { "✗ ${it.message}" }
        progress = JSONObject().put("state", "done").put("total", steps.size).put("results", results).put("cockpit", cockpit)
        return progress
    }
}
