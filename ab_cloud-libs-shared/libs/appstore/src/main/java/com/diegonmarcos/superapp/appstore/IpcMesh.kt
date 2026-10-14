package com.diegonmarcos.superapp.appstore

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Base64
import com.diegonmarcos.superapp.devtools.FleetIpc
import org.json.JSONArray
import org.json.JSONObject

/**
 * #ipc IPC MESH — the model behind the IPC page (Cloud Store > Access > IPC and SuperApp >
 * Configs > Network > IPC, both drawing [IpcMeshPage]; a0_docs/eng-specs/fleet-ipc.md).
 *
 * The data is the GENERATED registry aa_cloud-superapp/data/ipc-mesh.json
 * (1_cicd/src/scripts/cloud-android-ipc-mesh.py, baked here as IPC_MESH_B64): per fleet member
 * the IPC endpoints it exposes — the fleet IPC layer's ops on content://<pkg>.fleet, the
 * signature-guarded providers, bound services and their AIDL methods, intent actions and deep
 * links — the permission protecting each, and the members whose code calls it. This file names
 * no member and no endpoint; a new one appears by being declared.
 *
 * The LIVE half ([probe]) only reads the PackageManager — installed, version, does each
 * endpoint resolve — so it starts nothing. Whether a member's door RESPONDS is [ping], one
 * read-only `system/ping` over the IPC layer, which starts a stopped member: the page asks it
 * when Ping is tapped, the way Apps Mesh's Wake does.
 */
object IpcMesh {

    const val TAG_MEMBER = "ipc-mesh-member:"
    /** One endpoint row: "$TAG_ENDPOINT<member id>|<endpoint id>". */
    const val TAG_ENDPOINT = "ipc-mesh-endpoint:"
    const val TAG_FILTER = "ipc-mesh-filter:"
    const val TAG_SUMMARY = "ipc-mesh-summary"
    const val PKG = "<pkg>"

    class Aidl(val iface: String, val methods: List<String>)

    class Endpoint(
        val id: String, val kind: String, val name: String,
        /** what a caller names: content://authority, an action, a URI, or "<op> on content://<pkg>.fleet" */
        val address: String,
        val permission: String?, val level: String, val callers: List<String>,
        val aidl: List<Aidl>, val source: String,
        /** the raw parts [resolves] needs */
        val authority: String?, val actions: List<String>, val uris: List<String>,
    )

    class Member(val id: String, val pkg: String, val label: String, val kind: String,
                 val door: Boolean, val endpoints: List<Endpoint>)

    class Registry(val members: List<Member>, val permission: String, val method: String) {
        val count: Int get() = members.sumOf { it.endpoints.size }
    }

    /** The registry this build baked. */
    fun baked(): Registry = parse(String(Base64.decode(BuildConfig.IPC_MESH_B64, Base64.DEFAULT)))

    /** A member's endpoints are its own plus every shared lib's it links, with <pkg> = its package. */
    fun parse(json: String): Registry {
        if (json.isBlank()) return Registry(emptyList(), "", "")
        val o = JSONObject(json)
        val shared = o.optJSONObject("shared") ?: JSONObject()
        val method = o.optJSONObject("door")?.optString("method").orEmpty()
        val members = o.optJSONArray("members").objs().map { m ->
            val pkg = m.getString("pkg")
            val own = m.optJSONArray("endpoints").objs()
            val libs = m.optJSONArray("libs").strs().flatMap { shared.optJSONArray(it).objs() }
            Member(m.getString("id"), pkg, m.optString("label", m.getString("id")), m.optString("kind", "app"),
                m.optBoolean("door"), (own + libs).map { endpoint(it, pkg, method) }.distinctBy { it.id })
        }
        return Registry(members, o.optJSONObject("permission")?.optString("name").orEmpty(), method)
    }

