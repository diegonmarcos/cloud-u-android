package com.diegonmarcos.superapp.profile

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * #778 Configs ▸ Account ▸ DRIFT — the rules between the three files. Pure: the JVM suite runs
 * every rule here on golden fixtures, and the tab, the debug API and the sync actions call only this.
 *
 * ONE PATTERN, THREE FILES. Each is a body in the vault bundle's own shape
 * (`section › key › …`, cloud-vault C_A1-configs/profile-secrets.json):
 *  • S — the declared SERVER file, exactly as Connect last fetched it;
 *  • R — the RUNTIME snapshot, every value an app reported, written at the path the vault keeps it;
 *  • L — the LOCAL declared copy, edited on this phone and not yet uploaded.
 *
 * A FIELD IS A LEAF: a string, number or boolean, a whole list (a repo list, a clipboard list) or a
 * vault `pending` marker. An empty value is no value. Bookkeeping (`_generated`, `schema_version`)
 * is never a field. Two values are equal when their canonical JSON is ([canonical]: keys sorted),
 * so a re-ordered object is not drift.
 *
 * R IS PARTIAL BY NATURE: an app reports only what it can read. A path R did not OBSERVE is never
 * drift against it — "the keyboard cannot say" is not "the keyboard disagrees". A path R observed
 * with no value is: the app holds nothing where the vault declares something.
 */
object AccountDrift {

    const val SEP = InfoMask.SEP

    // ── leaves ───────────────────────────────────────────────────────────

