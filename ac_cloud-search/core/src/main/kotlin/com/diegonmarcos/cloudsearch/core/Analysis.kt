package com.diegonmarcos.cloudsearch.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Market statistics computed from what the live sources returned — nothing estimated, nothing
 * typed in. A number no source carries (time-to-hire, a salary band nobody publishes) is absent
 * (null), and the screen says so instead of showing one.
 */
object Analysis {
    data class Bar(val label: String, val value: Int)

    data class Result(
        val city: String,
        /** Open positions the Bundesagentur reports for the query in the city (its maxErgebnisse). */
        val total: Int?,
        /** Listings the share and pay figures were computed over. */
        val sample: Int,
        val remoteShare: Double?,
        val remoteSample: Int,
        val medianHourly: Double?,
        val hourlySample: Int,
        val medianYearly: Double?,
        val yearlySample: Int,
        /** Top occupational fields (BA facet berufsfeld), largest first. */
        val fields: List<Bar>,
    ) {
        fun toJson(): JSONObject = JSONObject().put("city", city).put("total", total ?: JSONObject.NULL).put("sample", sample)
            .put("remote_share", remoteShare ?: JSONObject.NULL).put("remote_sample", remoteSample)
            .put("median_hourly", medianHourly ?: JSONObject.NULL).put("hourly_sample", hourlySample)
            .put("median_yearly", medianYearly ?: JSONObject.NULL).put("yearly_sample", yearlySample)
            .put("fields", JSONArray(fields.map { JSONObject().put("label", it.label).put("value", it.value) }))
    }

    const val TOP_FIELDS = 6

    fun median(xs: List<Double>): Double? {
        if (xs.isEmpty()) return null
        val s = xs.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
    }

    fun jobs(r: SearchEngine.QueryResult, baBody: String?): Result {
        val ba = baBody?.let { runCatching { JSONObject(it) }.getOrNull() }
        val withRemote = r.listings.filter { it.remote != null }
        val hourly = r.listings.filter { it.unit == "h" }.mapNotNull { it.price }
        val yearly = r.listings.filter { it.unit == "yr" }.mapNotNull { it.price }
        val counts = ba?.optJSONObject("facetten")?.optJSONObject("berufsfeld")?.optJSONObject("counts")
        val fields = if (counts == null) emptyList()
            else counts.keys().asSequence().map { Bar(it, counts.optInt(it)) }.sortedByDescending { it.value }.take(TOP_FIELDS).toList()
        return Result(
            city = r.city,
            total = ba?.takeIf { it.has("maxErgebnisse") }?.optInt("maxErgebnisse"),
            sample = r.listings.size,
            remoteShare = if (withRemote.isEmpty()) null else withRemote.count { it.remote == true }.toDouble() / withRemote.size,
            remoteSample = withRemote.size,
            medianHourly = median(hourly), hourlySample = hourly.size,
            medianYearly = median(yearly), yearlySample = yearly.size,
            fields = fields,
        )
    }
}
