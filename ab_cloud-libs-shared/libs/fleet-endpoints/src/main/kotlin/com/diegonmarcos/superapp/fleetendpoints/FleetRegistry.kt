package com.diegonmarcos.superapp.fleetendpoints

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** One endpoint pair as an app learned it (Cloud Account's declaration or its published document). No secret. */
data class EndpointPair(val publicUrl: String, val meshUrl: String, val policy: String, val only: List<String> = emptyList(),
    /** False for an mTLS or SSO service: it is routed like any other, but the fleet bearer is never sent to its host. */
    val bearer: Boolean = true)

/**
 * #1093 The ONE way an app puts endpoint pairs on the fleet transport: Cloud Account from its own declaration, an app that
 * authenticates to the fleet (Cloud Vault) from the pairs Account publishes. The bearer is asked for when a request needs
 * it ([bearer]) and only for a host of a registered pair; the interceptor then sends it on that host's public path alone.
 */
object FleetRegistry {
    /** Registers [pairs] and points [bearers] at [bearer]; returns how many pairs were usable. */
    fun register(pairs: List<EndpointPair>, router: EndpointRouter, bearers: FleetBearers, bearer: () -> String?): Int {
        val hosts = pairs.filter { it.bearer }.mapNotNull { it.publicUrl.toHttpUrlOrNull()?.host }.toSet()
        bearers.source = { host -> if (host.lowercase() in hosts) bearer() else null }
        return pairs.count { router.set(it.publicUrl, it.meshUrl, EndpointPolicy.parse(it.policy), it.only) }
    }
}
