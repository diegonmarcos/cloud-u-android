package com.diegonmarcos.superapp.browser

import com.diegonmarcos.superapp.texttools.TextTools
import org.json.JSONArray
import org.json.JSONObject

/**
 * #886 Translate page, IN PLACE: the pure half. The page's visible text nodes are collected by
 * assets/browser/translate_collect.js in batches, translated here by the ENGINE THE USER CHOSE, and
 * written back into the same nodes by translate_apply.js; translate_restore.js puts the originals back.
 * Everything in this file is JVM-tested ([PageTranslateTest]); [PageTranslator] drives it.
 *
 * THE TWO ENGINES (Configs ▸ Translate & summary ▸ Translate with), both reached over the fleet's
 * text-tools binder ([TextToolsPort]) and never copied into this app:
 *  - [ON_DEVICE]: the fleet's translation library (ML on the phone), via `translate`;
 *  - [OPENROUTER]: an LLM through AI Model Routing, via `enhanceWith` with a translation prompt.
 * The binder's own rule applies: the two are NOT interchangeable and neither ever calls the other —
 * an on-device translation never reaches the LLM provider, and an LLM translation is never routed
 * through the translator. The API key stays in the serving app (Cloud Writer / Cloud Keyboard): this
 * app only names the prompt, the target, and sends the text.
 */
object PageTranslate {
    const val ON_DEVICE = "on_device"
    const val OPENROUTER = "openrouter"
    val ENGINES = listOf(ON_DEVICE, OPENROUTER)

    /** The setting's "follow the phone" value for [targetTag]. */
    const val DEVICE_LANGUAGE = "device"

    /** One text node: its index in the page's registry and its trimmed text. */
    data class Seg(val idx: Int, val text: String)

    data class Collected(val items: List<Seg>, val remaining: Int)

    /** The collect script's answer; an empty or malformed answer is "nothing left". */
    fun parseCollect(json: String?): Collected {
        val o = runCatching { JSONObject(json ?: return Collected(emptyList(), 0)) }.getOrNull() ?: return Collected(emptyList(), 0)
        val arr = o.optJSONArray("items") ?: return Collected(emptyList(), o.optInt("remaining"))
        val items = (0 until arr.length()).mapNotNull { i ->
            val p = arr.optJSONArray(i) ?: return@mapNotNull null
            val t = p.optString(1)
            if (t.isBlank()) null else Seg(p.optInt(0), t)
        }
        return Collected(items, o.optInt("remaining"))
    }

    /** The apply script's argument: `{"<index>": "<text>"}`. */
    fun applyMap(results: Map<Int, String>): String =
        JSONObject().also { o -> results.forEach { (i, t) -> o.put(i.toString(), t) } }.toString()

