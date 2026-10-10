package com.diegonmarcos.cloudsearch.core.models

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.MathContext
import java.util.Locale

/**
 * Chat › Search's model catalogue: the curated selection (app assets models/catalogue.json — which
 * model per provider per section, its params and licence) priced by OpenRouter's live catalogues.
 * Groups A Text, B Search, C Audio & Speech, D Visual Media; each section a dense table, Anthropic
 * first as the reference, then one row per provider ranked by price, low to high. Only `chat`
 * sections serve the chat; the others are browsable and greyed. Nothing here knows a network.
 */
object ModelCatalogue {
    enum class Kind { CHAT, EMBEDDINGS, RERANK, AUDIO, IMAGE }

    /** Which OpenRouter catalogue prices a section's rows. */
    enum class Source { CHAT, EMBEDDINGS, IMAGES, VIDEOS, NONE }

    enum class RankBy { INPUT, OUTPUT }

    /** The unit a price is in: dollars per 1M tokens, or per image / megapixel / second of video. */
    enum class PriceUnit { TOKENS, IMAGE, MEGAPIXEL, SECOND }

    /** A price as the page shows it; a floor is the cheapest provider OpenRouter routes to. Null = not published. */
    data class Price(val unit: PriceUnit, val input: Double?, val output: Double?, val floorInput: Double? = input, val floorOutput: Double? = output)

    data class Row(
        val provider: String, val id: String, val name: String,
        val params: String, val paramsEst: Boolean, val license: String, val licenseEst: Boolean,
        val note: String?, val snapshot: Price?,
    ) {
        val anthropic: Boolean get() = id.startsWith(ANTHROPIC_PREFIX)
    }

    data class Section(val id: String, val label: String, val kind: Kind, val source: Source, val rankBy: RankBy, val note: String, val rows: List<Row>) {
        /** Only a chat section's models can answer the chat; every other section is browsed, not picked. */
        val chat: Boolean get() = kind == Kind.CHAT

        /** No Anthropic model here (embeddings, rerank, audio, image): the page draws one greyed reference row. */
        val needsReferenceRow: Boolean get() = rows.none { it.anthropic }
    }

    data class Group(val id: String, val label: String, val sections: List<Section>)

    data class Urls(val chat: String, val embeddings: String, val images: String, val videos: String, val modelEndpoints: String, val imageEndpoints: String) {
        fun list(s: Source): String? = when (s) {
            Source.CHAT -> chat
            Source.EMBEDDINGS -> embeddings
            Source.IMAGES -> images
            Source.VIDEOS -> videos
            Source.NONE -> null
        }
    }

    data class Catalogue(val version: Int, val pricingAsOf: String, val refreshHours: Int, val urls: Urls, val groups: List<Group>) {
        val sections: List<Section> get() = groups.flatMap { it.sections }
        val rows: List<Row> get() = sections.flatMap { it.rows }
    }

    /** One row as drawn: its price (live, cached or the snapshot), whether OpenRouter still lists it, whether the chat may pick it. */
    data class Shown(val row: Row, val price: Price?, val listed: Boolean, val selectable: Boolean)

    const val ANTHROPIC_PREFIX = "anthropic/"

    /** Two prices closer than this are the same price: no "Floor" note, no reorder. */
    const val SAME = 0.01

    /** [json] is assets/models/catalogue.json. Throws on a selection that would draw a wrong table. */
    fun parse(json: String): Catalogue {
        val o = JSONObject(json)
        val u = o.getJSONObject("urls")
        val urls = Urls(
            u.getString("chat"), u.getString("embeddings"), u.getString("images"), u.getString("videos"),
            u.getString("model_endpoints"), u.getString("image_endpoints"),
        )
        listOf(urls.chat, urls.embeddings, urls.images, urls.videos, urls.modelEndpoints, urls.imageEndpoints).forEach {
            require(it.startsWith("https://")) { "catalogue url $it is not https" }
        }
        require(ID in urls.modelEndpoints && ID in urls.imageEndpoints) { "an endpoints url has no $ID" }
        val refresh = o.getInt("refresh_hours")
        require(refresh >= 1) { "refresh_hours $refresh: at most hourly" }
        val asOf = o.getString("pricing_as_of")
        require(DATE.matches(asOf)) { "pricing_as_of $asOf is not YYYY-MM-DD" }
        val groups = objects(o.getJSONArray("groups")).map { g ->
            Group(g.getString("id"), g.getString("label"), objects(g.getJSONArray("sections")).map(::section))
        }
        require(groups.isNotEmpty()) { "no groups" }
        val ids = groups.flatMap { g -> g.sections.map { it.id } }
        require(ids.size == ids.toSet().size) { "a section id repeats: $ids" }
        return Catalogue(o.getInt("version"), asOf, refresh, urls, groups)
    }

    private fun section(s: JSONObject): Section {
        val id = s.getString("id")
        val kind = enumOf<Kind>(s.getString("kind"), id)
        val source = enumOf<Source>(s.getString("catalog"), id)
        val rows = objects(s.getJSONArray("rows")).map { row(it, id) }
        // Anthropic is the benchmark and may bring several; every other provider brings its one best model.
        val providers = rows.filterNot { it.anthropic }.map { it.provider.lowercase(Locale.ROOT) }
        require(providers.size == providers.toSet().size) { "$id: one row per provider, but ${providers.groupBy { it }.filter { it.value.size > 1 }.keys} repeat" }
        val dup = rows.map { it.id }
        require(dup.size == dup.toSet().size) { "$id: a model id repeats" }
        require(kind == Kind.CHAT || rows.none { it.anthropic }) { "$id: Anthropic has no ${kind.name.lowercase()} model on OpenRouter" }
        require(source != Source.NONE || rows.isEmpty()) { "$id: rows with no catalogue to price them" }
        require(source == Source.NONE || rows.all { it.snapshot != null }) { "$id: a row without its offline snapshot" }
        return Section(id, s.getString("label"), kind, source, enumOf(s.getString("rank_by"), id), s.optString("note"), rows)
    }

