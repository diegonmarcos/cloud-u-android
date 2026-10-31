package com.diegonmarcos.cloudme

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.health.HealthConnectGateway
import com.diegonmarcos.superapp.perms.PermKind
import com.diegonmarcos.superapp.perms.PermState
import com.diegonmarcos.superapp.perms.PermStatusReader
import com.diegonmarcos.superapp.perms.Perms
import com.diegonmarcos.superapp.uiicons.UiIcons
import com.diegonmarcos.superapp.uikit.KitButton
import com.diegonmarcos.superapp.uikit.KitDensity
import com.diegonmarcos.superapp.uikit.KitState
import com.diegonmarcos.superapp.uikit.KitStatePill
import com.diegonmarcos.superapp.uikit.KitTextButton
import com.diegonmarcos.superapp.uikit.KitTonalButton
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import com.diegonmarcos.superapp.uikit.kitStateColor
import org.json.JSONArray
import org.json.JSONObject

/**
 * Configs > Perms (build.json::ui.permissions). The list, the live state and the settings intents of every catalogue
 * entry are the shared libs:perms ones (the SuperApp's Perms page reads the same catalogue); this file only adds Cloud
 * Me's reasons, the install-time rows, the Health Connect rows, and the start-up / Grant all walk. It names no permission.
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

object PermissionTags {
    const val BANNER = "me:perm:banner"
    const val BANNER_ACTION = "me:perm:banner:grant-all"
    const val BANNER_DISMISS = "me:perm:banner:dismiss"
    const val GRANT_ALL = "me:perm:grant-all"
    const val PROGRESS = "me:perm:progress"
    const val HEALTH_EXPAND = "me:perm:health:expand"
    const val UNUSED_EXPAND = "me:perm:unused:expand"
    fun row(id: String) = "me:perm:$id"
    fun grant(id: String) = "me:perm:grant:$id"
    fun group(title: String) = "me:perm:group:$title"
}

/** The state chip of a row: what it says and how it is painted (green / amber / grey). */
enum class PermChip(val word: String, val kit: KitState) {
    GRANTED("Granted", KitState.OK),
    MISSING("Missing", KitState.WARN),
    NOT_APPLICABLE("Not applicable", KitState.IDLE),
    UNKNOWN("Unknown", KitState.IDLE),
}

/**
 * What Configs > Perms shows, as pure rules over the rows (MePermsTest). Health Connect's data types are ONE unit (one
 * Health Connect screen grants them), install-time rows are not actionable, and a row declared `used: false` sits in
 * the collapsed Not used yet group.
 */
object MePermView {
    data class Progress(val granted: Int, val total: Int) {
        val label: String get() = "$granted of $total granted"
        val missing: Int get() = total - granted
        val fraction: Float get() = if (total == 0) 1f else granted.toFloat() / total
    }

    /** A titled group of rows; [collapsed] groups open on a tap. */
    data class Group(val title: String, val rows: List<PermRow>, val collapsed: Boolean = false)

    const val NOT_USED = "Not used yet"

    fun chip(r: PermRow): PermChip = when (r.state) {
        PermState.GRANTED -> PermChip.GRANTED
        PermState.DENIED, PermState.PERMANENTLY_DENIED -> PermChip.MISSING
        PermState.NOT_APPLICABLE -> PermChip.NOT_APPLICABLE
        PermState.UNKNOWN -> PermChip.UNKNOWN
    }

    /** The second line: the reason, or why it cannot be granted from the dialog any more, or why it does not apply. */
    fun reasonLine(r: PermRow): String = when {
        r.state == PermState.PERMANENTLY_DENIED -> "Denied twice: allow it in Settings"
        r.state == PermState.NOT_APPLICABLE && r.reason.isNotBlank() -> r.reason
        else -> r.why
    }

    /** Health Connect as one row: granted when every type is, missing when one is, not applicable without it. */
    fun healthState(rows: List<PermRow>): PermState {
        val h = rows.filter { it.kind == RowKind.HEALTH }
        return when {
            h.isEmpty() || h.all { it.state == PermState.NOT_APPLICABLE } -> PermState.NOT_APPLICABLE
            h.any { it.missing } -> PermState.DENIED
            h.all { it.state == PermState.GRANTED } -> PermState.GRANTED
            else -> PermState.UNKNOWN
        }
    }

    /** "3 of 12 granted" over what Grant all can act on: runtime and special rows, and Health Connect counted once. */
    fun progress(rows: List<PermRow>): Progress {
        val states = rows.filter { it.kind == RowKind.RUNTIME || it.kind == RowKind.SPECIAL }.map { it.state } + healthState(rows)
        val counted = states.filter { it == PermState.GRANTED || it == PermState.DENIED || it == PermState.PERMANENTLY_DENIED }
        return Progress(counted.count { it == PermState.GRANTED }, counted.size)
    }

