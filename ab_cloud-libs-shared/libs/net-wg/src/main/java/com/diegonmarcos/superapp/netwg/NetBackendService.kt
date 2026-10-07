package com.diegonmarcos.superapp.netwg

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.diegonmarcos.superapp.net.INetBackend
import com.wireguard.android.backend.BackendException
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config

/**
 * The far side of [INetBackend]: the only process on the device that holds
 * a GoBackend, and therefore the only APK carrying libwg-go.so.
 *
 * Every app that wants a tunnel binds here instead of linking the engine.
 * The tunnel object is OURS - the caller's Tunnel cannot cross the binder,
 * so callers address tunnels by name and AidlBackend delivers the state
 * callback locally.
 *
 * #751 A DNS CHOICE OUTLIVES THE MESH. Android has one
 * VPN slot and this engine owns it, so the fleet's DNS choice can only reach
 * the phone through a tunnel here. While the mesh is up its own config carries
 * the choice; when no tunnel is up the engine raises the IDLE tunnel a client
 * registered with setIdleTunnel — peerless, so it routes nothing and only puts
 * the chosen DNS servers on the VPN — and GoBackend's one-tunnel rule drops it
 * again the moment any tunnel comes UP. It is raised only when the slot is
 * already ours to give — a tunnel of ours just went down — or when the client
 * says so: another app's VPN (the SuperApp's firewall) holds the slot as well,
 * and only the client knows whether that one should win.
 */
class NetBackendService : Service() {

    /** One tunnel per name. GoBackend tracks the active tunnel by identity,
     *  so handing it a fresh object per call would make it report empty
     *  statistics for a tunnel it is in fact running. */
    private val tunnels = HashMap<String, NamedTunnel>()

    private class NamedTunnel(private val n: String) : Tunnel {
        @Volatile var state: Tunnel.State = Tunnel.State.DOWN
        override fun getName(): String = n
        override fun onStateChange(newState: Tunnel.State) { state = newState }
    }

    private val backend by lazy { GoBackend(applicationContext) }

    private fun tunnelFor(name: String): NamedTunnel =
        synchronized(tunnels) { tunnels.getOrPut(name) { NamedTunnel(name) } }

    /** Serialises every slot change: binder calls arrive on several threads. */
    private val slot = Any()
    private val idlePrefs by lazy { getSharedPreferences("idle_tunnel", MODE_PRIVATE) }
    private val idleName get() = idlePrefs.getString(KEY_NAME, "").orEmpty()
    private val idleConfig get() = idlePrefs.getString(KEY_CONFIG, "").orEmpty()
    private var idleApplied: String? = null
    private var idleError: String? = null

    /** A tunnel other than the idle one (and than [leaving]) holds the slot. */
    private fun otherTunnelUp(leaving: String? = null): Boolean =
        synchronized(tunnels) { tunnels.values.toList() }
            .any { it.getName() != idleName && it.getName() != leaving && backend.getState(it) == Tunnel.State.UP }

    /**
     * Raise, re-raise or drop the idle tunnel to match what is stored. With
     * [leaving] (a tunnel of ours on its way down) raising it is ONE GoBackend
     * switch, never a DOWN followed by an UP: the DOWN alone would stop the
     * VpnService the UP is about to reuse.
     */
    private fun reconcileIdle(leaving: String? = null) = synchronized(slot) {
        val name = idleName; val text = idleConfig
        if (name.isEmpty() || otherTunnelUp(leaving)) return@synchronized
        val idle = tunnelFor(name)
        if (text.isNotBlank() && text == idleApplied && backend.getState(idle) == Tunnel.State.UP) return@synchronized
        idleError = runCatching {
            if (text.isBlank()) backend.setState(idle, Tunnel.State.DOWN, null)
            else backend.setState(idle, Tunnel.State.UP, Config.parse(text.byteInputStream().bufferedReader()))
            idleApplied = text.ifBlank { null }
        }.exceptionOrNull()?.let {
            Log.w(TAG, "idle tunnel $name not raised", it)
            (it as? BackendException)?.reason?.name ?: it.message ?: it.javaClass.simpleName
        }
    }