    /** [segs] in consecutive groups of at most [maxItems] segments and [maxChars] characters (one segment may exceed it alone). */
    fun batches(segs: List<Seg>, maxChars: Int, maxItems: Int): List<List<Seg>> {
        val out = ArrayList<List<Seg>>()
        var cur = ArrayList<Seg>(); var chars = 0
        for (s in segs) {
            if (cur.isNotEmpty() && (chars + s.text.length > maxChars || cur.size >= maxItems)) {
                out.add(cur); cur = ArrayList(); chars = 0
            }
            cur.add(s); chars += s.text.length
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    /** `device` → the phone's language tag; anything else as chosen. */
    fun targetTag(setting: String?, deviceLanguage: String): String =
        if (setting.isNullOrBlank() || setting == DEVICE_LANGUAGE) deviceLanguage.ifBlank { "en" } else setting

    private val NAMES = mapOf(
        "en" to "English", "es" to "Spanish", "pt" to "Portuguese", "fr" to "French", "de" to "German", "it" to "Italian",
        "nl" to "Dutch", "pl" to "Polish", "ru" to "Russian", "ja" to "Japanese", "zh" to "Chinese", "ko" to "Korean",
        "ar" to "Arabic", "tr" to "Turkish", "sv" to "Swedish", "uk" to "Ukrainian", "hi" to "Hindi",
    )

    fun languageName(tag: String): String = NAMES[tag.lowercase().substringBefore('-')] ?: tag

    /** Does the on-device engine's language list cover [tag]? An empty list (an older peer) cannot say: assume yes. */
    fun supports(languages: List<String>, tag: String): Boolean =
        languages.isEmpty() || languages.any { it.equals(tag, true) || it.substringBefore('-').equals(tag.substringBefore('-'), true) }

    // ── the LLM engine: a JSON array in, a JSON array out ───────────────────────────────────────

    /** The system prompt of an LLM translation into [targetName]. Nothing else is added on the far side. */
    fun llmPrompt(targetName: String): String =
        "You are a translation engine inside a web browser. The user message is a JSON array of strings, each one " +
            "piece of text from a web page. Translate every string into $targetName. Reply with ONLY a JSON array of " +
            "strings: exactly the same number of elements, in the same order, one translation per input string. Do not " +
            "merge, split, drop, add or explain anything. Keep numbers, URLs, e-mail addresses, product and brand " +
            "names, and any placeholder such as {0} or %s unchanged. If a string is already in $targetName, repeat it as is."

    fun llmPayload(segs: List<Seg>): String = JSONArray().also { a -> segs.forEach { a.put(it.text) } }.toString()

    /**
     * The model's reply as exactly [expected] strings, or null when it cannot be read as that: a reply
     * may arrive in a code fence or with a sentence around it, so the first top-level `[...]` is taken.
     * A wrong COUNT is a null, never a guess: writing translations into the wrong nodes is worse than none.
     */
    fun parseLlm(reply: String?, expected: Int): List<String>? {
        val text = reply?.trim().orEmpty()
        val from = text.indexOf('['); val to = text.lastIndexOf(']')
        if (from < 0 || to <= from) return null
        val arr = runCatching { JSONArray(text.substring(from, to + 1)) }.getOrNull() ?: return null
        if (arr.length() != expected) return null
        return (0 until arr.length()).map { i -> arr.opt(i).let { if (it is String) it else it?.toString().orEmpty() } }
    }

    // ── the engines, over the text-tools port ────────────────────────────────────────────────────

    /** What the fleet's text tools offer, as the browser uses them: the real binder client, or a test's fake. */
    interface TextToolsPort {
        fun installed(): Boolean
        fun translate(text: String, tag: String): TextTools.Result
        fun translateLanguages(): List<String>
        fun enhanceWith(text: String, systemPrompt: String): TextTools.Result
        fun summariseWith(text: String, systemPrompt: String): TextTools.Result
        fun providerLabel(): String?
    }

    /** One batch's outcome: the translations that arrived (by segment index) and, when it did not all work, why. */
    data class Outcome(val results: Map<Int, String>, val error: String? = null)

    interface Backend {
        val engine: String
        val maxChars: Int
        val maxItems: Int
        /** BLOCKING (binder): never on the main thread. */
        fun translate(segs: List<Seg>, targetTag: String): Outcome
    }

    class OnDeviceBackend(private val tools: TextToolsPort) : Backend {
        override val engine = ON_DEVICE
        override val maxChars = 4000
        override val maxItems = 40
        override fun translate(segs: List<Seg>, targetTag: String): Outcome {
            if (!tools.installed()) return Outcome(emptyMap(), TextTools.NOT_INSTALLED)
            val langs = tools.translateLanguages()
            if (!supports(langs, targetTag))
                return Outcome(emptyMap(), "On-device translation cannot write ${languageName(targetTag)} (it offers: ${langs.joinToString()})")
            val out = LinkedHashMap<Int, String>()
            var error: String? = null
            for (s in segs) {
                val r = tools.translate(s.text, targetTag)
                if (r.ok) out[s.idx] = r.text!! else { error = r.error; break }
            }
            return Outcome(out, error)
        }
    }

    class LlmBackend(private val tools: TextToolsPort) : Backend {
        override val engine = OPENROUTER
        override val maxChars = 2400
        override val maxItems = 40
        override fun translate(segs: List<Seg>, targetTag: String): Outcome {
            if (!tools.installed()) return Outcome(emptyMap(), TextTools.NOT_INSTALLED)
            return ask(segs, llmPrompt(languageName(targetTag)))
        }

        /** A batch the model answered with the wrong count is split in two and asked again, down to one string. */
        private fun ask(segs: List<Seg>, prompt: String): Outcome {
            val r = tools.enhanceWith(llmPayload(segs), prompt)
            if (!r.ok) return Outcome(emptyMap(), r.error)
            val parsed = parseLlm(r.text, segs.size)
            if (parsed != null) return Outcome(segs.indices.associate { segs[it].idx to parsed[it] })
            if (segs.size == 1) {
                // One string in: the whole reply IS its translation (minus a stray fence/quotes).
                val t = r.text!!.trim().removeSurrounding("```").trim().removeSurrounding("\"")
                return if (t.isBlank()) Outcome(emptyMap(), "The model returned nothing for a piece of text") else Outcome(mapOf(segs[0].idx to t))
            }
            val mid = segs.size / 2
            val a = ask(segs.subList(0, mid), prompt)
            if (a.error != null) return a
            val b = ask(segs.subList(mid, segs.size), prompt)
            return Outcome(a.results + b.results, b.error)
        }
    }

    fun backend(engine: String?, tools: TextToolsPort): Backend =
        if (engine == OPENROUTER) LlmBackend(tools) else OnDeviceBackend(tools)
}
