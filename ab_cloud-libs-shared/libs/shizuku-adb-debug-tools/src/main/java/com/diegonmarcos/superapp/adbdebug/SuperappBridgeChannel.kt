package com.diegonmarcos.superapp.adbdebug

import android.content.Context
import com.diegonmarcos.superapp.devtools.FleetToken
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Shell channel backed by the fleet SuperApp's loopback DevControlServer route
 * (build.json::shizuku_client.superapp_bridge, default
 * 127.0.0.1:38080/api/adb/exec). The SuperApp already holds adb-shell (uid
 * 2000) privilege — it ships its own Shizuku-compatible provider and is already
 * granted in Shizuku's list — and that route runs `sh -c <cmd>` through its own
 * [ShellChannels] ladder. This is how a terminal reaches uid 2000 WITHOUT
 * running app_process itself: it delegates to the one app that can.
 *
 * It authenticates with the fleet token ([FleetToken]) over loopback, the same
 * secret the SuperApp mints and every signed member adopts. Enabled only when
 * the app's shizuku_client block names "com.diegonmarcos.superapp" as a
 * provider — pure data, no hardcoded identity here.
 */
object SuperappBridgeChannel : ShellChannel {

    private const val SUPERAPP_PROVIDER = "com.diegonmarcos.superapp"

    private fun enabled(): Boolean = RishBridge.providers.contains(SUPERAPP_PROVIDER)

    override fun name(): String = "superapp-bridge"

    override fun isReady(ctx: Context): Boolean = enabled() && probeOpen()

    private fun probeOpen(): Boolean = runCatching {
        val u = URL(RishBridge.bridgeUrl())
        (u.openConnection() as HttpURLConnection).apply {
            connectTimeout = 400; readTimeout = 400; requestMethod = "GET"
        }.let { c -> runCatching { c.connect() }.isSuccess.also { c.disconnect() } }
    }.getOrDefault(false)

    override fun exec(ctx: Context, command: String): String? {
        if (!enabled()) return null
        return runCatching {
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
    }

    override fun status(ctx: Context): String = when {
        !enabled() -> "SuperApp bridge not a declared provider"
        isReady(ctx) -> "Ready — SuperApp DevControlServer on ${RishBridge.bridgeUrl()} (shell domain, Bearer fleet token)"
        else -> "SuperApp not reachable on ${RishBridge.bridgeUrl()} — open the SuperApp once"
    }
}