    /** The page's groups in order; empty ones are left out. Unused rows of any kind go to the collapsed [NOT_USED]. */
    fun groups(rows: List<PermRow>): List<Group> {
        val used = rows.filter { it.used }
        return listOf(
            Group("Runtime", used.filter { it.kind == RowKind.RUNTIME }),
            Group("Special access", used.filter { it.kind == RowKind.SPECIAL }),
            Group("Health Connect", used.filter { it.kind == RowKind.HEALTH }),
            Group("Granted at install", used.filter { it.kind == RowKind.INSTALL }),
            Group(NOT_USED, rows.filter { !it.used }, collapsed = true),
        ).filter { it.rows.isNotEmpty() }
    }

    /** The banner sentence: a count and the first names, short enough for two lines. */
    fun bannerText(missing: List<String>): String = when (missing.size) {
        0 -> ""
        1 -> "${missing[0]} is not granted"
        else -> "${missing.size} permissions missing: " + missing.take(3).joinToString(", ") + if (missing.size > 3) " and ${missing.size - 3} more" else ""
    }

    /** The trailing button's word for a step. */
    fun actionWord(step: PermStep): String = if (step is PermStep.Settings) "Open" else "Grant"
}

/** The button a row shows: missing rows, and special rows whose state the phone does not report. */
private fun rowStep(r: PermRow, rows: List<PermRow>): PermStep? =
    if (r.missing || (r.kind == RowKind.SPECIAL && r.state == PermState.UNKNOWN)) MePermPlan.forRow(r, rows) else null

/** Configs > Perms: a summary card with Grant all, then one card per group of rows with their state chip and button. */
@Composable
fun PermissionsList() {
    val ctx = LocalContext.current
    val hooks = LocalMePermissions.current
    val health by produceState<Pair<Boolean, Set<String>?>>(true to null, hooks.tick) { value = MePerms.health(ctx) }
    val rows = MePerms.rows(ctx, ctx as? Activity, health.first, health.second)
    val all = MePermPlan.grantAll(rows)
    val prog = MePermView.progress(rows)
    val p = LocalKitPalette.current
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        PermCard {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(prog.label, color = p.textPrimary, fontSize = KitDensity.title, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f).testTag(PermissionTags.PROGRESS))
                    KitStatePill(if (prog.missing == 0) "All set" else "${prog.missing} missing", if (prog.missing == 0) KitState.OK else KitState.WARN)
                }
                LinearProgressIndicator(
                    progress = { prog.fraction }, color = if (prog.missing == 0) p.ok else p.accent, trackColor = p.surfaceSelected,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(6.dp).clip(RoundedCornerShape(3.dp)),
                )
                if (all.isNotEmpty()) KitButton(
                    onClick = { hooks.run(all) },
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = 40.dp).testTag(PermissionTags.GRANT_ALL),
                ) { Text("Grant all") }
            }
        }
        for (g in MePermView.groups(rows)) PermGroup(g, rows, hooks)
    }
}

@Composable
private fun PermCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val p = LocalKitPalette.current
    val shape = RoundedCornerShape(KitDensity.corner)
    Column(modifier.fillMaxWidth().clip(shape).background(p.surface).border(KitDensity.rule, p.hairline, shape)) { content() }
}

@Composable
private fun PermGroup(g: MePermView.Group, rows: List<PermRow>, hooks: MePermissions) {
    val p = LocalKitPalette.current
    var open by rememberSaveable(g.title) { mutableStateOf(!g.collapsed) }
    val granted = g.rows.count { it.state == PermState.GRANTED }
    Row(
        Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp, start = 4.dp, end = 4.dp).testTag(PermissionTags.group(g.title))
            .then(if (g.collapsed) Modifier.clickable { open = !open }.testTag(PermissionTags.UNUSED_EXPAND) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(g.title.uppercase(), color = p.textSecondary, fontSize = KitDensity.caption, fontWeight = FontWeight.SemiBold,
            letterSpacing = KitDensity.eyebrowTracking, modifier = Modifier.weight(1f))
        Text(if (g.collapsed) "${g.rows.size}" else "$granted / ${g.rows.size}", color = p.textSecondary, fontSize = KitDensity.caption)
        if (g.collapsed) Icon(if (open) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, contentDescription = if (open) "Collapse" else "Expand",
            tint = p.textSecondary, modifier = Modifier.padding(start = 4.dp).size(20.dp))
    }
    if (!open) return
    PermCard {
        if (g.collapsed) Text("On the list for features that are not built yet. Grant all still asks for them.",
            color = p.textSecondary, fontSize = KitDensity.caption, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp))
        if (g.rows.first().kind == RowKind.HEALTH && !g.collapsed) HealthRows(g.rows, rows, hooks)
        else g.rows.forEachIndexed { i, r ->
            if (i > 0) HorizontalDivider(color = p.hairline, thickness = KitDensity.rule, modifier = Modifier.padding(start = 64.dp))
            PermRowView(r.label, MePermView.reasonLine(r), r.icon, MePermView.chip(r), rowStep(r, rows), r.id, hooks)
        }
    }
}