    private fun row(r: JSONObject, section: String): Row {
        val id = r.getString("id").trim()
        require(id.isNotEmpty() && '/' in id) { "$section: model id '$id' is not provider/model" }
        val provider = r.getString("provider").trim()
        require(provider.isNotEmpty()) { "$section/$id: no provider" }
        require((provider == "Anthropic") == id.startsWith(ANTHROPIC_PREFIX)) { "$section/$id: provider $provider" }
        return Row(
            provider, id, r.getString("name"), r.getString("params"), r.getBoolean("params_est"),
            r.getString("license"), r.getBoolean("license_est"), r.optString("note").ifBlank { null },
            r.optJSONObject("snapshot")?.let(::snapshot),
        )
    }

    private fun snapshot(o: JSONObject): Price = Price(
        enumOf(o.getString("unit"), "snapshot"), o.num("in"), o.num("out"), o.num("floor_in"), o.num("floor_out"),
    )

    /**
     * The section as drawn: Anthropic's rows first, in their curated order (the benchmark), then
     * every other row ranked by its price, low to high — output, or input where output is not
     * billed — the cheaper unit first where a section mixes units; a row with no price goes last.
     * [prices] null = nothing fetched yet (the snapshot stands); [missing] = ids a fetched catalogue no longer lists.
     */
    fun ordered(section: Section, prices: Map<String, Price>?, missing: Set<String>): List<Shown> {
        val shown = section.rows.map { r ->
            val listed = r.id !in missing
            Shown(r, prices?.get(r.id) ?: r.snapshot, listed, section.chat && listed)
        }
        val (ref, rest) = shown.partition { it.row.anthropic }
        return ref + rest.sortedWith(compareBy<Shown>({ rank(it, section.rankBy) == null }, { it.price?.unit }, { rank(it, section.rankBy) }))
    }

    /** The price a row is ranked by; negative (a router's "varies") counts as none. */
    fun rank(s: Shown, by: RankBy): Double? {
        val p = s.price ?: return null
        return (if (by == RankBy.OUTPUT) p.output ?: p.input else p.input ?: p.output)?.takeIf { it >= 0 }
    }

    /** [id] picked from [section]: true and handed to [pick] only when the chat can use it. */
    fun choose(section: Section, shown: Shown, pick: (String) -> Unit): Boolean {
        if (!shown.selectable || !section.chat || shown.row !in section.rows) return false
        pick(shown.row.id)
        return true
    }

    /** "$1.28", "$0.0126", "$0.90", "free"; null = "—". Up to three significant figures under a dollar. */
    fun usd(x: Double?): String {
        if (x == null || x < 0) return DASH
        if (x == 0.0) return FREE
        val s = if (x >= 1) String.format(Locale.ROOT, "%.2f", x)
        else BigDecimal(x).round(MathContext(3)).stripTrailingZeros().toPlainString().let { if (it.substringAfter('.', "").length < 2) String.format(Locale.ROOT, "%.2f", x) else it }
        return "$$s"
    }

    fun suffix(u: PriceUnit): String = when (u) {
        PriceUnit.TOKENS -> ""
        PriceUnit.IMAGE -> "/img"
        PriceUnit.MEGAPIXEL -> "/MP"
        PriceUnit.SECOND -> "/s"
    }

    /** The Input cell: the list price, its floor beside it when a provider is cheaper. */
    fun inputCell(section: Section, p: Price?): String = cell(p?.input, p?.floorInput, p?.unit, section.rankBy == RankBy.INPUT)

    /** The Output cell, "$1.28 (Floor ~$0.30)"; "—" for embeddings, which bill no output. */
    fun outputCell(section: Section, p: Price?): String =
        if (section.kind == Kind.EMBEDDINGS) DASH else cell(p?.output, p?.floorOutput, p?.unit, section.rankBy == RankBy.OUTPUT)

    private fun cell(list: Double?, floor: Double?, unit: PriceUnit?, withFloor: Boolean): String {
        if (list == null || unit == null || list < 0) return DASH
        val base = usd(list).let { if (it == FREE) it else it + suffix(unit) }
        return if (withFloor && floor != null && floor >= 0 && floor < list * (1 - SAME)) "$base (Floor ~${usd(floor)})" else base
    }

    /** "Dense 24B est." — a value nobody published is marked as the estimate it is. */
    fun est(value: String, estimated: Boolean): String = if (estimated) "$value est." else value

    const val DASH = "—"
    const val FREE = "free"
    const val ID = "{id}"
    private val DATE = Regex("\\d{4}-\\d{2}-\\d{2}")

    private inline fun <reified E : Enum<E>> enumOf(s: String, where: String): E =
        enumValues<E>().firstOrNull { it.name.equals(s, ignoreCase = true) } ?: throw IllegalArgumentException("$where: unknown ${E::class.simpleName} '$s'")

    private fun objects(a: JSONArray): List<JSONObject> = (0 until a.length()).map { a.getJSONObject(it) }

    private fun JSONObject.num(k: String): Double? = if (has(k) && !isNull(k)) getDouble(k) else null
}
