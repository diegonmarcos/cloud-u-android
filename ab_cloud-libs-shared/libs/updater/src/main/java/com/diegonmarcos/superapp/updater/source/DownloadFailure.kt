package com.diegonmarcos.superapp.updater.source

import java.io.IOException
import java.net.UnknownHostException

/**
 * #831 WHAT KIND OF FAILURE A DOWNLOAD LEG HIT, decided once, here.
 *
 * The phone could not resolve github.com, and the Store reported it as
 * "stalled at 0 of unknown bytes, no new data for 8s over 5 attempts" — five
 * retries of a name lookup that fails identically every time, described as a
 * slow transfer. A name that does not resolve and an asset that is not on the
 * release are FACTS, not flakes: no retry changes them, and each needs a
 * different thing from the owner (fix DNS / wait for the publish). So they are
 * classified, never retried, and worded for the person reading the row.
 */
object DownloadFailure {
    enum class Kind { DNS, NOT_PUBLISHED, OTHER }

    /** The host name did not resolve (UnknownHostException, "No address associated"). */
    class Unresolvable(val host: String, cause: Throwable?) :
        IOException("cannot resolve $host", cause)

    /** A non-2xx answer, with its code kept as data rather than parsed back out of text. */
    class HttpStatus(val code: Int, val url: String, body: String?) :
        IOException("HTTP $code for $url" + (body?.take(200)?.let { ": $it" } ?: ""))

    /**
     * The host's description of the resolver in effect ("10.0.0.1 via VPN",
     * Private DNS host, …). Set by the app that owns the DNS page (SuperApp:
     * FleetDns); null = unknown, and the message says so instead of guessing.
     */
    @Volatile var activeResolver: () -> String? = { null }

    /** The same, for one host: concurrent legs must not read each other's failure. Defaults to [activeResolver]. */
    @Volatile var activeResolverFor: (String?) -> String? = { activeResolver() }

    private fun chain(t: Throwable): Sequence<Throwable> = generateSequence(t) { c -> c.cause?.takeIf { it !== c } }.take(12)

    fun kind(t: Throwable): Kind {
        for (c in chain(t)) {
            if (c is Unresolvable || c is UnknownHostException) return Kind.DNS
            if (c is HttpStatus && (c.code == 404 || c.code == 410)) return Kind.NOT_PUBLISHED
            if (c is GhcrClient.HttpException && c.code == 404) return Kind.NOT_PUBLISHED
        }
        return Kind.OTHER
    }

    /** Facts a retry cannot change: fail the leg at once. */
    fun isFinal(t: Throwable): Boolean = kind(t) != Kind.OTHER

    /** The host that did not resolve, when the failure says. */
    fun host(t: Throwable): String? = chain(t).firstNotNullOfOrNull { c ->
        when (c) {
            is Unresolvable -> c.host
            is UnknownHostException -> c.message?.let { HOST_IN_MESSAGE.find(it)?.groupValues?.get(1) ?: it.substringBefore(':').trim().takeIf { h -> h.isNotEmpty() && ' ' !in h } }
            else -> null
        }
    }

    /** One line for the row: the classified wording, or the raw message for anything else. */
    fun describe(t: Throwable, resolver: String? = runCatching { activeResolverFor(host(t)) }.getOrNull()): String = when (kind(t)) {
        Kind.DNS -> "DNS: cannot resolve ${host(t) ?: "the download host"} (active resolver: ${resolver?.takeIf { it.isNotBlank() } ?: "unknown"})"
        Kind.NOT_PUBLISHED -> chain(t).firstNotNullOf { c ->
            when (c) {
                is HttpStatus -> "not published on the release yet (HTTP ${c.code})"
                is GhcrClient.HttpException -> "not published on GHCR yet (HTTP ${c.code})"
                else -> null
            }
        }
        Kind.OTHER -> t.message ?: t.javaClass.simpleName
    }

    /** Does a stored failure line (ApkCache note, Stage text) name a DNS failure? The Store keys its DNS-page button on this. */
    fun mentionsDns(text: String?): Boolean = text?.contains(DNS_PREFIX) == true

    const val DNS_PREFIX = "DNS: cannot resolve "
    private val HOST_IN_MESSAGE = Regex("resolve host \"([^\"]+)\"")
}
