package com.diegonmarcos.superapp.profile

import com.diegonmarcos.superapp.fleetconfig.FleetPolicy
import org.json.JSONObject

/**
 * #873 The fleet-wide SETUP PLAN: what Cloud Account holds (the imported configs blob and the declared copy's
 * `settings` subtree) mapped to (app, store, file, key), from the DECLARATIONS - never from a hand list in code:
 *
 *   - `settings > <app id> > <file> > <key>` names its destination itself; the manifest resolves the file to its
 *     declared store ([FleetPolicy.Manifest.storeOfFile]) and the key's class decides whether it may move.
 *   - a bundle path (`mail.password`, `auth.authelia_token`, `wg.wg0_private_key`) is routed by fleet-config.json's
 *     `bundle` section ({"<path>": [{"store","key"}]}); every app that declares that store (its module or one of
 *     its libs `used_by` it) receives the value, so a new app that declares `mail_jmap_prefs` is set up without
 *     touching this file.
 *
 * Anything it cannot place is reported in [Plan.unmapped] with why; nothing is dropped silently.
 * Pure over org.json: the JVM suite pins it. Values are never logged here or rendered by it.
 */
object SetupPlan {

    data class Item(val app: String, val pkg: String, val store: String, val file: String, val key: String,
                    val value: Any, val type: String?, val source: String)

    data class Unmapped(val source: String, val why: String)

    class AppPlan(val app: FleetPolicy.App, val items: List<Item>) {
        /** One apply call per (store, file), in declaration order. */
        fun groups(): Map<Pair<String, String>, List<Item>> = items.groupBy { it.store to it.file }
    }

    class Plan(val apps: List<AppPlan>, val unmapped: List<Unmapped>) {
        val itemCount: Int get() = apps.sumOf { it.items.size }
        fun of(appId: String) = apps.firstOrNull { it.app.id == appId }
    }

    const val BUNDLE = "bundle"

    /** One declared destination of a bundle path. [pull] names the app Cloud Account reads the value FROM when it holds none yet (#874 migration). */
    data class Route(val store: String, val key: String, val pull: String? = null)

    /** The declared bundle routes: path -> destinations. */
    fun routes(m: FleetPolicy.Manifest): Map<String, List<Route>> {
        val o = m.json.optJSONObject(BUNDLE) ?: return emptyMap()
        return o.keys().asSequence().filter { !it.startsWith("_") }.associateWith { path ->
            val a = o.getJSONArray(path)
            (0 until a.length()).map { a.getJSONObject(it).let { t -> Route(t.getString("store"), t.getString("key"), t.optString("pull").takeIf { p -> p.isNotEmpty() }) } }
        }
    }

