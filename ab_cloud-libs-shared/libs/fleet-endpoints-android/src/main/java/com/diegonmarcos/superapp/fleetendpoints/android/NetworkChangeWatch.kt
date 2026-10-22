package com.diegonmarcos.superapp.fleetendpoints.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * #1090/#1093 The "the network changed" signal of the fleet endpoint router, ONE implementation for Cloud Mail and
 * Cloud Account: a mesh verdict taken on another network is stale (Wi-Fi to mobile, the VPN, which is the mesh,
 * coming up or going down). The default network's transports are compared, so signal-strength callbacks cost
 * nothing. It knows nothing of the router: the caller passes what to do ([start]'s `onChange`).
 */
object NetworkChangeWatch {
    /** The transports of a network as one word, so "the same network, a stronger signal" compares equal. */
    fun signature(c: NetworkCapabilities?): String = listOf(
        NetworkCapabilities.TRANSPORT_WIFI to "w", NetworkCapabilities.TRANSPORT_CELLULAR to "c",
        NetworkCapabilities.TRANSPORT_VPN to "v", NetworkCapabilities.TRANSPORT_ETHERNET to "e",
    ).filter { c?.hasTransport(it.first) == true }.joinToString("") { it.second }

    @Volatile private var started = false
    @Volatile private var last = ""

    /** Registers once per process; false when the platform refuses (the router then re-probes on its timer alone). */
    fun start(ctx: Context, onChange: () -> Unit): Boolean {
        if (started) return true
        val cm = ctx.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return false
        started = runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { last = ""; onChange() }
                override fun onLost(network: Network) { last = ""; onChange() }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val now = signature(caps)
                    if (now != last) { last = now; onChange() }
                }
            })
        }.isSuccess
        return started
    }
}
