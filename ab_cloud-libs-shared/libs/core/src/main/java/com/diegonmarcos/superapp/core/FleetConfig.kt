package com.diegonmarcos.superapp.core

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * #783 THE fleet configuration contract: every fleet app exports and imports its own
 * configuration through ONE call, so a brand-new phone can be made to match the old one.
 *
 *     FleetConfig.export(ctx, "com.diegonmarcos.cloudcalc")          // → {"stores": {...}} or null
 *     FleetConfig.import(ctx, "com.diegonmarcos.cloudcalc", body)    // → per-store result
 *
 * WHAT moves is declared once, in this lib's assets/fleet-config.json (the manifest): every
 * store an app or lib opens, with its class. Only `config` and `secret` classes migrate; a
 * `device` store (install ids, caches, Keystore-bound blobs) or a `content` store (data that
 * lives on a server) never leaves the phone, and a key-level override can narrow one key of a
 * migrating store the same way. fleet-config-guard.yml fails the build when the code opens a
 * store the manifest does not declare — so nothing here is ever a list of app settings.
 *
 * HOW: there is no per-app code. Every fleet app links libs:core, and libs:core's manifest
 * merges [FleetConfigProvider] into each of them at `<package>.fleetconfig`. The provider runs
 * IN the owning app's process, so it reads and writes that app's own SharedPreferences (and its
 * EncryptedSharedPreferences, through the same default MasterKey) — the files no other app can
 * open. A provider call starts a stopped app, so this works with the app closed.
 *
 * WHO may call: the provider is exported behind CONSTELLATION_DATA, the fleet's signature
 * permission; the system refuses to hand it to an APK signed with any other key, and the
 * provider re-checks the caller itself because `call()` is not permission-checked on its own.
 *
 * WIRE FORMAT (one app):
 *     {"contract": 1, "app": "calc", "schema_version": 1,
 *      "stores": {"<file>": {"<key>": <value>, …, "_types": {"<key>": "i|l|f"}}}}
 * A value is its natural JSON (string, boolean, number, array = string set); `_types` keeps
 * the int/long/float distinction a JSON number loses, so an import never makes `getInt` throw
 * on a value written as a long. Store keys are the on-disk file names.
 *
 * test: aa_cloud-superapp FleetConfigTest (export → clean profile → import, field by field).
 */
object FleetConfig {

    private const val TAG = "FleetConfig"

    const val CONTRACT = 1
    const val ASSET = "fleet-config.json"
    const val AUTHORITY_SUFFIX = ".fleetconfig"
    const val PERMISSION = "com.diegonmarcos.cloud.permission.CONSTELLATION_DATA"

    const val METHOD_EXPORT = "export"
    const val METHOD_IMPORT = "import"
    const val KEY_JSON = "json"
    const val KEY_ERROR = "error"
    /** Import extra: the app restarts once the reply is out, so no cached copy of the old
     *  values outlives the import (the next launch reads what was written). */
    const val KEY_RESTART = "restart"

    const val TYPES = "_types"

    fun authority(pkg: String) = pkg + AUTHORITY_SUFFIX

    // ── the declaration ──────────────────────────────────────────────────

    data class Store(
        val name: String, val kind: String, val cls: String, val doc: String,
        val keys: Map<String, String>, val classByApp: Map<String, String>, val files: List<String>,
        val usedBy: List<String> = emptyList(),
    ) {
        /** The on-disk file names in [pkg] (`{pkg}` expanded). */
        fun filesFor(pkg: String): List<String> = files.map { it.replace("{pkg}", pkg) }
    }

    data class Coverage(val app: String, val covered: List<String>, val gaps: List<String>) {
        val total: Int get() = covered.size + gaps.size
        /** Percent of declared migrating items the contract moves; an app with none is fully covered. */
        val percent: Int get() = if (total == 0) 100 else covered.size * 100 / total
        fun json(): JSONObject = JSONObject().put("covered", JSONArray(covered)).put("gaps", JSONArray(gaps))
            .put("total", total).put("percent", percent)
    }

    data class App(val id: String, val pkg: String, val module: String, val schema: Int, val libs: List<String>, val items: List<JSONObject>)

    class Manifest(val json: JSONObject) {
        val migrate: Set<String> = json.optJSONArray("migrate").strings().toSet()
        /** Kinds this contract can read and write without the app's help. */
        val portable = setOf("prefs", "encrypted")

