package com.diegonmarcos.superapp.appstore

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * This phone's edits to the Declared list, over the source map baked into the APK: apps the user
 * declared (from Search or from Installed, with the rungs they were found on) and declared apps the
 * user removed. The Phone page applies them in one place ([apply]), so Declared, Installed's
 * declared/not-declared filter and Install all all see the same list.
 * // ponytail: kept on this phone; carry it in the Account device profile when the A37 needs it.
 */
object DeclaredEdits {
    private const val PREFS = "store_declared_edits"
    private const val KEY = "edits"

    private fun load(ctx: Context): JSONObject = runCatching {
        JSONObject(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "{}") ?: "{}")
    }.getOrDefault(JSONObject())

    private fun save(ctx: Context, o: JSONObject) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, o.toString()).apply()

    private fun added(o: JSONObject): JSONObject = o.optJSONObject("added") ?: JSONObject().also { o.put("added", it) }
    private fun removed(o: JSONObject): MutableSet<String> {
        val a = o.optJSONArray("removed") ?: JSONArray()
        return (0 until a.length()).map { a.getString(it) }.toMutableSet()
    }

    /** Declare [pkg] with the rung kinds it can install from (`fdroid`, `play-anon`, …). */
    fun declare(ctx: Context, pkg: String, label: String, kinds: List<String>) {
        val o = load(ctx)
        added(o).put(pkg, JSONObject().put("label", label).put("sources", JSONArray(kinds)))
        o.put("removed", JSONArray(removed(o) - pkg))
        save(ctx, o)
    }

    /** Take [pkg] off Declared: a user-added row is forgotten, a built-in one is hidden. */
    fun undeclare(ctx: Context, pkg: String) {
        val o = load(ctx)
        added(o).remove(pkg)
        o.put("removed", JSONArray(removed(o) + pkg))
        save(ctx, o)
    }

    /** The packages the user added (they may not be in the built-in map). */
    fun addedPackages(ctx: Context): Map<String, String> {
        val a = added(load(ctx))
        return a.keys().asSequence().associateWith { a.getJSONObject(it).optString("label", it) }
    }

    /** [ext] as this phone declares it: removed → undeclared; user-added → declared with its rungs. */
    fun apply(ctx: Context, cfg: SourceResolver.Config, pkg: String, ext: SourceResolver.External): SourceResolver.External {
        val o = load(ctx)
        if (pkg in removed(o)) return SourceResolver.External(pkg, ext.label, ext.sources, declared = false,
            unresolved = ext.unresolved, officialPage = ext.officialPage, integrity = ext.integrity)
        val add = added(o).optJSONObject(pkg) ?: return ext
        if (ext.declared) return ext
        val kinds = add.optJSONArray("sources") ?: JSONArray()
        val sources = (0 until kinds.length()).mapNotNull { i ->
            when (kinds.getString(i)) {
                SourceResolver.KIND_FDROID -> SourceResolver.Source.FDroid
                SourceResolver.KIND_PLAY_ANON -> SourceResolver.Source.PlayAnon
                else -> null
            }
        }
        return SourceResolver.External(pkg, add.optString("label", ext.label), sources, declared = true, integrity = ext.integrity)
    }
}
