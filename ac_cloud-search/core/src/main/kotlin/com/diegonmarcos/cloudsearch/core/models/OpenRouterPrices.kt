package com.diegonmarcos.cloudsearch.core.models

import com.diegonmarcos.cloudsearch.core.models.ModelCatalogue.Price
import com.diegonmarcos.cloudsearch.core.models.ModelCatalogue.PriceUnit
import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenRouter's public catalogues, read: /models and /embeddings/models publish DOLLARS PER TOKEN as
 * strings, /models/{id}/endpoints the same per provider (the cheapest is the "Floor"),
 * /images/models/{id}/endpoints a list of billables per image, megapixel or token, /videos/models
 * a pricing_skus map per second of video. Everything here is converted to what the page shows: $
 * per 1M tokens, or $ per image / megapixel / second. A negative price ("varies", a router) is none.
 */
object OpenRouterPrices {
    /** "0.0000018" $/token → 1.8 $/1M. Rounded to a millionth of a dollar so 1.7999999999999998 reads 1.8. */
    fun perMillion(perToken: Any?): Double? {
        val v = when (perToken) {
            is Number -> perToken.toDouble()
            is String -> perToken.trim().toDoubleOrNull()
            else -> null
        } ?: return null
        if (v < 0 || v.isNaN() || v.isInfinite()) return null
        return Math.round(v * MILLION * MILLION) / MILLION
    }

    /** /models or /embeddings/models: every listed id with its list price per 1M tokens (floor = list until an endpoints read says otherwise). */
    fun list(json: String): Map<String, Price> = data(json).associate { m ->
        val p = m.optJSONObject("pricing")
        m.getString("id") to Price(PriceUnit.TOKENS, perMillion(p?.opt("prompt")), perMillion(p?.opt("completion")))
    }

    /** The ids a catalogue lists (any of the four). */
    fun ids(json: String): Set<String> = data(json).map { it.getString("id") }.toSet()

    /** /models/{id}/endpoints: the cheapest input and output any provider charges, per 1M tokens. */
    fun floor(json: String): Pair<Double?, Double?> {
        val eps = JSONObject(json).optJSONObject("data")?.optJSONArray("endpoints") ?: return null to null
        val pricing = objects(eps).mapNotNull { it.optJSONObject("pricing") }
        return pricing.mapNotNull { perMillion(it.opt("prompt")) }.minOrNull() to pricing.mapNotNull { perMillion(it.opt("completion")) }.minOrNull()
    }

    /** A list price with its floor: a floor above the list price (or none) is the list price. */
    fun withFloor(p: Price, floor: Pair<Double?, Double?>): Price = p.copy(
        floorInput = lower(p.input, floor.first), floorOutput = lower(p.output, floor.second),
    )

    private fun lower(list: Double?, floor: Double?): Double? = when {
        list == null -> floor
        floor == null -> list
        else -> minOf(list, floor)
    }

    /**
     * /images/models/{id}/endpoints: output_image (and input_image) billables. The unit is the
     * first output billable's; a per-token one is shown per 1M tokens. List = the first provider's
     * cheapest variant, floor = the cheapest across providers.
     */
    fun image(json: String): Price? {
        val eps = JSONObject(json).optJSONArray("endpoints") ?: return null
        val bills = objects(eps).map { e -> e.optJSONArray("pricing")?.let(::objects).orEmpty() }
        val unitName = bills.flatten().firstOrNull { it.optString("billable") == OUT_IMAGE }?.optString("unit") ?: return null
        val unit = imageUnit(unitName) ?: return null
        fun cost(o: JSONObject): Double? = o.optDouble("cost_usd").takeIf { !it.isNaN() && it >= 0 }
            ?.let { if (unit == PriceUnit.TOKENS) Math.round(it * MILLION * MILLION) / MILLION else it }
        fun pick(list: List<JSONObject>, billable: String) = list.filter { it.optString("billable") == billable && it.optString("unit") == unitName }.mapNotNull(::cost).minOrNull()
        val first = bills.first { b -> b.any { it.optString("billable") == OUT_IMAGE } }
        return Price(
            unit, pick(first, IN_IMAGE), pick(first, OUT_IMAGE),
            bills.mapNotNull { pick(it, IN_IMAGE) }.minOrNull(), bills.mapNotNull { pick(it, OUT_IMAGE) }.minOrNull(),
        )
    }

    private fun imageUnit(s: String): PriceUnit? = when (s) {
        "image" -> PriceUnit.IMAGE
        "megapixel" -> PriceUnit.MEGAPIXEL
        "token" -> PriceUnit.TOKENS
        else -> null
    }

    /**
     * /videos/models: per id, its pricing_skus per second of video — `cents_per_…second…` in cents,
     * `…duration_seconds…` in dollars. The cheapest second (lowest resolution) is the price.
     */
    fun videos(json: String): Map<String, Price> = data(json).mapNotNull { m ->
        val skus = m.optJSONObject("pricing_skus") ?: return@mapNotNull null
        val perSecond = skus.keys().asSequence().filter { "second" in it }.mapNotNull { k ->
            skus.optString(k).toDoubleOrNull()?.takeIf { it >= 0 }?.let { if (k.startsWith("cents_")) it / CENTS else it }
        }.toList()
        val low = perSecond.minOrNull() ?: return@mapNotNull null
        m.getString("id") to Price(PriceUnit.SECOND, null, low)
    }.toMap()

    private fun data(json: String): List<JSONObject> = JSONObject(json).optJSONArray("data")?.let(::objects).orEmpty()

    private fun objects(a: JSONArray): List<JSONObject> = (0 until a.length()).mapNotNull { a.optJSONObject(it) }

    private const val MILLION = 1_000_000.0
    private const val CENTS = 100.0
    private const val OUT_IMAGE = "output_image"
    private const val IN_IMAGE = "input_image"
}
