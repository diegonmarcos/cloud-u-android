package com.diegonmarcos.superapp.net

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.Statistics
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [Backend] implemented against the engine in Cloud-Lib-Net-Wg.apk.
 *
 * The app used to compile GoBackend - and its 8.5MB libwg-go.so - into its
 * own APK. It now drives the same tunnel through [INetBackend], so the
 * native blob exists once on the device instead of once per app.
 *
 * Degrades the way the keyboard's AIDL clients do: with the engine APK
 * absent every call reports DOWN / empty stats rather than throwing, so a
 * device that has not installed it sees a VPN that will not connect, not a
 * crash. [isEngineInstalled] is what the UI should ask before offering the
 * switch.
 *
 * Threading: [bindBlocking] and every Backend method block, so call them
 * off the main thread - exactly as GoBackend already required.
 */
class AidlBackend(context: Context) : Backend {

    private val ctx = context.applicationContext
    @Volatile private var service: INetBackend? = null
    @Volatile private var latch: CountDownLatch? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = binder?.takeIf { it.pingBinder() }?.let { INetBackend.Stub.asInterface(it) }
            latch?.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    /** True when the engine APK is on the device at all. */
    fun isEngineInstalled(): Boolean = runCatching {
        ctx.packageManager.getPackageInfo(ENGINE_PKG, 0); true
    }.getOrDefault(false)

    /**
     * The consent Intent to start with startActivityForResult, or null when
     * consent is already granted or the engine is absent.
     *
     * VpnService.prepare() must be called by the package that OWNS the
     * service, which is now the engine APK - so the app cannot call it
     * directly any more. The engine exposes a tiny activity that calls it
     * and finishes; that activity is the app's entry point to consent.
     */
    fun consentIntent(): Intent? =
        if (!isEngineInstalled()) null
        else Intent().setClassName(ENGINE_PKG, CONSENT_ACTIVITY)

    @Synchronized
    fun bindBlocking(timeoutMs: Long = 4000): Boolean {
        if (service != null) return true
        if (!isEngineInstalled()) return false
        val l = CountDownLatch(1)
        latch = l
        val intent = Intent().setClassName(ENGINE_PKG, SERVICE)
        val started = runCatching {
            ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!started) return false
        runCatching { l.await(timeoutMs, TimeUnit.MILLISECONDS) }
        return service != null
    }

    private fun remote(): INetBackend? {
        if (service == null) bindBlocking()
        return service
    }

    override fun getState(tunnel: Tunnel): Tunnel.State {
        val name = runCatching { remote()?.getState(tunnel.name) }.getOrNull() ?: return Tunnel.State.DOWN
        return runCatching { Tunnel.State.valueOf(name) }.getOrDefault(Tunnel.State.DOWN)
    }

    override fun setState(tunnel: Tunnel, state: Tunnel.State, config: Config?): Tunnel.State {
        val r = remote() ?: throw IllegalStateException(
            "Cloud-Lib-Net-Wg is not installed - install it from Store ▸ Cloud Constellation ▸ Libs to use WireGuard")
        val name = r.setState(tunnel.name, state.name, config?.toWgQuickString())
        val resulting = runCatching { Tunnel.State.valueOf(name) }.getOrDefault(Tunnel.State.DOWN)
        // The caller's Tunnel is a local object the remote knows nothing
        // about, so deliver the callback the engine cannot.
        runCatching { tunnel.onStateChange(resulting) }
        return resulting
    }

    override fun getStatistics(tunnel: Tunnel): Statistics {
        val raw = runCatching { remote()?.getStatisticsRaw(tunnel.name) }.getOrNull()
        return Statistics.parse(raw)
    }

    override fun getVersion(): String =
        runCatching { remote()?.version }.getOrNull() ?: "unavailable"

    /**
     * Always-on and lockdown describe the system VPN profile bound to the
     * package that OWNS the service, so only the engine can answer. False when
     * it is absent, which is correct: no engine, no VPN profile.
     */
    override fun isAlwaysOn(): Boolean =
        runCatching { remote()?.isAlwaysOn }.getOrNull() ?: false

    override fun isLockdownEnabled(): Boolean =
        runCatching { remote()?.isLockdownEnabled }.getOrNull() ?: false

    override fun getRunningTunnelNames(): MutableSet<String> = mutableSetOf()

    /**
     * #751 Register what the engine's VPN slot carries while no tunnel is up
     * (null = nothing: release it). See INetBackend.setIdleTunnel. Answers the
     * engine's idle status, or why there is none.
     */
    fun setIdleTunnel(tunnelName: String, config: Config?, raiseNow: Boolean): String =
        idleAnswer { it.setIdleTunnel(tunnelName, config?.toWgQuickString().orEmpty(), raiseNow) }

    fun idleStatus(): String = idleAnswer { it.idleStatus }

    private fun idleAnswer(call: (INetBackend) -> String?): String {
        val r = remote() ?: return "DOWN: Cloud-Lib-Net-Wg is not installed"
        return runCatching { call(r) }.getOrElse { return "DOWN: ${it.message}" }
            // An engine that predates the call answers null: the transaction is unknown to it.
            ?: "DOWN: the installed Cloud-Lib-Net-Wg predates DNS without the mesh — update it"
    }

    fun unbind() {
        runCatching { ctx.unbindService(connection) }
            .onFailure { Log.w(TAG, "unbind: not bound", it) }
        service = null
    }

    companion object {
        private const val TAG = "AidlBackend"
        /** #794 public so a client can watch this package (an engine update or reinstall can
         *  drop the tunnel and its VPN consent) and name it to the user by its label. */
        const val ENGINE_PKG = "com.diegonmarcos.cloudlib.netwg"
        private const val SERVICE = "com.diegonmarcos.superapp.netwg.NetBackendService"
        private const val CONSENT_ACTIVITY = "com.diegonmarcos.superapp.netwg.VpnConsentActivity"
    }
}
