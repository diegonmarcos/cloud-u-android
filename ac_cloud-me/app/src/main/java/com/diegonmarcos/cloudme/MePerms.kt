package com.diegonmarcos.cloudme

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.util.Base64
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.health.HealthConnectGateway
import com.diegonmarcos.superapp.perms.PermAction
import com.diegonmarcos.superapp.perms.PermItem
import com.diegonmarcos.superapp.perms.PermItemKind
import com.diegonmarcos.superapp.perms.PermKind
import com.diegonmarcos.superapp.perms.PermState
import com.diegonmarcos.superapp.perms.PermStatusReader
import com.diegonmarcos.superapp.perms.Perms
import com.diegonmarcos.superapp.perms.PermsList
import com.diegonmarcos.superapp.perms.PermsPromptCard
import com.diegonmarcos.superapp.perms.PermsStyle
import com.diegonmarcos.superapp.perms.PermsView
import com.diegonmarcos.superapp.uiicons.UiIcons
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import org.json.JSONArray
import org.json.JSONObject

/**
 * Configs > Perms (build.json::ui.permissions). The list, the live state and the settings intents of every catalogue
 * entry are the shared libs:perms ones (the SuperApp's Perms page reads the same catalogue); this file only adds Cloud
 * Me's reasons, the install-time rows, the Health Connect rows, and the start-up / Grant all walk. It names no permission.
 * #1372 it draws nothing of its own: the page is libs:perms PermsList and the banner its PermsPromptCard (PermsView rules).
 */
data class MePermDecl(
    val entries: List<Entry>,
    val install: List<InstallPerm>,
    val healthRead: List<String>,
    val healthWrite: List<String>,
    val rationale: String,
    val healthIcon: String = "",
) {
    /** A catalogue row: its libs:ui-icons [icon], Cloud Me's one-line [why], and [used] = false while no feature needs it yet. */
    data class Entry(val id: String, val why: String, val icon: String = "", val used: Boolean = true)
    data class InstallPerm(val perm: String, val label: String, val why: String, val icon: String = "")

    val health: List<String> get() = (healthRead + healthWrite).distinct()

    companion object {
        fun parse(json: String): MePermDecl {
            val o = JSONObject(json)
            fun arr(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.get(it) }
            val h = o.optJSONObject("health") ?: JSONObject()
            return MePermDecl(
                entries = arr(o.optJSONArray("entries")).map { (it as JSONObject).let { e -> Entry(e.getString("id"), e.optString("why"), e.optString("icon"), e.optBoolean("used", true)) } },
                install = arr(o.optJSONArray("install")).map { (it as JSONObject).let { e -> InstallPerm(e.getString("perm"), e.optString("label"), e.optString("why"), e.optString("icon")) } },
                healthRead = arr(h.optJSONArray("read")).map { it.toString() },
                healthWrite = arr(h.optJSONArray("write")).map { it.toString() },
                rationale = h.optString("rationale"),
                healthIcon = h.optString("icon"),
            )
        }

        val baked: MePermDecl by lazy {
            runCatching { parse(String(Base64.decode(BuildConfig.UI_PERMISSIONS_B64, Base64.DEFAULT))) }
                .getOrDefault(MePermDecl(emptyList(), emptyList(), emptyList(), emptyList(), ""))
        }
    }
}

/** How a row is granted: the system dialog, a settings page, Health Connect's own screen, or nothing (install time). */
enum class RowKind { RUNTIME, SPECIAL, HEALTH, INSTALL }

/** One row of Configs > Perms. [permission] is the Android name (runtime and health rows), [id] the catalogue id or the permission. */
data class PermRow(
    val id: String, val label: String, val why: String, val kind: RowKind,
    val permission: String, val state: PermState, val reason: String = "",
    val icon: String = "", val used: Boolean = true,
) {
    val missing: Boolean get() = state == PermState.DENIED || state == PermState.PERMANENTLY_DENIED
}

/** One step of a walk: one system dialog batch, one Health Connect request, or one settings page. */
sealed interface PermStep {
    data class Runtime(val perms: List<String>) : PermStep
    data class Health(val perms: Set<String>) : PermStep
    data class Settings(val id: String) : PermStep
}

/** What was already asked: runtime permissions shown a dialog, Health Connect requests made, special pages opened. */
data class AskMemory(val healthAsks: Int, val specialOpened: Set<String>)

/** The walks, as pure rules over the rows (MePermsTest). */
object MePermPlan {
    /** Health Connect stops showing its request after two declines; asking a third time would be a silent no-op. */
    const val HEALTH_MAX_ASKS = 2

