package com.diegonmarcos.superapp.devcontrol

import android.content.Context
import com.diegonmarcos.superapp.devtools.FleetIpc
import com.diegonmarcos.superapp.devtools.FleetIpcWire
import com.diegonmarcos.superapp.devtools.FleetPeers
import com.diegonmarcos.superapp.updater.Fleet
import org.json.JSONArray
import org.json.JSONObject

/**
 * #ipc THE MESH GATEWAY (a0_docs/eng-specs/fleet-ipc.md): one door per phone. The SuperApp's
 * DevControlServer (127.0.0.1:38080, the fleet token) is the only fleet app that listens; a
 * caller OFF the phone (a PC, the MCP, an agent, CI — over ssh, or the mesh) asks
 *
 *   GET|POST /app/<id|pkg>/api/<op>[?query]   one app's route, forwarded over the fleet IPC layer
 *   GET      /api/fleet/apps                  what is behind the door
 *
 * and the gateway asks that app over [FleetIpc]: a signature-guarded provider call that starts
 * the app when it is not running. A member still on a build from before the layer is asked on
 * its old loopback port (the version-skew rung, devtools' legacy table). Every /app/ request
 * needs the fleet token, ping included: the open ping was there to map a port to its app, and
 * there are no per-app ports left to map.
 *
 * Trade-off, by design: with no SuperApp on the phone, remote access to the other apps stops;
 * the apps still talk to each other on the phone, because that never went through here.
 */
object MeshGateway {

    const val PREFIX = "/app/"
    /** Body a forwarded request may carry: an Account vault import is ~2 MB (Op.maxBody). */
    const val MAX_BODY = 4 * 1024 * 1024
    const val TIMEOUT_MS = 30_000L

    /** "/app/<who>/api/<op>" (or "/app/<who>/<op>") -> (who, op); null for anything else. */
    fun split(path: String): Pair<String, String>? {
        if (!path.startsWith(PREFIX)) return null
        val rest = path.removePrefix(PREFIX)
        val who = rest.substringBefore('/').trim()
        val op = rest.substringAfter('/', "").removePrefix("api/").trim('/')
        return if (who.isEmpty() || op.isEmpty()) null else who to op
    }

    class Member(val id: String, val pkg: String, val label: String)

    /** [who] is a fleet id, an applicationId or a label, case-insensitive; null = not in the fleet. */
    fun resolve(roster: List<Member>, who: String): Member? {
        val w = who.lowercase()
        return roster.firstOrNull { it.pkg.lowercase() == w } ?: roster.firstOrNull { it.id.lowercase() == w }
            ?: roster.firstOrNull { it.label.lowercase() == w }
    }

    fun roster(): List<Member> = runCatching {
        Fleet.parse(com.diegonmarcos.superapp.appstore.BuildConfig.CONSTELLATION_FLEET_B64)
            .filter { it.pkg.isNotEmpty() }.map { Member(it.id, it.pkg, it.label) }
    }.getOrDefault(emptyList())

    /**
     * The gateway's whole answer for one request, or null when [path] is not the gateway's.
     * [authed] = the request carried the fleet token. Blocking (it waits on the app).
     */
    fun handle(ctx: Context, path: String, query: Map<String, String>, body: String?, authed: Boolean,
               roster: List<Member> = roster(),
               ask: (pkg: String, op: String, q: Map<String, String>, body: String?) -> FleetIpcWire.Reply = { p, o, q, b ->
                   FleetIpc.call(ctx, p, o, q, TIMEOUT_MS, httpFallback = true, body = b)
               }): FleetIpcWire.Reply? {
        val isApps = path.trimEnd('/') in setOf("/api/fleet/apps", "/fleet/apps")
        val target = split(path)
        if (!isApps && target == null && !path.startsWith(PREFIX)) return null
        if (!authed) return FleetIpcWire.Reply(401, "unauthorized — Bearer <fleet token>\n")
        if (isApps) return FleetIpcWire.Reply(200, appsJson(ctx, roster).toString(), "application/json")
        val (who, op) = target ?: return FleetIpcWire.Reply(400, "expected /app/<id|pkg>/api/<op>\n")
        val m = resolve(roster, who)
            ?: return FleetIpcWire.Reply(404, "no fleet app \"$who\" — GET /api/fleet/apps lists them\n")
        if ((body?.length ?: 0) > MAX_BODY) return FleetIpcWire.Reply(413, "body over $MAX_BODY bytes\n")
        return ask(m.pkg, op, query, body)
    }

    /** What is behind the door: every fleet member installed on this phone, and how it is reached. */
    fun appsJson(ctx: Context, roster: List<Member>): JSONObject {
        val installed = FleetPeers.list(ctx).toSet()
        val arr = JSONArray()
        for (m in roster.filter { it.pkg in installed }) arr.put(JSONObject().put("id", m.id).put("pkg", m.pkg)
            .put("label", m.label).put("door", FleetIpc.hasDoor(ctx, m.pkg)).put("route", "/app/${m.pkg}/api/"))
        return JSONObject().put("gateway", "http://127.0.0.1:${FleetIpc.GATEWAY_PORT}")
            .put("route", "/app/<id|pkg>/api/<op>").put("auth", "Bearer <fleet token>")
            .put("count", arr.length()).put("apps", arr)
    }
}
