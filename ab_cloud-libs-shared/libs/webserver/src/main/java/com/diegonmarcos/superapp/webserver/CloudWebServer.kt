package com.diegonmarcos.superapp.webserver

import android.content.Context
import java.net.NetworkInterface

/**
 * The one process-wide instance, so the About screen, a future foreground
 * service and the standalone Cloud WebServer APK all drive the SAME socket
 * rather than each opening a second one on the same port and reporting the
 * loser as "stopped".
 *
 * Every entry point takes a [Context] and re-reads [WebServerPrefs], so a
 * setting changed anywhere is in force at the next call — there is no cached
 * copy of the configuration to go stale.
 */
object CloudWebServer {

    @Volatile private var server: WebServer? = null

    /** The live engine, created from prefs on first use. */
    fun instance(context: Context): WebServer {
        server?.let { return it }
        return synchronized(this) {
            server ?: WebServer(WebServerPrefs(context).toConfig()).also { server = it }
        }
    }

    fun isRunning(context: Context): Boolean = instance(context).isRunning()

    fun stats(context: Context): WebServer.Stats = instance(context).stats()

    /** Start under the current prefs, seeding the document root so the first
     *  request returns a page instead of a 404. */
    fun start(context: Context) {
        val engine = instance(context)
        engine.reconfigure(WebServerPrefs(context).toConfig())
        engine.seedDocRoot()
        engine.start()
    }

    fun stop(context: Context) {
        instance(context).stop()
    }

    /** Re-read prefs and restart if it was running. Call after any setting
     *  change; a port edit that does not rebind is a lie on the status row. */
    fun refresh(context: Context) {
        instance(context).reconfigure(WebServerPrefs(context).toConfig())
    }

    /** Apply the persisted master switch. Safe to call at app start. */
    fun applyPreference(context: Context) {
        if (WebServerPrefs(context).enabled) start(context) else stop(context)
    }

    /**
     * The address to actually type into a browser on another machine.
     *
     * Loopback-only binds are unreachable from anywhere else, so they report
     * 127.0.0.1 honestly instead of a LAN address that would refuse the
     * connection. When bound wide, the first non-loopback IPv4 wins — on this
     * device that is normally wlan0, with the WireGuard 10.x as the fallback.
     */
    fun reachableUrl(context: Context): String {
        val prefs = WebServerPrefs(context)
        val host = if (prefs.loopbackOnly) "127.0.0.1" else (localIpv4() ?: "127.0.0.1")
        return "http://$host:${prefs.port}/"
    }

    fun localIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            // wlan0 before tun0: a LAN peer can reach the Wi-Fi address, and
            // only a mesh member can reach the 10.x one.
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
            ?.hostAddress
    }.getOrNull()
}
