package com.diegonmarcos.superapp.devcontrol

import android.content.Context
import com.diegonmarcos.superapp.adbdebug.LocalShellChannel
import com.diegonmarcos.superapp.adbdebug.ShellChannels
import com.diegonmarcos.superapp.devtools.AppDebugServer
import org.json.JSONObject

/**
 * #ipc the SuperApp bridge over the fleet IPC layer: a terminal (or Cloud Drive's Manage ▸
 * Apps) runs a uid-2000 shell command through the SuperApp's privileged channel with
 * `FleetIpc.call(ctx, "com.diegonmarcos.superapp", "adb/exec", cmd=…)` — a signature-guarded
 * provider call, no loopback socket and no token (libs:shizuku-adb-debug-tools
 * SuperappBridgeChannel is the client). The same route stays reachable from outside the
 * phone through the debug API (fleet token). DevControlServer's own /api/adb/exec on 38080
 * is the external door that predates this one and is kept for curl and the MCP.
 */
object AdbBridgeApi {
    fun register(ctx: Context) {
        val app = ctx.applicationContext
        AppDebugServer.route("adb", listOf(
            AppDebugServer.Op("exec", "cmd=<shell command>",
                "run `sh -c <cmd>` as uid 2000 through the SuperApp's active shell channel: {ok, channel, out}"),
        )) { op, q -> if (op == "exec") exec(app, q["cmd"].orEmpty()).toString() else null }
    }

    fun exec(ctx: Context, cmd: String): JSONObject {
        if (cmd.isBlank()) return JSONObject().put("ok", false).put("out", "missing cmd")
        val ch = ShellChannels.active(ctx)
            ?: return JSONObject().put("ok", false).put("channel", "none")
                .put("out", "ERR: no shell channel ready — ${LocalShellChannel.status(ctx)}\n")
        val out = ch.exec(ctx, cmd)
        return JSONObject().put("ok", out != null).put("channel", ch.name()).put("out", out ?: "")
    }
}
