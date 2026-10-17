package com.diegonmarcos.superapp.appstore

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitDensity
import com.diegonmarcos.superapp.uikit.KitFingerprint
import com.diegonmarcos.superapp.uikit.KitListRow
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.KitSectionHeader
import com.diegonmarcos.superapp.uikit.KitState
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import java.security.MessageDigest
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

/**
 * Configs > Network > Apps Mesh > Identity: the Linux identity of every fleet app.
 *
 * DECLARED: aa_cloud-superapp/data/fleet-identity.json, baked resolved as FLEET_IDENTITY_B64
 * (libs:appstore build.gradle): the members are the IPC registry's, and an app is in its own uid
 * unless a group names it; a group's shared_user_id is read from the file that owns it
 * (ac_cloud-termux/build.json::shared_store.shared_user_id). test-fleet-shared-uid.sh holds every
 * manifest to it.
 *
 * LIVE ([probe]): PackageManager only — the uid (getApplicationInfo(pkg).uid), the sharedUserId
 * the installed package carries, and its signing certificate's SHA-256 (short form). A row is
 * flagged when the live identity differs from the declared one.
 */
object FleetIdentity {

    const val TAG_ROW = "fleet-identity-row:"
    const val TAG_GROUP = "fleet-identity-group:"
    const val TAG_SUMMARY = "fleet-identity-summary"
    const val OWN = "own uid"

    /** [migration] = how an installed member reaches this uid ("shared_uid"), [plan] its declared backup paths (SharedUidMigration). */
    class Group(val id: String, val label: String, val sharedUserId: String?, val members: List<String>,
                val migration: String? = null, val plan: SharedUidMigration.Plan? = null)
    class Member(val id: String, val pkg: String, val label: String)
    class Registry(val groups: List<Group>, val members: List<Member>) {
        /** The group in force for [pkg]: a pending group whose value has not been declared yet is not. */
        fun groupOf(pkg: String): Group? = groups.firstOrNull { pkg in it.members && it.sharedUserId != null }
    }

    /** What the device says about one installed package. */
    class Seen(val uid: Int, val sharedUserId: String?, val signer: String?)

    class Row(
        val member: Member, val group: Group?, val declared: String, val sharesWith: List<String>,
        val seen: Seen?, val problem: String?,
    ) {
        val tag: String get() = TAG_ROW + member.id
    }

    fun baked(): Registry = parse(String(Base64.decode(BuildConfig.FLEET_IDENTITY_B64, Base64.DEFAULT)))

    fun parse(json: String): Registry {
        if (json.isBlank()) return Registry(emptyList(), emptyList())
        val o = JSONObject(json)
        val groups = o.optJSONArray("groups").objs().map { g ->
            Group(g.getString("id"), g.optString("label", g.getString("id")),
                g.optString("shared_user_id").takeIf { it.isNotEmpty() && it != "null" }, g.optJSONArray("members").strs(),
                g.optString("migration").takeIf { it.isNotEmpty() && it != "null" }, SharedUidMigration.parsePlan(g.optJSONObject("migration_plan")))
        }
        val members = o.optJSONArray("members").objs().map { Member(it.getString("id"), it.getString("pkg"), it.optString("label", it.getString("id"))) }
        return Registry(groups, members)
    }