    /** Every field of [body]: full path → its raw JSON value (String, Number, Boolean, JSONArray or a pending JSONObject). */
    fun leaves(body: JSONObject?): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        body?.keys()?.forEach { k -> if (!bookkeeping(k)) walk(body.opt(k), k, out) }
        return out
    }

    private fun bookkeeping(key: String) = key.startsWith("_") || key == "schema_version"

    private fun walk(v: Any?, path: String, out: MutableMap<String, Any>) {
        when {
            v == null || v == JSONObject.NULL -> Unit
            v is String && v.isBlank() -> Unit
            v is JSONObject && v.optBoolean("pending") -> out[path] = v
            v is JSONObject -> v.keys().forEach { k -> walk(v.opt(k), path + SEP + k, out) }
            v is JSONArray -> if (v.length() > 0) out[path] = v
            else -> out[path] = v
        }
    }

    /** The comparable text of a value: JSON with sorted keys, so order is never drift. */
    fun canonical(v: Any?): String = when (v) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> v.keys().asSequence().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(v.opt(it)) }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canonical(v.opt(it)) }
        is String -> JSONObject.quote(v)
        else -> v.toString()
    }

    /** The human text of a value: a string as itself, anything else as its canonical JSON. */
    fun text(v: Any?): String = if (v is String) v else canonical(v)

    /** sha256 of [body]'s canonical JSON — a file's identity in its metadata, independent of key order. */
    fun sha256(body: JSONObject): String =
        MessageDigest.getInstance("SHA-256").digest(canonical(body).toByteArray()).joinToString("") { "%02x".format(it) }

    // ── apps ─────────────────────────────────────────────────────────────

    /** One fleet app as the drift sees it: its id and the vault sections it consumes (the cockpit declaration). */
    data class App(val id: String, val label: String, val sections: List<String>)

    /** The app a path belongs to: the first declared app consuming its section; "" for a section no app consumes. */
    fun ownerOf(path: String, apps: List<App>): String {
        val section = path.substringBefore(SEP)
        return apps.firstOrNull { section in it.sections }?.id.orEmpty()
    }

    // ── two files ────────────────────────────────────────────────────────

    enum class Kind { SAME, CHANGED, ONLY_A, ONLY_B }

    /** One field of a comparison. [a] / [b] are the values' [text], null where that side has none. */
    data class Field(val path: String, val app: String, val kind: Kind, val a: String?, val b: String?)

    /**
     * Field by field, [a] against [b], in path order. [scope] non-null limits the comparison to those
     * paths — what R observed — so an unobservable field is never reported; null compares every field
     * either side holds.
     */
    fun diff(a: Map<String, Any>, b: Map<String, Any>, apps: List<App>, scope: Set<String>? = null): List<Field> {
        val paths = (scope ?: (a.keys + b.keys)).toSortedSet()
        return paths.map { p ->
            val x = a[p]; val y = b[p]
            val kind = when {
                x == null && y == null -> Kind.SAME
                x == null -> Kind.ONLY_B
                y == null -> Kind.ONLY_A
                canonical(x) == canonical(y) -> Kind.SAME
                else -> Kind.CHANGED
            }
            Field(p, ownerOf(p, apps), kind, x?.let { text(it) }, y?.let { text(it) })
        }
    }

    data class Counts(val same: Int, val changed: Int, val onlyA: Int, val onlyB: Int) {
        val drift: Int get() = changed + onlyA + onlyB
        fun json(): JSONObject = JSONObject().put("same", same).put("changed", changed)
            .put("only_a", onlyA).put("only_b", onlyB).put("drift", drift)
    }

    fun counts(fields: List<Field>) = Counts(
        fields.count { it.kind == Kind.SAME }, fields.count { it.kind == Kind.CHANGED },
        fields.count { it.kind == Kind.ONLY_A }, fields.count { it.kind == Kind.ONLY_B },
    )

    /** [counts] per app, in declared app order, then "" (fields no app consumes) when there are any. */
    fun byApp(fields: List<Field>, apps: List<App>): Map<String, Counts> {
        val out = LinkedHashMap<String, Counts>()
        (apps.map { it.id } + "").forEach { id ->
            val mine = fields.filter { it.app == id }
            if (mine.isNotEmpty() || id.isNotEmpty()) out[id] = counts(mine)
        }
        return out
    }

    // ── three files ──────────────────────────────────────────────────────

    /**
     * Where one field stands across all three files:
     *  IN_SYNC — L and (when observed) R equal S;
     *  LOCAL_EDIT — L moved off S, R still equals S (or cannot say);
     *  RUNTIME_DRIFT — R moved off S, L did not;
     *  AGREED — L and R moved off S to the same value (upload L and the server catches up);
     *  CONFLICT — all three differ: no direction is safe without a person choosing.
     */
    enum class Three { IN_SYNC, LOCAL_EDIT, RUNTIME_DRIFT, AGREED, CONFLICT }

    data class ThreeWay(val path: String, val app: String, val state: Three, val observed: Boolean)

    fun threeWay(s: Map<String, Any>, r: Map<String, Any>, l: Map<String, Any>, observed: Set<String>, apps: List<App>): List<ThreeWay> =
        (s.keys + l.keys + observed).toSortedSet().map { p ->
            val sv = s[p]?.let { canonical(it) }; val lv = l[p]?.let { canonical(it) }
            val seen = p in observed
            val rv = r[p]?.let { canonical(it) }
            val state = when {
                !seen -> if (lv == sv) Three.IN_SYNC else Three.LOCAL_EDIT
                lv == sv && rv == sv -> Three.IN_SYNC
                lv != sv && rv == sv -> Three.LOCAL_EDIT
                lv == sv -> Three.RUNTIME_DRIFT
                rv == lv -> Three.AGREED
                else -> Three.CONFLICT
            }
            ThreeWay(p, ownerOf(p, apps), state, seen)
        }

    // ── writes ───────────────────────────────────────────────────────────

    fun copy(o: JSONObject?): JSONObject = if (o == null) JSONObject() else JSONObject(o.toString())

    /** [body] with [value] at [path], creating the objects on the way; a non-object in the way is replaced. */
    fun put(body: JSONObject, path: String, value: Any): JSONObject {
        val keys = path.split(SEP)
        var node = body
        for (k in keys.dropLast(1)) {
            node = node.optJSONObject(k) ?: JSONObject().also { node.put(k, it) }
        }
        node.put(keys.last(), value)
        return body
    }

    /** Why a runtime value did not reach L. */
    enum class Skip { NOT_OBSERVED, HOLDS_NONE, READ_ONLY }

    data class Written(val body: JSONObject, val written: List<String>, val skipped: Map<String, Skip>)

    /**
     * RUNTIME → DECLARED: a copy of [l] with R's value at each of [paths]. A path R did not observe,
     * one it observed holding nothing, and one whose app cannot be written back ([readOnly]: a running
     * tunnel is not a wg-quick declaration) are skipped and named — a runtime that holds nothing never
     * erases a declared value.
     */
    fun runtimeToDeclared(l: JSONObject?, r: Map<String, Any>, observed: Set<String>, readOnly: Set<String>, paths: Collection<String>): Written {
        val out = copy(l)
        val written = mutableListOf<String>()
        val skipped = LinkedHashMap<String, Skip>()
        for (p in paths.toSortedSet()) {
            val v = r[p]
            when {
                p !in observed -> skipped[p] = Skip.NOT_OBSERVED
                p in readOnly -> skipped[p] = Skip.READ_ONLY
                v == null -> skipped[p] = Skip.HOLDS_NONE
                else -> { put(out, p, copyValue(v)); written += p }
            }
        }
        return Written(out, written, skipped)
    }

    private fun copyValue(v: Any): Any = when (v) {
        is JSONObject -> JSONObject(v.toString())
        is JSONArray -> JSONArray(v.toString())
        else -> v
    }

    /**
     * SERVER → RUNTIME, as a plan: for each of [paths] S holds and R observed, the value to push, by
     * app. Executing it is the app's ([AccountRuntime.push]); a path S does not hold is not a push.
     */
    fun pushPlan(s: Map<String, Any>, observed: Set<String>, paths: Collection<String>, apps: List<App>): Map<String, List<Pair<String, Any>>> {
        val out = LinkedHashMap<String, MutableList<Pair<String, Any>>>()
        for (p in paths.toSortedSet()) {
            val v = s[p] ?: continue
            if (p !in observed) continue
            out.getOrPut(ownerOf(p, apps)) { mutableListOf() } += p to v
        }
        return out
    }

    /** Every drifted path of [fields] belonging to [app] ("*" = every app). */
    fun drifted(fields: List<Field>, app: String = "*"): List<String> =
        fields.filter { it.kind != Kind.SAME && (app == "*" || it.app == app) }.map { it.path }
}
