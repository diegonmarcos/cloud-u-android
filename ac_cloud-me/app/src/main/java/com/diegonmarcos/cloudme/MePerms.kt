package com.diegonmarcos.cloudme

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.health.HealthConnectGateway
import com.diegonmarcos.superapp.perms.PermKind
import com.diegonmarcos.superapp.perms.PermState
import com.diegonmarcos.superapp.perms.PermStatusReader
import com.diegonmarcos.superapp.perms.Perms
import org.json.JSONArray
import org.json.JSONObject

/**
 * Configs > Perms (build.json::ui.permissions). The list, the live state and the settings intents of every catalogue
 * entry are the shared libs:perms ones (the SuperApp's Perms page reads the same catalogue); this file only adds Cloud
 * Me's reasons, the install-time rows, the Health Connect rows, and the start-up / Grant all walk. It names no permission.
 */
data class MePermDecl(
    val entries: List<Pair<String, String>>,
    val install: List<InstallPerm>,
    val healthRead: List<String>,
    val healthWrite: List<String>,
    val rationale: String,
) {
    data class InstallPerm(val perm: String, val label: String, val why: String)

    val health: List<String> get() = (healthRead + healthWrite).distinct()

    companion object {
        fun parse(json: String): MePermDecl {
            val o = JSONObject(json)
            fun arr(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.get(it) }
            val h = o.optJSONObject("health") ?: JSONObject()
            return MePermDecl(
                entries = arr(o.optJSONArray("entries")).map { (it as JSONObject).let { e -> e.getString("id") to e.optString("why") } },
                install = arr(o.optJSONArray("install")).map { (it as JSONObject).let { e -> InstallPerm(e.getString("perm"), e.optString("label"), e.optString("why")) } },
                healthRead = arr(h.optJSONArray("read")).map { it.toString() },
                healthWrite = arr(h.optJSONArray("write")).map { it.toString() },
                rationale = h.optString("rationale"),
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
        for ((id, why) in decl.entries) {
            val e = cat.byId(id)
            if (e == null) { out += PermRow(id, id, why, RowKind.SPECIAL, "", PermState.UNKNOWN, "not in the libs:perms catalogue"); continue }
            val s = PermStatusReader.status(ctx, e, activity, vars)
            out += PermRow(id, e.label, why, if (e.kind == PermKind.RUNTIME) RowKind.RUNTIME else RowKind.SPECIAL, e.permission, s.state,
                listOf(s.reason, e.lookFor).filter { it.isNotBlank() }.joinToString(" · "))
        }
        for (p in decl.health) {
            val state = when {
                !healthAvailable -> PermState.NOT_APPLICABLE
                healthGranted == null -> PermState.UNKNOWN
                p in healthGranted -> PermState.GRANTED
                else -> PermState.DENIED
            }
            out += PermRow(p, MePermPlan.healthLabel(p), "Health Connect", RowKind.HEALTH, p, state,
                if (!healthAvailable) "Health Connect is not available on this phone" else "")
        }
        for (i in decl.install) {
            val g = ctx.checkSelfPermission(i.perm) == PackageManager.PERMISSION_GRANTED
            out += PermRow(i.perm, i.label, i.why, RowKind.INSTALL, i.perm, if (g) PermState.GRANTED else PermState.UNKNOWN, "granted at install")
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

object PermissionTags {
    const val BANNER = "me:perm:banner"
    const val BANNER_ACTION = "me:perm:banner:grant-all"
    const val GRANT_ALL = "me:perm:grant-all"
    fun row(id: String) = "me:perm:$id"
    fun grant(id: String) = "me:perm:grant:$id"
}

private fun stateText(r: PermRow): String = when (r.state) {
    PermState.GRANTED -> "Granted"
    PermState.DENIED -> "Not granted"
    PermState.PERMANENTLY_DENIED -> "Denied: grant it in settings"
    PermState.NOT_APPLICABLE -> "Not applicable"
    PermState.UNKNOWN -> if (r.kind == RowKind.HEALTH) "Checking" else "Check it on the page"
}

private val SECTIONS = listOf(RowKind.RUNTIME to "Runtime", RowKind.SPECIAL to "Special access", RowKind.HEALTH to "Health Connect", RowKind.INSTALL to "Granted at install")

/** Configs > Perms: every permission Cloud Me needs, its state and a grant button, and one Grant all for every missing one. */
@Composable
fun PermissionsList(title: String, subtitle: String) {
    val ctx = LocalContext.current
    val hooks = LocalMePermissions.current
    val health by produceState<Pair<Boolean, Set<String>?>>(true to null, hooks.tick) { value = MePerms.health(ctx) }
    val rows = MePerms.rows(ctx, ctx as? Activity, health.first, health.second)
    val all = MePermPlan.grantAll(rows)
    val text = colorResource(R.color.me_text); val dimC = colorResource(R.color.me_text_dim); val prim = colorResource(R.color.me_primary)
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        if (title.isNotBlank()) Text(title, color = text, fontSize = 15.sp)
        if (subtitle.isNotBlank()) Text(subtitle, color = dimC, fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp))
        Text("${rows.count { it.state == PermState.GRANTED }} of ${rows.count { it.state != PermState.NOT_APPLICABLE }} granted",
            color = dimC, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        if (all.isNotEmpty()) Text("Grant all (${rows.count { it.missing }} missing)", color = prim, fontSize = 15.sp,
            modifier = Modifier.padding(vertical = 8.dp).clickable { hooks.run(all) }.testTag(PermissionTags.GRANT_ALL))
        for ((kind, head) in SECTIONS) {
            val part = rows.filter { it.kind == kind }
            if (part.isEmpty()) continue
            Text(head, color = text, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp))
            for (r in part) Row(Modifier.fillMaxWidth().padding(top = 7.dp).testTag(PermissionTags.row(r.id)), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.label, color = text, fontSize = 13.sp)
                    Text(listOf(stateText(r), r.why, r.reason).filter { it.isNotBlank() }.joinToString(" · "), color = dimC, fontSize = 11.sp)
                }
                val step = if (r.missing || (r.kind == RowKind.SPECIAL && r.state == PermState.UNKNOWN)) MePermPlan.forRow(r, rows) else null
                if (step != null) Text(if (step is PermStep.Settings) "Open" else "Grant", color = prim, fontSize = 13.sp,
                    modifier = Modifier.clickable { hooks.run(listOf(step)) }.padding(8.dp).testTag(PermissionTags.grant(r.id)))
            }
        }
    }
}

/** The strip under the top bar while a runtime or Health Connect permission is missing: what, and Grant all. */
@Composable
fun PermissionBanner(missing: List<String>, onGrantAll: () -> Unit) {
    if (missing.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().background(colorResource(R.color.me_surface_high)).padding(horizontal = 16.dp, vertical = 8.dp).testTag(PermissionTags.BANNER),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Permission needed: ${missing.joinToString()}", color = colorResource(R.color.me_text), fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text("Grant all", color = colorResource(R.color.me_primary), fontSize = 13.sp,
            modifier = Modifier.clickable { onGrantAll() }.padding(8.dp).testTag(PermissionTags.BANNER_ACTION))
    }
}
