package com.diegonmarcos.superapp.fleetconfig

/**
 * #1093 The one seam through which a library BELOW the fleet transport (libs:core's Telemetry) sends a request over it. An app
 * that runs the fleet endpoints (Cloud Account, Cloud Mail, Cloud Vault) installs [transport]; a URL it does not claim, or an
 * app that installed none, gets null and the caller makes its plain request, so every other app is unchanged.
 */
object FleetHttpSeam {
    data class Reply(val code: Int, val body: String, val location: String = "")

    fun interface Transport {
        /** Null when [url] is not a registered fleet endpoint; an [java.io.IOException] when the request could not be made. */
        fun exchange(method: String, url: String, headers: Map<String, String>, body: String?, connectMs: Int, readMs: Int): Reply?
    }

    @Volatile var transport: Transport? = null

    fun exchange(method: String, url: String, headers: Map<String, String>, body: String?, connectMs: Int, readMs: Int): Reply? =
        transport?.exchange(method, url, headers, body, connectMs, readMs)
}
