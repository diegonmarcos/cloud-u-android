package app.sterna.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import app.sterna.core.jmap.FleetTransport

/**
 * #1090 Tells the endpoint router the network changed, so a fleet account re-probes its mesh address instead of
 * trusting a verdict taken on another network: Wi-Fi to mobile, the VPN (the mesh) coming up or going down. The
 * default network's transports are compared, so signal-strength callbacks cost nothing.
 */
object EndpointNetworkWatch {
    /** The transports of a network as one word, so "the same network, a stronger signal" compares equal. */
    internal fun signature(c: NetworkCapabilities?): String = listOf(
        NetworkCapabilities.TRANSPORT_WIFI to "w", NetworkCapabilities.TRANSPORT_CELLULAR to "c",
        NetworkCapabilities.TRANSPORT_VPN to "v", NetworkCapabilities.TRANSPORT_ETHERNET to "e",
    ).filter { c?.hasTransport(it.first) == true }.joinToString("") { it.second }

    @Volatile private var started = false
    @Volatile private var last = ""

    fun start(ctx: Context) {
        if (started) return
        val cm = ctx.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return
        started = runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { last = ""; FleetTransport.router.onNetworkChanged() }
                override fun onLost(network: Network) { last = ""; FleetTransport.router.onNetworkChanged() }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val now = signature(caps)
                    if (now != last) { last = now; FleetTransport.router.onNetworkChanged() }
                }
            })
        }.isSuccess
    }
}
