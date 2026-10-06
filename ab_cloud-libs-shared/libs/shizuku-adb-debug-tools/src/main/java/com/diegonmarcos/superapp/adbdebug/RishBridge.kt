package com.diegonmarcos.superapp.adbdebug

import android.content.Context
import android.util.Base64
import com.diegonmarcos.superapp.devtools.FleetToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Makes this app a Shizuku client and puts `rish` inside its proot rootfs —
 * entirely from ONE data block, build.json::shizuku_client, baked into
 * [BuildConfig.SHIZUKU_CLIENT_B64] by this lib's build.gradle. No per-app
 * Kotlin, no hardcoded package name or path: a third terminal is one build.json
 * block and nothing here changes (Pillar: DATA-DRIVEN).
 *
 * Why a BRIDGE and not Shizuku's stock rish: Shizuku's rish execs `app_process`
 * with rish_shizuku.dex on the Android side. That cannot run inside this proot —
 * the login binds /dev /proc /sys and storage but NOT /system, /apex or
 * /linkerconfig, so there is no app_process and no ART to host the dex. Instead
 * rish forwards the command over loopback to a provider that already holds
 * adb-shell (uid 2000) privilege and runs `sh -c <cmd>` there. The providers,
 * in [providers] order, are tried by the runtime shell ladder ([ShellChannels])
 * and by rish alike; today the reachable-from-proot one is the SuperApp
 * ([superappBridge]). proot shares the host network namespace, so 127.0.0.1
 * inside the rootfs is the device loopback and no networking bind is needed —
 * only the fleet token has to cross in, which [export] writes 0600 into the
 * session, never a secret in source.
 */
object RishBridge {

    private val cfg: JSONObject by lazy {
        runCatching {
            val raw = String(Base64.decode(BuildConfig.SHIZUKU_CLIENT_B64, Base64.DEFAULT))
            JSONObject(if (raw.isBlank()) "{}" else raw)
        }.getOrDefault(JSONObject())
    }

    /** Declared provider order; empty when this app ships no block. */
    val providers: List<String>
        get() = cfg.optJSONArray("providers").toStringList()

    /** True when this app opted into the Shizuku-client feature at all. */
    val enabled: Boolean get() = providers.isNotEmpty()

    /** True when the Shizuku app is a declared provider (so this app should
     *  request its permission and show up in Shizuku's app-management list). */
    val usesShizuku: Boolean get() = providers.contains("moe.shizuku.privileged.api")

    private val rish: JSONObject get() = cfg.optJSONObject("rish") ?: JSONObject()
    private val bridge: JSONObject get() = cfg.optJSONObject("superapp_bridge") ?: JSONObject()

    val applicationId: String get() = rish.optString("application_id")
    val tokenEnv: String get() = rish.optString("token_env", "CLOUD_FLEET_TOKEN")
    val envFileName: String get() = rish.optString("env_file", "rish.env")

    fun bridgeUrl(): String {
        val host = bridge.optString("host", "127.0.0.1")
        val port = bridge.optInt("port", 38080)
        val path = bridge.optString("exec_path", "/api/adb/exec")
        return "http://$host:$port$path"
    }
    fun bridgeCmdParam(): String = bridge.optString("cmd_param", "cmd")

    /** The enter-time bind list, each {stage (relative to the login dir), guest}. */
    fun binds(): List<Pair<String, String>> =
        cfg.optJSONArray("binds").let { arr ->
            (0 until (arr?.length() ?: 0)).mapNotNull {
                val o = arr!!.optJSONObject(it) ?: return@mapNotNull null
                val s = o.optString("stage"); val g = o.optString("guest")
                if (s.isBlank() || g.isBlank()) null else s to g
            }
        }

    /**
     * Request Shizuku permission once, if Shizuku is a declared provider. Safe
     * to call on every launch: no-op when the block omits Shizuku, when Shizuku
     * isn't running, or when already granted. Making this app call
     * Shizuku.requestPermission is what adds it to Shizuku's "Application
     * management" list — the user's one grant.
     */
    fun requestShizukuPermissionIfNeeded() {
        if (!usesShizuku) return
        if (ShizukuAdb.isAvailable() && !ShizukuAdb.isGranted()) ShizukuAdb.requestPermission(null)
    }

    /**
     * Write `rish` and its env file into [loginDir] (the directory the login
     * script lives in and binds from). The token is the fleet pre-shared key,
     * which this signed fleet member reads from [FleetToken]; it is written 0600
     * and only when it would change. No-op when the app ships no block.
     */
    fun export(ctx: Context, loginDir: File) {
        if (!enabled) return
        runCatching {
            val dir = File(loginDir, "rish")
            dir.mkdirs()
            writeIfChanged(File(dir, "rish"), RISH_SCRIPT.replace('§', '$'), "0755")
            val env = buildString {
                append("RISH_APPLICATION_ID=").append(applicationId).append('\n')
                append(tokenEnv).append('=').append(FleetToken.get(ctx)).append('\n')
                append("RISH_BRIDGE_URL=").append(bridgeUrl()).append('\n')
                append("RISH_BRIDGE_CMD_PARAM=").append(bridgeCmdParam()).append('\n')
                append("RISH_TOKEN_ENV=").append(tokenEnv).append('\n')
            }
            writeIfChanged(File(dir, envFileName), env, "0600")
        }
    }

    private fun writeIfChanged(f: File, body: String, mode: String) {
        if (f.isFile && f.readText() == body) return
        val tmp = File(f.parentFile, f.name + ".new")
        tmp.writeText(body)
        runCatching { android.system.Os.chmod(tmp.absolutePath, mode.toInt(8)) }
        android.system.Os.rename(tmp.absolutePath, f.absolutePath)
    }

    private fun JSONArray?.toStringList(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { optString(it) }.filter { it.isNotBlank() }

    // The launcher written into the rootfs. POSIX sh; no app id or URL baked —
    // every value comes from rish.env, which [export] renders from the block.
    // The sentinel U+00A7 stands in for '$' so Kotlin does not template the
    // shell variables; [export] swaps it back before writing.
    private const val RISH_SCRIPT = """#!/bin/sh
# rish — Shizuku-style shell bridge for the Cloud Terminal rootfs. GENERATED by
# RishBridge from build.json::shizuku_client; do not edit. Runs its argv as a
# shell command at adb-shell (uid 2000) privilege through the fleet provider's
# loopback exec route. With no argv it opens an interactive-ish passthrough.
set -eu
: "§{RISH_ENV:=/usr/local/etc/rish.env}"
[ -r "§RISH_ENV" ] || { echo "rish: §RISH_ENV missing — open a fresh session after granting Shizuku or the SuperApp" >&2; exit 1; }
. "§RISH_ENV"
[ "§#" -gt 0 ] || set -- "§{SHELL:-sh} -i"
cmd="§*"
enc=§(printf '%s' "§cmd" | jq -sRr @uri)
exec curl -fsS -H "Authorization: Bearer §{CLOUD_FLEET_TOKEN:-}" \
    "§RISH_BRIDGE_URL?§RISH_BRIDGE_CMD_PARAM=§enc"
"""
}