    /**
     * On app open. Runtime: every DENIED one (never asked, or denied once) in one dialog batch; a permanently denied one
     * is never asked at start. Health Connect: the missing set, until it was declined [HEALTH_MAX_ASKS] times. Special
     * access: its settings page, once ever (first launch, or the first open after it became needed); later only from
     * the row, the banner or Grant all.
     */
    fun startup(rows: List<PermRow>, mem: AskMemory): List<PermStep> {
        val out = ArrayList<PermStep>()
        rows.filter { it.kind == RowKind.RUNTIME && it.state == PermState.DENIED }.map { it.permission }.distinct()
            .takeIf { it.isNotEmpty() }?.let { out += PermStep.Runtime(it) }
        if (mem.healthAsks < HEALTH_MAX_ASKS) healthStep(rows)?.let { out += it }
        rows.filter { it.kind == RowKind.SPECIAL && it.state == PermState.DENIED && it.id !in mem.specialOpened }
            .forEach { out += PermStep.Settings(it.id) }
        return out
    }

    /** Grant all: every missing row, whatever was asked before. A permanently denied runtime one goes to its settings page. */
    fun grantAll(rows: List<PermRow>): List<PermStep> {
        val out = ArrayList<PermStep>()
        val runtime = rows.filter { it.kind == RowKind.RUNTIME && it.missing }
        runtime.filter { it.state == PermState.DENIED }.map { it.permission }.distinct()
            .takeIf { it.isNotEmpty() }?.let { out += PermStep.Runtime(it) }
        healthStep(rows)?.let { out += it }
        runtime.filter { it.state == PermState.PERMANENTLY_DENIED }.forEach { out += PermStep.Settings(it.id) }
        rows.filter { it.kind == RowKind.SPECIAL && it.missing }.forEach { out += PermStep.Settings(it.id) }
        return out
    }

    /** One row's grant button. Health rows ask for every missing Health Connect permission at once (one screen). */
    fun forRow(row: PermRow, rows: List<PermRow>): PermStep? = when (row.kind) {
        RowKind.INSTALL -> null
        RowKind.HEALTH -> healthStep(rows)
        RowKind.RUNTIME -> if (row.state == PermState.DENIED) PermStep.Runtime(listOf(row.permission)) else PermStep.Settings(row.id)
        RowKind.SPECIAL -> if (row.state == PermState.NOT_APPLICABLE) null else PermStep.Settings(row.id)
    }

    private fun healthStep(rows: List<PermRow>): PermStep.Health? =
        rows.filter { it.kind == RowKind.HEALTH && it.missing }.map { it.permission }.toSet().takeIf { it.isNotEmpty() }?.let { PermStep.Health(it) }

    /** The banner: what is missing that a feature needs right now (runtime and Health Connect), by label. */
    fun banner(rows: List<PermRow>): List<String> {
        val runtime = rows.filter { it.kind == RowKind.RUNTIME && it.missing }.map { it.label }
        val health = rows.count { it.kind == RowKind.HEALTH && it.missing }
        return runtime + if (health > 0) listOf("Health Connect ($health)") else emptyList()
    }

    /** "Read steps" from android.permission.health.READ_STEPS. */
    fun healthLabel(perm: String): String =
        perm.substringAfterLast('.').lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
}

/** Reads every declared row's live state. [healthGranted]: null while unknown, empty when Health Connect is absent. */
object MePerms {
    private const val PREFS = "me_perm_walk"

    fun memory(ctx: Context): AskMemory = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).let {
        AskMemory(it.getInt("health_asks", 0), it.getStringSet("special_opened", emptySet()).orEmpty())
    }

    fun noteHealthAsked(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).let {
        it.edit().putInt("health_asks", it.getInt("health_asks", 0) + 1).apply()
    }

    fun noteSpecialOpened(ctx: Context, id: String) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).let {
        it.edit().putStringSet("special_opened", it.getStringSet("special_opened", emptySet()).orEmpty() + id).apply()
    }

    fun rows(ctx: Context, activity: Activity?, healthAvailable: Boolean, healthGranted: Set<String>?, decl: MePermDecl = MePermDecl.baked): List<PermRow> {
        val cat = Perms.catalogue(ctx)
        val vars = Perms.vars(ctx)
        val out = ArrayList<PermRow>()
        for (d in decl.entries) {
            val e = cat.byId(d.id)
            if (e == null) { out += PermRow(d.id, d.id, d.why, RowKind.SPECIAL, "", PermState.UNKNOWN, "not in the libs:perms catalogue", d.icon, d.used); continue }
            val s = PermStatusReader.status(ctx, e, activity, vars)
            out += PermRow(d.id, e.label, d.why, if (e.kind == PermKind.RUNTIME) RowKind.RUNTIME else RowKind.SPECIAL, e.permission, s.state,
                s.reason, d.icon, d.used)
        }
        for (p in decl.health) {
            val state = when {
                !healthAvailable -> PermState.NOT_APPLICABLE
                healthGranted == null -> PermState.UNKNOWN
                p in healthGranted -> PermState.GRANTED
                else -> PermState.DENIED
            }
            out += PermRow(p, MePermPlan.healthLabel(p), "Health Connect", RowKind.HEALTH, p, state,
                if (!healthAvailable) "Health Connect is not available on this phone" else "", decl.healthIcon)
        }
        for (i in decl.install) {
            val g = ctx.checkSelfPermission(i.perm) == PackageManager.PERMISSION_GRANTED
            out += PermRow(i.perm, i.label, i.why, RowKind.INSTALL, i.perm, if (g) PermState.GRANTED else PermState.UNKNOWN, "", i.icon)
        }
        return out
    }

    /** Health Connect availability and grants, off the main thread. */
    suspend fun health(ctx: Context): Pair<Boolean, Set<String>> {
        val ok = HealthConnectGateway.isProviderInstalled(ctx)
        return ok to if (ok) runCatching { HealthConnectGateway.grantedPermissions(ctx) }.getOrDefault(emptySet()) else emptySet()
    }
}

