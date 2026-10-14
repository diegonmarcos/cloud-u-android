package com.diegonmarcos.superapp.adbdebug

import android.content.Context
import com.diegonmarcos.superapp.devtools.FleetIpc
import com.diegonmarcos.superapp.devtools.FleetIpcWire
import com.diegonmarcos.superapp.devtools.FleetToken
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Shell channel backed by the fleet SuperApp's privileged shell. The SuperApp already holds
 * adb-shell (uid 2000) privilege — it ships its own Shizuku-compatible provider and is
 * already granted in Shizuku's list — and runs `sh -c <cmd>` through its own
 * [ShellChannels] ladder. This is how a terminal reaches uid 2000 WITHOUT running
 * app_process itself: it delegates to the one app that can.
 *
 * #ipc asked over the fleet IPC layer ([FleetIpc], op `adb/exec`, the SuperApp's
 * AdbBridgeApi): a signature-guarded provider call on `com.diegonmarcos.superapp.fleet`,
 * which also starts a stopped SuperApp. Only a SuperApp from before that route (it answers
 * the call with nothing, or 404) is asked the old way: its loopback DevControlServer route
 * (build.json::shizuku_client.superapp_bridge, 127.0.0.1:38080/api/adb/exec) with the
 * fleet token. Enabled only when the app's shizuku_client block names
 * "com.diegonmarcos.superapp" as a provider — pure data, no hardcoded identity here.
 */
object SuperappBridgeChannel : ShellChannel {

    private const val SUPERAPP_PROVIDER = "com.diegonmarcos.superapp"

    private fun enabled(ctx: Context): Boolean =
        RishBridge.providers.contains(SUPERAPP_PROVIDER) && ctx.packageName != SUPERAPP_PROVIDER

    override fun name(): String = "superapp-bridge"

    override fun isReady(ctx: Context): Boolean = enabled(ctx) && (FleetIpc.hasDoor(ctx, SUPERAPP_PROVIDER) || probeOpen())

    /** The pre-IPC SuperApp's loopback door (version skew only). */
    private fun probeOpen(): Boolean = runCatching {
        val u = URL(RishBridge.bridgeUrl())
        (u.openConnection() as HttpURLConnection).apply {
            connectTimeout = 400; readTimeout = 400; requestMethod = "GET"
        }.let { c -> runCatching { c.connect() }.isSuccess.also { c.disconnect() } }
    }.getOrDefault(false)

    override fun exec(ctx: Context, command: String): String? {
        if (!enabled(ctx)) return null
        val r = FleetIpc.call(ctx, SUPERAPP_PROVIDER, "adb/exec", mapOf("cmd" to command),
            timeoutMs = EXEC_TIMEOUT_MS, httpFallback = false)
        if (r.ok) return runCatching { JSONObject(r.body).optString("out") }.getOrNull()
        if (r.via != FleetIpcWire.Via.NONE && r.status != 404) return null
        return legacyExec(ctx, command)
    }

    private const val EXEC_TIMEOUT_MS = 20_000L

    /** Version skew: a SuperApp with no IPC `adb/exec` is asked on its loopback route. */
    private fun legacyExec(ctx: Context, command: String): String? = runCatching {
        val enc = URLEncoder.encode(command, "UTF-8")
        val u = URL("${RishBridge.bridgeUrl()}?${RishBridge.bridgeCmdParam()}=$enc")
        val c = (u.openConnection() as HttpURLConnection).apply {
            connectTimeout = 1000; readTimeout = 15_000; requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer ${FleetToken.get(ctx)}")
        }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        stream?.bufferedReader()?.readText().also { c.disconnect() }
    }.getOrNull()

    override fun status(ctx: Context): String = when {
        !enabled(ctx) -> "SuperApp bridge not a declared provider"
        FleetIpc.hasDoor(ctx, SUPERAPP_PROVIDER) -> "Ready — SuperApp over the fleet IPC layer (adb/exec, signature-guarded)"
        isReady(ctx) -> "Ready — older SuperApp, DevControlServer on ${RishBridge.bridgeUrl()} (Bearer fleet token)"
        else -> "SuperApp not installed (no fleet IPC door, nothing on ${RishBridge.bridgeUrl()})"
    }
}