    /**
     * [configs] the Connections section, [declared] the declared copy (`settings > app > file > key`), [appConfigs] the
     * Configs section (`app > store > file > key`, what Account captured from the apps and the bundle carries).
     */
    fun build(m: FleetPolicy.Manifest, configs: JSONObject?, declared: JSONObject?, appConfigs: JSONObject? = null): Plan {
        val items = LinkedHashMap<String, MutableList<Item>>()
        val unmapped = ArrayList<Unmapped>()
        fun add(i: Item) { items.getOrPut(i.app) { ArrayList() }.let { l -> if (l.none { it.store == i.store && it.file == i.file && it.key == i.key }) l += i } }

        // 1 -- the declared copy: settings > app > file > key
        val settings = declared?.optJSONObject(AccountFleet.SECTION)
        for (id in settings?.keys()?.asSequence()?.sorted().orEmpty()) {
            val sub = settings!!.optJSONObject(id) ?: continue
            if (sub.optBoolean("pending")) continue
            val app = m.apps[id] ?: run { unmapped += Unmapped("settings.$id", "not a fleet app this build knows"); null } ?: continue
            for (file in sub.keys().asSequence().filter { it != AccountFleet.SCHEMA }.sorted()) {
                val values = sub.optJSONObject(file) ?: continue
                val store = m.storeOfFile(app.pkg, file) ?: run { unmapped += Unmapped("settings.$id.$file", "not a declared store file"); null } ?: continue
                val types = values.optJSONObject(FleetPolicy.TYPES)
                for (k in values.keys().asSequence().filter { it != FleetPolicy.TYPES }.sorted()) {
                    if (m.keyClass(store, k, app.id) !in m.migrate) { unmapped += Unmapped("settings.$id.$file.$k", "${m.keyClass(store, k, app.id)} key: never migrates"); continue }
                    add(Item(app.id, app.pkg, store.name, file, k, values.get(k), types?.optString(k)?.takeIf { it.isNotEmpty() }, "settings.$id.$file.$k"))
                }
            }
        }

        // 2 -- the Configs section: app > store > file > key, validated against the same declarations
        for (id in appConfigs?.keys()?.asSequence()?.sorted().orEmpty()) {
            val app = m.apps[id] ?: run { unmapped += Unmapped("configs.$id", "not a fleet app this build knows"); null } ?: continue
            val stores = appConfigs!!.optJSONObject(id) ?: continue
            for (storeName in stores.keys().asSequence().sorted()) {
                val store = m.stores[storeName] ?: run { unmapped += Unmapped("configs.$id.$storeName", "not a declared store"); null } ?: continue
                val files = stores.optJSONObject(storeName) ?: continue
                for (file in files.keys().asSequence().sorted()) {
                    val values = files.optJSONObject(file) ?: continue
                    val types = values.optJSONObject(FleetPolicy.TYPES)
                    for (k in values.keys().asSequence().filter { it != FleetPolicy.TYPES }.sorted()) {
                        if (m.keyClass(store, k, app.id) !in m.migrate) continue            // a device key stays home, silently: it was never meant to travel
                        add(Item(app.id, app.pkg, store.name, file, k, values.get(k), types?.optString(k)?.takeIf { it.isNotEmpty() }, "configs.$id.$storeName.$file.$k"))
                    }
                }
            }
        }

        // 3 -- the Connections: bundle path -> declared store keys
        val routed = HashSet<String>()
        for ((path, targets) in routes(m)) {
            val v = configs?.let { lookup(it, path) } ?: continue
            routed += path
            for ((storeName, key) in targets.map { it.store to it.key }) {
                val store = m.stores[storeName] ?: run { unmapped += Unmapped(path, "routes to $storeName, which fleet-config.json does not declare"); null } ?: continue
                val owners = m.apps.values.filter { a -> store.usedBy.any { it in a.libs || it == a.module } }
                if (owners.isEmpty()) { unmapped += Unmapped(path, "no app declares $storeName"); continue }
                for (app in owners) add(Item(app.id, app.pkg, store.name, store.filesFor(app.pkg).first(), key, v, null, path))
            }
        }
        for (path in leaves(configs)) if (path !in routed && routes(m)[path] == null) unmapped += Unmapped(path, "no declared store takes it")

        val order = m.apps.keys.toList()
        return Plan(items.entries.sortedBy { order.indexOf(it.key) }.mapNotNull { (id, l) -> m.apps[id]?.let { AppPlan(it, l) } }, unmapped)
    }

    /** `a.b.c` in a nested JSON object, typed as stored; null when absent, blank or not a scalar. */
    fun lookup(o: JSONObject, path: String): Any? {
        var cur: Any? = o
        for (seg in path.split('.')) cur = (cur as? JSONObject)?.opt(seg) ?: return null
        return when (cur) { is String -> cur.takeIf { it.isNotBlank() }; is Number, is Boolean -> cur; else -> null }
    }

    /** Scalar leaf paths of [o], skipping `_doc*` prose. */
    fun leaves(o: JSONObject?, prefix: String = ""): List<String> {
        o ?: return emptyList()
        return o.keys().asSequence().filter { !it.startsWith("_") }.sorted().flatMap { k ->
            val v = o.opt(k); val p = if (prefix.isEmpty()) k else "$prefix.$k"
            if (v is JSONObject) leaves(v, p).asSequence() else if (v is String && v.isBlank()) emptySequence() else sequenceOf(p)
        }.toList()
    }
}