/** The shell's hooks: [run] walks steps (one row, or Grant all); [tick] changes whenever an answer may have changed. */
class MePermissions(val run: (List<PermStep>) -> Unit, val tick: Int)

val LocalMePermissions = staticCompositionLocalOf { MePermissions({}, 0) }

/**
 * Configs > Perms rows as the shared libs:perms items (#1372): PermsView groups, counts and chips them, PermsList draws
 * them. A row's action is the step [MePermPlan.forRow] would run: a settings page is Open, a dialog or Health Connect Grant.
 */
object MePermView {
    fun kind(k: RowKind): PermItemKind = when (k) {
        RowKind.RUNTIME -> PermItemKind.RUNTIME
        RowKind.SPECIAL -> PermItemKind.SPECIAL
        RowKind.HEALTH -> PermItemKind.HEALTH
        RowKind.INSTALL -> PermItemKind.INSTALL
    }

    fun items(rows: List<PermRow>): List<PermItem> = rows.map { r ->
        val step = rowStep(r, rows)
        PermItem(r.id, r.label, if (r.kind == RowKind.HEALTH) "" else r.why, r.icon, kind(r.kind), r.state, r.reason,
            step?.let { if (it is PermStep.Settings) PermAction.OPEN else PermAction.GRANT }, r.used)
    }
}

/** The button a row shows: missing rows, and special rows whose state the phone does not report. */
private fun rowStep(r: PermRow, rows: List<PermRow>): PermStep? =
    if (r.missing || (r.kind == RowKind.SPECIAL && r.state == PermState.UNKNOWN)) MePermPlan.forRow(r, rows) else null

/** Cloud Me's ui-kit palette handed to the shared Perms UI, so it reads as part of the page. */
@Composable
private fun meStyle(): PermsStyle {
    val p = LocalKitPalette.current
    return PermsStyle(ink = p.textPrimary, dim = p.textSecondary, accent = p.accent, surface = p.surface, hairline = p.hairline,
        ok = p.ok, warn = p.warn, bad = p.bad)
}

/** Configs > Perms: libs:perms' summary card with Grant all, then the grouped rows, with Cloud Me's reasons and icons. */
@Composable
fun PermissionsList() {
    val ctx = LocalContext.current
    val hooks = LocalMePermissions.current
    val health by produceState<Pair<Boolean, Set<String>?>>(true to null, hooks.tick) { value = MePerms.health(ctx) }
    val rows = MePerms.rows(ctx, ctx as? Activity, health.first, health.second)
    val all = MePermPlan.grantAll(rows)
    PermsList(
        items = MePermView.items(rows),
        onAction = { i -> rows.firstOrNull { it.id == i.id }?.let { MePermPlan.forRow(it, rows) }?.let { hooks.run(listOf(it)) } },
        onGrantAll = if (all.isEmpty()) null else ({ hooks.run(all) }),
        modifier = Modifier.padding(top = 12.dp),
        style = meStyle(),
        iconOf = { UiIcons.vector(it.ifBlank { "security" }) },
    )
}

/** The card under the top bar while a runtime or Health Connect permission is missing: libs:perms' one prompt card. */
@Composable
fun PermissionBanner(missing: List<String>, onGrantAll: () -> Unit) {
    PermsPromptCard(PermsView.promptText(missing), onGrantAll, Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        button = "Grant all", dismissKey = missing.joinToString("|"), style = meStyle())
}
