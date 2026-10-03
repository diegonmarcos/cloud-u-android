package com.diegonmarcos.superapp.browser

import org.json.JSONObject

/**
 * #823 (deferred #802 I9) "Summarize the page" on one of two routes, the #799/#800 rule:
 * `model` (the fleet's OpenRouter model, the declared default) or `on_device` (ML Kit's
 * on-device summarizer where the phone has it, else the extractive summary below — both
 * offline). The user's route is tried; a Model route that cannot run (offline, no token) is
 * not even attempted, and one that fails is replaced by the on-device answer when the
 * declaration allows it — which then says `fell_back` and why. Every answer names the route
 * and the engine that produced it. Pure; JVM-tested ([BrowserSummaryTest] in the app).
 */
object PageSummary {
    const val MODEL = "model"
    const val ON_DEVICE = "on_device"
    val ROUTES = listOf(MODEL, ON_DEVICE)

    const val OFFLINE = "offline: the phone has no validated internet connection"
    const val NO_TOKEN = "no OpenRouter token in the fleet Account"

    /** Engines, as reported. */
    const val ENGINE_MODEL = "openrouter"
    const val ENGINE_MLKIT = "mlkit_genai"
    const val ENGINE_EXTRACTIVE = "extractive"

    data class Result(
        val ok: Boolean,
        val summary: String?,
        /** The route that answered. */
        val route: String,
        val engine: String,
        /** The route he chose. */
        val requested: String,
        val fellBack: Boolean = false,
        /** Why the chosen route did not answer (fell back), or why an on-device engine was skipped. */
        val reason: String = "",
        val error: String? = null,
    ) {
        fun json(): JSONObject = JSONObject().put("ok", ok).put("summary", summary ?: JSONObject.NULL)
            .put("route", route).put("engine", engine).put("requested", requested).put("fell_back", fellBack)
            .put("reason", reason).put("error", error ?: JSONObject.NULL)

        companion object {
            fun failed(route: String, engine: String, error: String) = Result(false, null, route, engine, route, error = error)
        }
    }

    /** Whether the Model route is attempted, and when not, why (null [online]/[hasToken] = cannot tell: ask). */
    fun skipModel(online: Boolean?, hasToken: Boolean?): String? = when {
        online == false -> OFFLINE
        hasToken == false -> NO_TOKEN
        else -> null
    }

    /** The line under a summary: what produced it, and why it is not the route he chose. */
    fun credit(r: JSONObject): String {
        val by = if (r.optString("route") == MODEL) "the model (${r.optString("engine").substringAfter(':')})"
            else if (r.optString("engine") == ENGINE_MLKIT) "on device (ML Kit)" else "on device (key sentences)"
        return "summarized by $by" + if (r.optBoolean("fell_back")) "; the model could not answer: ${r.optString("reason")}" else ""
    }

    /** An unknown route reads as the declared default. */
    fun route(chosen: String?, default: String): String = chosen?.takeIf { it in ROUTES } ?: default.takeIf { it in ROUTES } ?: MODEL

    /**
     * One summary on [chosen]. [onDevice] runs only when it answers; [model] never throws past here.
     */
    fun routed(
        chosen: String,
        online: Boolean?,
        hasToken: Boolean?,
        fallback: Boolean,
        onDevice: () -> Result,
        model: () -> Result,
    ): Result {
        if (chosen != MODEL) return onDevice().copy(requested = ON_DEVICE)
        val skip = skipModel(online, hasToken)
        val m = if (skip != null) Result.failed(MODEL, ENGINE_MODEL, skip)
            else runCatching { model() }.getOrElse { Result.failed(MODEL, ENGINE_MODEL, it.message ?: it.javaClass.simpleName) }
        if (m.ok || !fallback) return m.copy(requested = MODEL)
        val d = onDevice()
        // When on-device cannot answer either, the model's own failure is the more useful sentence.
        return if (d.ok) d.copy(requested = MODEL, fellBack = true, reason = m.error ?: "the model did not answer")
            else m.copy(requested = MODEL, error = "${m.error}; on device: ${d.error}")
    }

    private val SENTENCE = Regex("(?<=[.!?])\\s+(?=[\\p{Lu}\\d\"“(])")
    private val WORD = Regex("[\\p{L}\\d]+")
    private val STOP = setOf(
        "the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "is", "are", "was", "were", "be", "it", "that",
        "this", "with", "as", "by", "at", "from", "its", "has", "have", "had", "not", "but", "they", "their", "he", "she",
        "de", "la", "el", "en", "y", "que", "los", "las", "der", "die", "das", "und", "ist", "le", "les", "et", "des",
    )

    /**
     * The on-device summary of last resort, with no model at all: the [n] sentences whose words
     * are most frequent in the page (stop-words aside), a mild bonus for coming early, kept in
     * page order. Lines shorter than [minChars] (menus, buttons) are dropped first.
     */
    fun extractive(text: String, n: Int, minChars: Int = 40): String {
        val lines = text.lines().map { it.trim() }.filter { it.length >= minChars }
        val sentences = lines.flatMap { SENTENCE.split(it) }.map { it.trim() }.filter { it.length >= minChars }.distinct()
        if (sentences.isEmpty()) return ""
        val words = { s: String -> WORD.findAll(s.lowercase()).map { it.value }.filter { it.length > 2 && it !in STOP }.toList() }
        val freq = HashMap<String, Int>()
        sentences.forEach { s -> words(s).forEach { freq[it] = (freq[it] ?: 0) + 1 } }
        val scored = sentences.mapIndexed { i, s ->
            val w = words(s)
            val score = if (w.isEmpty()) 0.0 else w.sumOf { freq[it] ?: 0 }.toDouble() / w.size
            Triple(i, s, score * (1.0 + 0.5 / (1 + i)))
        }
        return scored.sortedByDescending { it.third }.take(n.coerceAtLeast(1)).sortedBy { it.first }.joinToString("\n") { "• " + it.second }
    }
}