    private fun idleStatus(): String = synchronized(slot) {
        when {
            idleName.isEmpty() || idleConfig.isBlank() -> "OFF"
            otherTunnelUp() -> "STANDBY"
            backend.getState(tunnelFor(idleName)) == Tunnel.State.UP -> "UP"
            else -> "DOWN: " + (idleError ?: "another VPN took the slot")
        }
    }

    private val binder = object : INetBackend.Stub() {

        override fun getState(tunnelName: String?): String =
            runCatching { backend.getState(tunnelFor(tunnelName.orEmpty())).name }
                .getOrElse { Tunnel.State.DOWN.name }

        override fun setState(tunnelName: String?, state: String?, wgQuickConfig: String?): String {
            val tunnel = tunnelFor(tunnelName.orEmpty())
            val want = runCatching { Tunnel.State.valueOf(state.orEmpty()) }
                .getOrDefault(Tunnel.State.DOWN)
            // A null config is legal going DOWN and fatal going UP; let the
            // parse error surface rather than silently leaving it down.
            val cfg: Config? = wgQuickConfig?.takeIf { it.isNotBlank() }
                ?.let { Config.parse(it.byteInputStream().bufferedReader()) }
            return synchronized(slot) {
                val wasUp = backend.getState(tunnel) == Tunnel.State.UP
                // #751 a tunnel of ours leaving the slot hands it to the idle
                // tunnel, as one switch. A DOWN for a tunnel that was not up hands
                // nothing over: the slot may be another app's VPN by now.
                if (wasUp && want == Tunnel.State.DOWN && tunnel.getName() != idleName) reconcileIdle(leaving = tunnel.getName())
                val result = runCatching { backend.setState(tunnel, want, cfg).name }
                    .onFailure { Log.w(TAG, "setState($tunnelName, $want) failed", it) }
                    .getOrElse { Tunnel.State.DOWN.name }
                // A failed UP that left the slot empty: same hand-over.
                if (wasUp && result != Tunnel.State.UP.name) reconcileIdle()
                result
            }
        }

        override fun getStatisticsRaw(tunnelName: String?): String {
            // Statistics is not parcelable and we do not want an AIDL type
            // that has to track upstream WireGuard; the raw text round-trips
            // through the one parser both sides share.
            val t = tunnelFor(tunnelName.orEmpty())
            return runCatching { backend.getStatisticsRaw(t) }.getOrDefault("")
        }

        override fun getVersion(): String =
            runCatching { backend.version }.getOrElse { "unknown" }

        override fun isAlwaysOn(): Boolean =
            runCatching { backend.isAlwaysOn }.getOrDefault(false)

        override fun isLockdownEnabled(): Boolean =
            runCatching { backend.isLockdownEnabled }.getOrDefault(false)

        override fun setIdleTunnel(tunnelName: String?, wgQuickConfig: String?, raiseNow: Boolean): String {
            synchronized(slot) {
                val old = idleName
                val name = tunnelName.orEmpty()
                // A renamed idle tunnel must not be left up under its old name.
                if (old.isNotEmpty() && old != name) {
                    runCatching { backend.setState(tunnelFor(old), Tunnel.State.DOWN, null) }
                }
                idlePrefs.edit().putString(KEY_NAME, name).putString(KEY_CONFIG, wgQuickConfig.orEmpty()).commit()
                if (raiseNow || wgQuickConfig.isNullOrBlank()) reconcileIdle()
            }
            return idleStatus()
        }

        override fun getIdleStatus(): String = idleStatus()

        override fun methods(): Array<String> = methodNames()
    }

    /** The wire INetBackend declares, by name (the engine tester holds this list to the AIDL file). */
    fun methodNames(): Array<String> = arrayOf(
        "getState", "setState", "getStatisticsRaw", "getVersion", "isAlwaysOn", "isLockdownEnabled",
        "setIdleTunnel", "getIdleStatus")

    override fun onBind(intent: Intent?): IBinder = binder

    private companion object {
        const val TAG = "NetBackendService"
        const val KEY_NAME = "name"
        const val KEY_CONFIG = "config"
    }
}
