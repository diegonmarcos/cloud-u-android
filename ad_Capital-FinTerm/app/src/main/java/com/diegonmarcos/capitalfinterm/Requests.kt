package com.diegonmarcos.capitalfinterm

import java.net.URLEncoder
import java.time.LocalDate

/** Builds a request address from a declared source's endpoint, a declared path and declared parameters. No address is spelled here. */
object Requests {
    /** endpoint + path + the parameters, percent-encoded, in the order given; [path] may carry {name} slots filled from [slots]. */
    fun url(endpoint: String, path: String, params: List<Pair<String, String>>, slots: Map<String, String> = emptyMap()): String {
        var p = path
        slots.forEach { (k, v) -> p = p.replace("{$k}", v) }
        val q = params.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }
        return endpoint.trimEnd('/') + p + (if (q.isEmpty()) "" else "?$q")
    }

    /** One FRED series, from [lookbackDays] before [today]: the public graph endpoint honours the window for a single series only. */
    fun fred(endpoint: String, seriesId: String, today: LocalDate, lookbackDays: Int): String =
        url(endpoint, "", listOf("id" to seriesId, "cosd" to today.minusDays(lookbackDays.toLong()).toString()))

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