        val stores: Map<String, Store> = json.optJSONObject("stores").let { o ->
            o?.keys()?.asSequence()?.associateWith { n ->
                val s = o.getJSONObject(n)
                Store(n, s.optString("kind"), s.optString("class"), s.optString("doc"),
                    s.optJSONObject("keys").stringMap(), s.optJSONObject("class_by_app").stringMap(),
                    s.optJSONArray("files")?.strings() ?: listOf(if (n == "<default>") "{pkg}_preferences" else n),
                    s.optJSONArray("used_by").strings())
            }.orEmpty()
        }

        val apps: Map<String, App> = json.optJSONObject("apps").let { o ->
            o?.keys()?.asSequence()?.associateWith { id ->
                val a = o.getJSONObject(id)
                App(id, a.optString("package"), a.optString("module"), a.optInt("schema_version", 1),
                    a.optJSONArray("libs").strings(),
                    a.optJSONArray("items")?.let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } }.orEmpty())
            }.orEmpty()
        }

        fun appByPackage(pkg: String): App? = apps.values.firstOrNull { it.pkg == pkg }

        fun classOf(s: Store, appId: String): String = s.classByApp[appId] ?: s.cls

        /** A key's class: the store's, unless an override (exact, or a `*` glob) narrows it. */
        fun keyClass(s: Store, key: String, appId: String): String {
            val own = classOf(s, appId)
            s.keys[key]?.let { return it }
            s.keys.entries.firstOrNull { (pat, _) -> '*' in pat && glob(pat).matches(key) }?.let { return it.value }
            return own
        }

        fun migrates(s: Store, appId: String) = s.kind in portable && classOf(s, appId) in migrate
        fun migratesKey(s: Store, key: String, appId: String) = migrates(s, appId) && keyClass(s, key, appId) in migrate
        fun secret(s: Store, key: String, appId: String) = keyClass(s, key, appId) == "secret"

        /** Library items (files a lib keeps), by lib module id. */
        val libItems: Map<String, List<JSONObject>> = json.optJSONObject("libs").let { o ->
            o?.keys()?.asSequence()?.associateWith { l ->
                o.getJSONObject(l).optJSONArray("items")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
            }.orEmpty()
        }

        /**
         * How much of [app]'s migrating configuration this contract moves on its own: every
         * config/secret store its code or its libs open, plus every declared config/secret file.
         * A prefs/encrypted store is covered; a DataStore, a Room table or a file is a named gap.
         */
        fun coverage(app: App): Coverage {
            val mods = app.libs.toSet() + app.module
            val mine = stores.values.filter { s -> s.usedBy.any { it in mods } && classOf(s, app.id) in migrate }.sortedBy { it.name }
            // An item is moved when a portable store holds it, when installing the same build
            // reproduces it (`via: install`), or when Connect re-fetches it (`via: connect`).
            val (moved, notMoved) = (app.items + app.libs.flatMap { libItems[it].orEmpty() })
                .filter { it.optString("class") in migrate }
                .partition { it.optString("via") in setOf("install", "connect") || stores[it.optString("store")]?.kind in portable }
            fun label(i: JSONObject) = "file:" + i.optString("path").ifBlank { i.optString("id") }
            return Coverage(app.id,
                covered = mine.filter { it.kind in portable }.map { it.name } + moved.map(::label),
                gaps = mine.filter { it.kind !in portable }.map { it.name } + notMoved.map(::label))
        }

        /** The declared store a file in [pkg] belongs to (a store may own several files). */
        fun storeOfFile(pkg: String, file: String): Store? = stores.values.firstOrNull { file in it.filesFor(pkg) }
    }

    @Volatile private var cached: Manifest? = null

    /** The manifest baked into this APK (libs:core assets). */
    fun manifest(ctx: Context): Manifest = cached ?: synchronized(this) {
        cached ?: Manifest(JSONObject(ctx.applicationContext.assets.open(ASSET).bufferedReader().use { it.readText() }))
            .also { cached = it }
    }

    // ── the engine (pure over SharedPreferences: the JVM suite runs it on two profiles) ──

    /** Opens one store file; null = the file does not exist (export) — import passes create=true. */
    fun interface Opener { fun open(store: Store, file: String, create: Boolean): SharedPreferences? }

    /** Every migrating value [appId] holds, in the wire format. Device/content keys never leave. */
    fun exportApp(m: Manifest, app: App, opener: Opener): JSONObject {
        val stores = JSONObject()
        for (s in m.stores.values.sortedBy { it.name }) {
            if (!m.migrates(s, app.id)) continue
            for (file in s.filesFor(app.pkg)) {
                val prefs = runCatching { opener.open(s, file, false) }
                    .onFailure { Log.w(TAG, "export ${app.id}/$file unreadable: ${it.javaClass.simpleName}") }
                    .getOrNull() ?: continue
                val out = JSONObject(); val types = JSONObject()
                for ((k, v) in prefs.all.toSortedMap()) {
                    if (v == null || !m.migratesKey(s, k, app.id)) continue
                    when (v) {
                        is Int -> { out.put(k, v); types.put(k, "i") }
                        is Long -> { out.put(k, v); types.put(k, "l") }
                        is Float -> { out.put(k, v.toDouble()); types.put(k, "f") }
                        is Set<*> -> out.put(k, JSONArray(v.map { it.toString() }.sorted()))
                        else -> out.put(k, v)   // String, Boolean
                    }
                }
                if (out.length() == 0) continue
                if (types.length() > 0) out.put(TYPES, types)
                stores.put(file, out)
            }
        }
        return JSONObject().put("contract", CONTRACT).put("app", app.id).put("schema_version", app.schema).put("stores", stores)
    }

    /**
     * Writes [body] (the wire format) into [app]'s stores and says, per file, what it wrote and
     * what it refused. Refuses — never guesses — a newer schema, an undeclared file, a store that
     * does not migrate, and any key the manifest keeps on the device. Keys the phone holds and
     * [body] does not are left alone. Idempotent: the same body twice writes the same values.
     */
    fun importApp(m: Manifest, app: App, body: JSONObject, opener: Opener): JSONObject {
        val result = JSONObject().put("app", app.id)
        val incoming = body.optInt("schema_version", app.schema)
        if (incoming > app.schema)
            return result.put(KEY_ERROR, "the declared copy is schema $incoming, this app understands ${app.schema} — update ${app.id} first")
        val files = JSONObject()
        var written = 0
        body.optJSONObject("stores")?.let { stores ->
            for (file in stores.keys().asSequence().sorted()) {
                val r = JSONObject(); files.put(file, r)
                val s = m.storeOfFile(app.pkg, file) ?: run { r.put(KEY_ERROR, "not a declared store"); null } ?: continue
                if (!m.migrates(s, app.id)) { r.put(KEY_ERROR, "${m.classOf(s, app.id)} store: never migrates"); continue }
                val values = stores.getJSONObject(file)
                val types = values.optJSONObject(TYPES) ?: JSONObject()
                val prefs = opener.open(s, file, true) ?: run { r.put(KEY_ERROR, "cannot open"); null } ?: continue
                val existing = prefs.all
                val ed = prefs.edit()
                val wrote = JSONArray(); val refused = JSONArray()
                for (k in values.keys().asSequence().filter { it != TYPES }.sorted()) {
                    if (!m.migratesKey(s, k, app.id)) { refused.put(k); continue }
                    if (!put(ed, k, values.get(k), types.optString(k), existing[k])) { refused.put(k); continue }
                    wrote.put(k)
                }
                if (!ed.commit()) { r.put(KEY_ERROR, "commit failed"); continue }
                written += wrote.length()
                r.put("written", wrote).put("refused", refused)
            }
        }
        return result.put("files", files).put("written", written)
    }

    /** One value with its declared type, else the type the key already has, else its JSON type. */
    private fun put(ed: SharedPreferences.Editor, k: String, v: Any?, type: String, existing: Any?): Boolean {
        val t = type.ifEmpty {
            when (existing) { is Int -> "i"; is Long -> "l"; is Float -> "f"; else -> "" }
        }
        when {
            v == null || v == JSONObject.NULL -> ed.remove(k)
            v is JSONArray -> ed.putStringSet(k, (0 until v.length()).map { v.optString(it) }.toSet())
            v is Boolean -> ed.putBoolean(k, v)
            v is Number -> when (t) {
                "i" -> ed.putInt(k, v.toInt())
                "f" -> ed.putFloat(k, v.toFloat())
                "l" -> ed.putLong(k, v.toLong())
                else -> if (v.toDouble() % 1.0 != 0.0) ed.putFloat(k, v.toFloat())
                        else if (v.toLong() in Int.MIN_VALUE..Int.MAX_VALUE) ed.putInt(k, v.toInt()) else ed.putLong(k, v.toLong())
            }
            v is String -> ed.putString(k, v)
            else -> return false
        }
        return true
    }

    // ── on the phone ─────────────────────────────────────────────────────

    /** The real opener: a plain file directly, an encrypted one through its cipher. An export
     *  never creates a file: an absent plain store reads as empty, an absent encrypted one is not
     *  opened at all (opening one writes its keyset). */
    fun deviceOpener(ctx: Context): Opener = Opener { s, file, create ->
        val app = ctx.applicationContext
        when (s.kind) {
            "prefs" -> app.getSharedPreferences(file, Context.MODE_PRIVATE).takeIf { create || it.all.isNotEmpty() }
            "encrypted" -> if (!create && !File(app.dataDir, "shared_prefs/$file.xml").isFile) null else androidx.security.crypto.EncryptedSharedPreferences.create(
                app, file,
                androidx.security.crypto.MasterKey.Builder(app).setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM).build(),
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            else -> null
        }
    }

    /** This app's own export (what the provider answers; the SuperApp reads itself with it). */
    fun exportSelf(ctx: Context): JSONObject? {
        val m = manifest(ctx)
        val app = m.appByPackage(ctx.packageName) ?: return null
        return exportApp(m, app, deviceOpener(ctx))
    }

    fun importSelf(ctx: Context, body: JSONObject): JSONObject {
        val m = manifest(ctx)
        val app = m.appByPackage(ctx.packageName)
            ?: return JSONObject().put(KEY_ERROR, "${ctx.packageName} is not a declared fleet app")
        return importApp(m, app, body, deviceOpener(ctx))
    }

    // ── the client: one fleet app asks another ───────────────────────────

    sealed class Reply {
        data class Ok(val json: JSONObject) : Reply()
        /** Not installed, not visible, or an older build without the contract. */
        data class Unreachable(val why: String) : Reply()
        data class Refused(val why: String) : Reply()
    }

    /** [pkg]'s configuration. Starts the app if it is stopped. BLOCKS: call off the main thread. */
    fun export(ctx: Context, pkg: String): Reply =
        if (pkg == ctx.packageName) exportSelf(ctx)?.let { Reply.Ok(it) } ?: Reply.Refused("not a declared fleet app")
        else call(ctx, pkg, METHOD_EXPORT, Bundle())

    /** Applies [body] to [pkg]; [restart] lets the app restart so nothing cached outlives it. */
    fun import(ctx: Context, pkg: String, body: JSONObject, restart: Boolean = true): Reply =
        if (pkg == ctx.packageName) importSelf(ctx, body).let { if (it.has(KEY_ERROR) && !it.has("files")) Reply.Refused(it.getString(KEY_ERROR)) else Reply.Ok(it) }
        else call(ctx, pkg, METHOD_IMPORT, Bundle().apply { putString(KEY_JSON, body.toString()); putBoolean(KEY_RESTART, restart) })

    private fun call(ctx: Context, pkg: String, method: String, extras: Bundle): Reply = try {
        val client = ctx.contentResolver.acquireUnstableContentProviderClient(authority(pkg))
            ?: return Reply.Unreachable("no ${authority(pkg)} — not installed, or a build older than the contract")
        try {
            val b = client.call(method, null, extras) ?: return Reply.Unreachable("$method returned nothing")
            b.getString(KEY_ERROR)?.let { return Reply.Refused(it) }
            Reply.Ok(JSONObject(b.getString(KEY_JSON) ?: return Reply.Refused("no body")))
        } finally {
            client.close()
        }
    } catch (t: Throwable) {
        Log.w(TAG, "$method $pkg: ${t.javaClass.simpleName}")
        Reply.Unreachable("${t.javaClass.simpleName}: ${t.message.orEmpty().take(120)}")
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun JSONArray?.strings(): List<String> = this?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
    private fun JSONObject?.stringMap(): Map<String, String> = this?.let { o -> o.keys().asSequence().associateWith { o.optString(it) } }.orEmpty()
    fun glob(p: String) = Regex(p.split('*').joinToString(".*") { Regex.escape(it) })
}
