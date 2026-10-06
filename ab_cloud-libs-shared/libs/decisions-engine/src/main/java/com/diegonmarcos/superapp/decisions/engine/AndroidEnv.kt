package com.diegonmarcos.superapp.decisions.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import com.diegonmarcos.superapp.decisions.core.Env

/** The phone's connectivity and power state, read at call time. A reading that fails is the cautious one. */
class AndroidEnv(context: Context) : Env {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    override fun online(): Boolean = runCatching {
        val c = cm?.getNetworkCapabilities(cm.activeNetwork)
        c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(false)

    override fun metered(): Boolean = runCatching { cm?.isActiveNetworkMetered ?: true }.getOrDefault(true)

    override fun batterySaver(): Boolean = runCatching { pm?.isPowerSaveMode ?: false }.getOrDefault(false)
}
