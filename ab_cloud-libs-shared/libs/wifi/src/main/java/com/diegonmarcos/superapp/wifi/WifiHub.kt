package com.diegonmarcos.superapp.wifi

import android.content.Context

/**
 * The process-wide analyzer (#1263): one [WifiController] per process, so the five tabs (and any host that embeds the panel)
 * share one scan history, one throttle window and the running tools. The host's [WifiHostConfig] is read on first use.
 */
object WifiHub {
    @Volatile private var controller: WifiController? = null

    fun controller(ctx: Context, host: WifiHostConfig = WifiHostConfig()): WifiController = controller ?: synchronized(this) {
        controller ?: run {
            val app = ctx.applicationContext
            fun asset(n: String) = app.assets.open("wifi/$n").bufferedReader().use { it.readText() }
            val cfg = WifiConfig.parse(asset("wifi-config.json"), host.overridesJson)
            val plans = ChannelPlans.parse(asset("channel-plans.json"))
            WifiController(app, cfg, plans) { OuiTable.parse(asset("oui.tsv")) }.also { controller = it }
        }
    }
}