    /** The live half: one PackageManager read per member, nothing started. */
    fun probe(ctx: Context, reg: Registry): Map<String, Seen> {
        val pm = ctx.packageManager
        return reg.members.mapNotNull { m ->
            runCatching {
                val uid = pm.getApplicationInfo(m.pkg, 0).uid
                val info = packageInfo(pm, m.pkg)
                @Suppress("DEPRECATION") val shared = info?.sharedUserId
                m.pkg to Seen(uid, shared, info?.let(::signer))
            }.getOrNull()
        }.toMap()
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(pm: PackageManager, pkg: String): PackageInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= 28) pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        else pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
    }.getOrNull()

    /** The first signer's SHA-256, as the short form the fleet prints (8 hex digits). */
    @Suppress("DEPRECATION")
    private fun signer(info: PackageInfo): String? {
        val cert = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners?.firstOrNull()
                   else info.signatures?.firstOrNull()
        return cert?.let { short(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }
    }

    fun short(digest: ByteArray): String = digest.take(4).joinToString("") { "%02X".format(it) }

    /** One row per member: the declaration, who shares it, and what the device says. */
    fun rows(reg: Registry, live: Map<String, Seen>): List<Row> = reg.members.map { m ->
        val g = reg.groupOf(m.pkg)
        val declared = g?.sharedUserId ?: OWN
        val with = g?.members.orEmpty().filter { it != m.pkg }
        val seen = live[m.pkg]
        Row(m, g, declared, with, seen, seen?.let { problem(m, g, it, live) })
    }

    /** Why the live identity is not the declared one, or null when it is. */
    fun problem(m: Member, g: Group?, seen: Seen, live: Map<String, Seen>): String? {
        val want = g?.sharedUserId
        // a sharedUserId naming the app's own package shares nothing: own uid
        val got = seen.sharedUserId?.takeIf { it != m.pkg }
        if (got != want) return "declared ${want ?: OWN}, installed ${got ?: OWN}"
        if (g != null) {
            val apart = g.members.filter { it != m.pkg }.mapNotNull { p -> live[p]?.takeIf { it.uid != seen.uid }?.let { p } }
            if (apart.isNotEmpty()) return "uid ${seen.uid} differs from ${apart.joinToString()}"
        } else {
            val same = live.filter { (p, s) -> p != m.pkg && s.uid == seen.uid }.keys
            if (same.isNotEmpty()) return "uid ${seen.uid} is shared with ${same.joinToString()} but not declared"
        }
        return null
    }

    fun summary(rows: List<Row>): String {
        val shared = rows.count { it.group != null }
        val installed = rows.count { it.seen != null }
        val bad = rows.count { it.problem != null }
        return "${rows.size} apps · $shared in a shared uid · $installed installed" + if (bad > 0) " · $bad mismatch" else ""
    }

    private fun JSONArray?.objs(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
    private fun JSONArray?.strs(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }
}

/**
 * The Identity page: the shared groups first (each with its members), then every fleet app —
 * package, identity, who shares it, the live uid and signer, and a flag when the device disagrees.
 * [live] given = no probe (the page test).
 */
@Composable
fun FleetIdentityPage(registry: FleetIdentity.Registry? = null, live: Map<String, FleetIdentity.Seen>? = null) {
    val ctx = LocalContext.current
    val reg = remember { registry ?: FleetIdentity.baked() }
    var seen by remember { mutableStateOf(live ?: emptyMap()) }
    LaunchedEffect(reg) {
        if (live == null) { val app = ctx.applicationContext; thread(name = "fleet-identity-probe") { seen = FleetIdentity.probe(app, reg) } }
    }
    val rows = FleetIdentity.rows(reg, seen)
    val p = LocalKitPalette.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(KitDensity.small)) {
        KitSectionHeader("Identity", "Which Linux user each fleet app runs as. Own uid unless declared in a shared group (android:sharedUserId).")
        Text(FleetIdentity.summary(rows), color = p.textSecondary, fontSize = KitDensity.caption,
            modifier = Modifier.testTag(FleetIdentity.TAG_SUMMARY))
        for (g in reg.groups) KitCard(Modifier.testTag(FleetIdentity.TAG_GROUP + g.id)) {
            Text("${g.label} · ${g.sharedUserId ?: "pending: each on its own uid today"}", color = p.textPrimary, fontSize = KitDensity.title)
            Text("shared by " + g.members.joinToString(", "), color = p.textSecondary, fontSize = KitDensity.caption)
        }
        for (r in rows) {
            val s = r.seen
            val secondary = listOfNotNull(
                r.member.pkg,
                r.declared,
                r.sharesWith.takeIf { it.isNotEmpty() }?.let { "shares with " + it.joinToString() },
                s?.let { "uid ${it.uid}" },
                r.problem,
            ).joinToString(" · ")
            val pill = when {
                s == null -> "not installed" to KitState.IDLE
                r.problem != null -> "mismatch" to KitState.BAD
                else -> "ok" to KitState.OK
            }
            val signer: (@Composable () -> Unit)? = s?.signer?.let { fp -> @Composable { KitFingerprint(fp, tag = "${r.member.id}:signer") } }
            KitListRow(r.member.label, secondary = secondary, pill = pill, tag = r.tag, trailing = signer)
        }
    }
}

/** The host for a fragment-paged app (SuperApp > Configs > Network > Apps Mesh > Identity). */
class FleetIdentityFragment : KitComposeFragment() {
    override fun palette(): KitPalette = StoreSearchPage.palette(requireContext())

    @Composable
    override fun Content() {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(KitDensity.medium)) { FleetIdentityPage() }
    }
}
