package com.diegonmarcos.superapp.perms

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import org.json.JSONObject

/**
 * #1360 what THIS app needs, as declared in its build.json::perms and generated into assets/app-perms.json by
 * 1_cicd/src/scripts/cloud-android-perms-guard.py --write (the guard fails when the two differ, when code uses a gated
 * API that is not declared, or when the manifest asks for something the declaration leaves out).
 *  - [AppPermsDecl.needs]: catalogue ids of this app's own grants, the rows of its Perms section.
 *  - [AppPermsDecl.helpers]: fleet apps that do work FOR this app (cld.termux runs Cloud Drive's git and the shared
 *    store), with the grants THEY must hold. Their state is read from the helper's own [PermsStateProvider].
 *  - [AppPermsDecl.firstRun]: "libs:perms" = [PermsFirstRun] asks at first launch; "own:<File.kt>" = the app asks itself.
 * Pure parse (org.json): JVM-tested.
 */
data class HelperNeed(val pkg: String, val label: String, val needs: List<String>, val why: String)

data class AppPermsDecl(val needs: List<String>, val firstRun: String, val helpers: List<HelperNeed>, val declared: Boolean) {
    companion object {
        const val ASSET = "app-perms.json"
        val NONE = AppPermsDecl(emptyList(), "libs:perms", emptyList(), declared = false)

        fun parse(json: String): AppPermsDecl {
            val o = JSONObject(json)
            fun strings(a: org.json.JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }
            val hs = o.optJSONArray("helpers")
            val helpers = if (hs == null) emptyList() else (0 until hs.length()).map { i ->
                val h = hs.getJSONObject(i)
                HelperNeed(h.getString("package"), h.optString("label", h.getString("package")), strings(h.optJSONArray("needs")), h.optString("why"))
            }
            return AppPermsDecl(strings(o.optJSONArray("needs")), o.optString("first_run", "libs:perms"), helpers, declared = true)
        }
    }
}

/** One grant a helper app must hold for this app's work, with its live state on this phone. */
data class HelperRow(val helper: HelperNeed, val entry: PermEntry, val status: PermStatus)

object AppPerms {
    @Volatile private var cached: AppPermsDecl? = null

    fun decl(ctx: Context): AppPermsDecl = cached ?: synchronized(this) {
        cached ?: runCatching {
            AppPermsDecl.parse(ctx.applicationContext.assets.open(AppPermsDecl.ASSET).bufferedReader().use { it.readText() })
        }.getOrElse { AppPermsDecl.NONE }.also { cached = it }
    }

    /** This app's rows: the declared needs (catalogue order); an app with no declaration gets every applicable entry. */
    fun own(ctx: Context, activity: android.app.Activity? = null): List<PermsActivity.Row> {
        val d = decl(ctx)
        val vars = Perms.vars(ctx)
        val declared = Perms.declared(ctx)
        val entries = Perms.catalogue(ctx).entries.filter { !d.declared || it.id in d.needs }
        return PermsActivity.rows(entries, emptySet()) { PermStatusReader.status(ctx, it, activity, vars, declared) }
    }

    /** Every grant every helper must hold, read live from the helper's [PermsStateProvider]. */
    fun helpers(ctx: Context): List<HelperRow> {
        val cat = Perms.catalogue(ctx)
        return decl(ctx).helpers.flatMap { h ->
            val states = HelperState.read(ctx, h.pkg)
            h.needs.mapNotNull { id -> cat.byId(id)?.let { e -> HelperRow(h, e, states(id)) } }
        }
    }

    /** The ids of this app's own missing grants (empty = nothing to ask). */
    fun missingOwn(ctx: Context): List<PermEntry> = own(ctx).filter { it.status.missing && it.entry.kind != PermKind.ADB_ONLY }.map { it.entry }

    fun missingHelpers(ctx: Context): List<HelperRow> = helpers(ctx).filter { it.status.missing }

    /** Opens the page that grants [row]: the helper's own settings page for that entry (its package, not ours). */
    fun openHelper(ctx: Context, row: HelperRow): Boolean =
        if (row.status.reason == HelperState.NOT_INSTALLED) PermsPages.openAppInfo(ctx, row.helper.pkg)
        else PermsSettings.open(ctx, row.entry, row.helper.pkg)

    internal fun reset() { cached = null }
}

/** Reads a helper app's grants from its [PermsStateProvider] (content://<pkg>.perms/state). */
object HelperState {
    const val NOT_INSTALLED = "not installed"

    fun uri(pkg: String): Uri = Uri.parse("content://$pkg${PermsStateProvider.AUTHORITY_SUFFIX}/state")

    fun read(ctx: Context, pkg: String): (String) -> PermStatus {
        val installed = runCatching { ctx.packageManager.getPackageInfo(pkg, 0); true }
            .getOrElse { it !is PackageManager.NameNotFoundException }
        if (!installed) return { PermStatus(PermState.DENIED, NOT_INSTALLED) }
        val rows = HashMap<String, PermStatus>()
        val ok = runCatching {
            ctx.contentResolver.query(uri(pkg), null, null, null, null)?.use { c ->
                val iId = c.getColumnIndexOrThrow("id"); val iSt = c.getColumnIndexOrThrow("state"); val iWhy = c.getColumnIndexOrThrow("reason")
                while (c.moveToNext()) {
                    val st = runCatching { PermState.valueOf(c.getString(iSt)) }.getOrDefault(PermState.UNKNOWN)
                    rows[c.getString(iId)] = PermStatus(st, c.getString(iWhy).orEmpty())
                }
                true
            } ?: false
        }.getOrDefault(false)
        return { id -> rows[id] ?: PermStatus(PermState.UNKNOWN, if (ok) "not reported by $pkg" else "$pkg cannot report it (update it): open its settings") }
    }
}
