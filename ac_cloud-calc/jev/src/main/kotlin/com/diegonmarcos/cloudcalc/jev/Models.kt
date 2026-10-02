package com.diegonmarcos.cloudcalc.jev

import org.json.JSONObject

/**
 * OpenRouter's live catalogue of DECISION models (build.json::jev.models_url, i.e.
 * GET /api/v1/models?output_modalities=decisions). No model list is spelled in this app: the
 * picker shows what this call returned, cached, and a use's declared model is only trusted once
 * the catalogue confirms it.
 */
object Models {
    data class Info(
        val slug: String,
        val name: String,
        val provider: String,
        /** USD per prompt token; null when the catalogue gives no number (or a negative "varies"). */
        val promptPrice: Double?,
        val context: Int,
        val inputs: List<String>,
        val free: Boolean,
    ) {
        val images: Boolean get() = "image" in inputs
    }

    /** The catalogue body, decision models only; a body that is not the catalogue is no models. */
    fun parse(json: String?): List<Info> = runCatching {
        val data = JSONObject(json ?: return emptyList()).getJSONArray("data")
        (0 until data.length()).map { data.getJSONObject(it) }.filter { m ->
            val outs = m.optJSONObject("architecture")?.optJSONArray("output_modalities")
            outs != null && (0 until outs.length()).any { outs.getString(it) == "decisions" }
        }.map { m ->
            val slug = m.getString("id")
            val pricing = m.optJSONObject("pricing")
            val prompt = pricing?.optString("prompt")?.toDoubleOrNull()?.takeIf { it >= 0 }
            val completion = pricing?.optString("completion")?.toDoubleOrNull()
            val ins = m.optJSONObject("architecture")?.optJSONArray("input_modalities")
            Info(
                slug = slug,
                name = m.optString("name", slug),
                provider = slug.trimStart('~').substringBefore('/'),
                promptPrice = prompt,
                context = m.optInt("context_length", 0),
                inputs = if (ins == null) emptyList() else (0 until ins.length()).map { ins.getString(it) },
                free = slug.endsWith(":free") || (prompt == 0.0 && (completion ?: 0.0) == 0.0),
            )
        }
    }.getOrDefault(emptyList())

    /** Cheapest first; among equals a `:free` variant first, then by slug so the order is stable. */
    fun cheapestFirst(models: List<Info>): List<Info> =
        models.sortedWith(compareBy<Info>({ it.promptPrice ?: Double.MAX_VALUE }, { if (it.slug.endsWith(":free")) 0 else 1 }, { it.slug }))

    /**
     * The model a use runs on: the declared slug when the catalogue confirms it (or nothing is
     * cached yet), the cheapest catalogued model for [JevConfig.CHEAPEST] or an unconfirmed slug,
     * and [JevConfig.fallbackModel] when there is neither.
     */
    fun resolve(choice: String, catalogue: List<Info>, fallback: String): String = when {
        catalogue.isEmpty() -> if (choice == JevConfig.CHEAPEST) fallback else choice
        choice != JevConfig.CHEAPEST && catalogue.any { it.slug == choice } -> choice
        else -> cheapestFirst(catalogue).first().slug
    }

    /** USD per million prompt tokens, the unit OpenRouter's own pages quote. */
    fun perMillion(info: Info?): String = info?.promptPrice?.let { "$" + String.format(java.util.Locale.ROOT, "%.3f", it * 1_000_000) + "/M" } ?: "?"
}