/** Health Connect: one summary row (one screen grants every type), then the types, collapsed. */
@Composable
private fun HealthRows(part: List<PermRow>, rows: List<PermRow>, hooks: MePermissions) {
    val p = LocalKitPalette.current
    var open by rememberSaveable { mutableStateOf(false) }
    val state = MePermView.healthState(rows)
    val head = PermRow("health_connect", "Health Connect", "", RowKind.HEALTH, "", state)
    val reads = part.count { it.label.startsWith("Read") }; val writes = part.size - reads
    val line = if (state == PermState.NOT_APPLICABLE) part.first().reason else "Reads $reads and writes $writes data types for Health"
    PermRowView("Health Connect", line, part.first().icon, MePermView.chip(head),
        part.firstOrNull { it.missing }?.let { MePermPlan.forRow(it, rows) }, "health_connect", hooks)
    HorizontalDivider(color = p.hairline, thickness = KitDensity.rule)
    Row(
        Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 16.dp, vertical = 12.dp).testTag(PermissionTags.HEALTH_EXPAND),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (open) "Hide the ${part.size} data types" else "Show the ${part.size} data types", color = p.accent, fontSize = KitDensity.body,
            modifier = Modifier.weight(1f))
        Icon(if (open) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, contentDescription = null, tint = p.accent, modifier = Modifier.size(20.dp))
    }
    if (open) for (r in part) Row(
        Modifier.fillMaxWidth().padding(start = 64.dp, end = 16.dp, top = 6.dp, bottom = 6.dp).testTag(PermissionTags.row(r.id)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(r.label, color = p.textPrimary, fontSize = KitDensity.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        val c = MePermView.chip(r)
        Box(Modifier.size(KitDensity.dot).clip(CircleShape).background(kitStateColor(c.kit)))
        Text(c.word, color = p.textSecondary, fontSize = KitDensity.caption, modifier = Modifier.padding(start = 6.dp).widthIn(min = 64.dp))
    }
    if (open) Spacer(Modifier.height(8.dp))
}

/** One row: icon tile, title, one-line reason, the state chip, and the trailing Grant / Open button when it can act. */
@Composable
private fun PermRowView(label: String, reason: String, icon: String, chip: PermChip, step: PermStep?, id: String, hooks: MePermissions) {
    val p = LocalKitPalette.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp).testTag(PermissionTags.row(id)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(p.surfaceSelected), contentAlignment = Alignment.Center) {
            Icon(UiIcons.vector(icon.ifBlank { "security" }), contentDescription = null, tint = p.accent, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
            Text(label, color = p.textPrimary, fontSize = KitDensity.title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (reason.isNotBlank()) Text(reason, color = p.textSecondary, fontSize = KitDensity.caption, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp))
            KitStatePill(chip.word, chip.kit, Modifier.padding(top = 8.dp))
        }
        if (step != null) KitTonalButton(onClick = { hooks.run(listOf(step)) }, modifier = Modifier.testTag(PermissionTags.grant(id))) {
            Text(MePermView.actionWord(step))
        }
    }
}

/** The card under the top bar while a runtime or Health Connect permission is missing: what, Grant all, and a close. */
@Composable
fun PermissionBanner(missing: List<String>, onGrantAll: () -> Unit) {
    if (missing.isEmpty()) return
    // dismissed for this set of missing names: a newly missing one brings it back
    var dismissed by rememberSaveable { mutableStateOf("") }
    val key = missing.joinToString("|")
    if (dismissed == key) return
    val p = LocalKitPalette.current
    val shape = RoundedCornerShape(KitDensity.corner)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(shape).background(p.surface)
            .border(KitDensity.rule, p.warn, shape).padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp).testTag(PermissionTags.BANNER),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(UiIcons.vector("security"), contentDescription = null, tint = p.warn, modifier = Modifier.size(20.dp))
        Text(MePermView.bannerText(missing), color = p.textPrimary, fontSize = KitDensity.body, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 12.dp, end = 8.dp))
        KitTextButton(onClick = onGrantAll, modifier = Modifier.testTag(PermissionTags.BANNER_ACTION)) { Text("Grant all", color = p.accent) }
        IconButton(onClick = { dismissed = key }, modifier = Modifier.size(40.dp).testTag(PermissionTags.BANNER_DISMISS)) {
            Icon(Icons.Outlined.Close, contentDescription = "Dismiss", tint = p.textSecondary, modifier = Modifier.size(18.dp))
        }
    }
}