    private fun endpoint(e: JSONObject, pkg: String, method: String): Endpoint {
        val sub = { s: String -> s.replace(PKG, pkg) }
        val kind = e.getString("kind")
        val authority = e.optString("authority").takeIf { it.isNotEmpty() }?.let(sub)
        val actions = e.optJSONArray("actions").strs().map(sub)
        val uris = e.optJSONArray("uris").strs().map(sub)
        val address = when (kind) {
            "op" -> "${e.getString("name")} · content://$pkg.fleet call $method"
            "provider" -> "content://$authority"
            "deeplink" -> uris.joinToString(" · ")
            else -> actions.joinToString(" · ").ifEmpty { sub(e.optString("component")) }
        }
        return Endpoint(sub(e.getString("id")), kind, e.optString("name"), address,
            e.optString("permission").takeIf { it.isNotEmpty() && it != "null" }, e.optString("level", "none"),
            e.optJSONArray("callers").strs(),
            e.optJSONArray("aidl").objs().map { Aidl(it.getString("interface"), it.optJSONArray("methods").strs()) },
            e.optString("source"), authority, actions, uris)
    }

    private fun JSONArray?.objs(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
    private fun JSONArray?.strs(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }

    // ── live ────────────────────────────────────────────────────────────────

    /** What this phone says, as plain data. [doors] are filled by [ping] only. */
    class Live(
        /** member id -> installed versionName ("" when it has none); absent = not installed. */
        val installed: Map<String, String>,
        /** "<member id>|<endpoint id>" -> does it resolve on this phone. */
        val resolves: Map<String, Boolean>,
        /** member id -> the door's answer: null not asked, else (ok, ms, note). */
        val doors: Map<String, Door> = emptyMap(),
    )

    class Door(val ok: Boolean, val ms: Long, val note: String)

    fun key(m: Member, e: Endpoint) = "${m.id}|${e.id}"

    /** PackageManager only: starts no process. Blocking; off the main thread. */
    fun probe(ctx: Context, reg: Registry, doors: Map<String, Door> = emptyMap()): Live {
        val pm = ctx.packageManager
        val installed = HashMap<String, String>()
        val resolves = HashMap<String, Boolean>()
        for (m in reg.members) {
            val v = runCatching { pm.getPackageInfo(m.pkg, 0).versionName ?: "" }.getOrNull() ?: continue
            installed[m.id] = v
            for (e in m.endpoints) resolves[key(m, e)] = resolves(ctx, m, e)
        }
        return Live(installed, resolves, doors)
    }

    @Suppress("DEPRECATION")
    fun resolves(ctx: Context, m: Member, e: Endpoint): Boolean = runCatching {
        val pm = ctx.packageManager
        fun i(a: String) = Intent(a).setPackage(m.pkg)
        when (e.kind) {
            "op" -> FleetIpc.hasDoor(ctx, m.pkg)
            "provider" -> e.authority!!.split(";").all { pm.resolveContentProvider(it, 0) != null }
            "service" -> e.actions.any { pm.queryIntentServices(i(it), 0).isNotEmpty() }
            "receiver" -> e.actions.any { pm.queryBroadcastReceivers(i(it), 0).isNotEmpty() }
            "deeplink" -> e.uris.any { pm.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse(it)).setPackage(m.pkg), 0).isNotEmpty() }
            else -> e.actions.any { pm.queryIntentActivities(i(it), 0).isNotEmpty() }
        }
    }.getOrDefault(false)

    /** The door's one read-only question (system/ping over IPC). Starts a stopped member. */
    fun ping(ctx: Context, m: Member): Door {
        val t0 = System.currentTimeMillis()
        val r = FleetIpc.ping(ctx, m.pkg, timeoutMs = PING_TIMEOUT_MS)
        val ms = System.currentTimeMillis() - t0
        val ok = r.ok && r.body.trim() == "pong ${m.pkg}"
        return Door(ok, ms, if (ok) "answers over ${r.via}" else "${r.status} ${r.note}".trim())
    }

    const val PING_TIMEOUT_MS = 6_000L

    // ── what the page draws ────────────────────────────────────────────────

    /** The page's filters, in order; [matches] implements each. */
    val FILTERS = listOf("all", "installed", "answers", "missing")

    fun matches(filter: String, m: Member, live: Live?): Boolean = when (filter) {
        "installed" -> live != null && m.id in live.installed
        "answers" -> live?.doors?.get(m.id)?.ok == true
        "missing" -> live != null && m.id !in live.installed
        else -> true
    }

    /** One drawn line: a member header, or one of its endpoints. */
    class Row(val tag: String, val member: Member, val endpoint: Endpoint?)

    /**
     * EXACTLY what the page draws, in order: each member that passes [filter] (in [order]),
     * and, when it is in [expanded] (or [expandAll]), every endpoint it declares. The page
     * renders these rows and nothing else, so IpcMeshTest proves "every declared endpoint is
     * listed" on this function and on the composed page alike.
     */
    fun rows(reg: Registry, live: Live?, filter: String, expanded: Set<String>, expandAll: Boolean = false,
             order: List<String> = emptyList()): List<Row> {
        val rank = order.withIndex().associate { (i, id) -> id to i }
        return reg.members.sortedBy { rank[it.id] ?: Int.MAX_VALUE }.filter { matches(filter, it, live) }.flatMap { m ->
            listOf(Row(TAG_MEMBER + m.id, m, null)) +
                if (expandAll || m.id in expanded) m.endpoints.map { Row(TAG_ENDPOINT + key(m, it), m, it) } else emptyList()
        }
    }

    fun summary(reg: Registry, live: Live?): String {
        val n = reg.members.size
        if (live == null) return "$n members · ${reg.count} IPC endpoints · probing…"
        val res = live.resolves.values.count { it }
        val doors = live.doors.values.count { it.ok }
        return "$n members · ${live.installed.size} installed · ${reg.count} IPC endpoints, $res resolve here" +
            if (live.doors.isEmpty()) " · doors not asked (Ping asks)" else " · $doors/${live.doors.size} doors answer"
    }

    /** One endpoint, in words: kind, address, guard, callers, live. */
    fun line(e: Endpoint, resolves: Boolean?): String = buildString {
        append(when (e.kind) { "op" -> "op"; "provider" -> "provider"; "service" -> "service"; "receiver" -> "receiver"
            "deeplink" -> "deep link"; else -> "intent" })
        append("  ").append(e.address)
        e.aidl.forEach { a -> append("\n    AIDL ").append(a.iface).append(": ").append(a.methods.joinToString(", ")) }
        append("\n    ").append(if (e.permission != null) "${e.permission.substringAfterLast('.')} (${e.level})" else "no permission (${e.level})")
        append(" · called by ").append(e.callers.ifEmpty { listOf("no fleet member") }.joinToString(", "))
        if (resolves != null) append(if (resolves) " · ✓ resolves" else " · ✕ does not resolve")
    }

    /** The whole page as text, for Copy. */
    fun report(reg: Registry, live: Live?): String = buildString {
        appendLine("IPC Mesh — ${summary(reg, live)}")
        for (m in reg.members) {
            appendLine("## ${m.label} (${m.pkg})" + (live?.installed?.get(m.id)?.let { " · $it" } ?: " · not installed"))
            for (e in m.endpoints) appendLine("  " + line(e, live?.resolves?.get(key(m, e))).replace("\n", "\n  "))
        }
    }

    /** Fleet groups, in declared order, for the page's ordering (same source as Apps Mesh). */
    fun order(): List<String> = runCatching {
        val o = JSONObject(String(Base64.decode(BuildConfig.CONSTELLATION_FLEET_B64, Base64.DEFAULT)))
        o.optJSONArray("groups").objs().flatMap { it.optJSONArray("members").strs() }.distinct() +
            o.optJSONArray("apps").objs().map { it.getString("id") }
    }.getOrDefault(emptyList()).distinct()
}
