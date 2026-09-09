package app.sterna.ui.text

import android.util.Base64
import app.sterna.BuildConfig
import org.json.JSONObject

/**
 * cloud-mail's OWN text-tool registry: providers, model lists, Enhance prompts, Resume prompts.
 *
 * WHAT THIS IS A COPY OF, AND WHAT IT DELIBERATELY IS NOT. The keyboard's `AiRouter` does two
 * separable jobs: it reads a baked registry and resolves the user's choices out of it, and it
 * makes the HTTPS request. This object is a copy of the FIRST job only, over cloud-mail's own
 * `build.json::mail_ai`. The second job — the POST, the auth header, the timeout, the chunking,
 * the summary budget, the bullet enforcement and the provider API KEY — is not copied and must
 * never be: it stays in Cloud Keyboard's process behind `ITextTools`, in one copy, so a fix to it
 * is made once. See [MailTextToolsPrefs] for the settings side.
 *
 * WHY A COPY AT ALL. The owner asked for cloud-mail to have its own Translate, Enhance, Resume and
 * AI Model Routing — its own, meaning editable in cloud-mail without changing the keyboard. A
 * single shared registry cannot do that no matter how the screens are arranged: two screens over
 * one store still edit each other. The prompts and model lists here started identical to the
 * keyboard's and are free to diverge from this point on. That divergence is the feature.
 *
 * Reads `BuildConfig.MAIL_AI_ROUTING_B64`, baked by app/build.gradle.kts. Base64 because the
 * prompts carry quotes and newlines a plain BuildConfig string would not survive.
 */
object MailAiRegistry {

    /**
     * One named prompt out of the registry. [bullets] marks a prompt that asked the model for a
     * list; it is registry data rather than a guess from the wording, and it is the flag that
     * travels to the engine so the far side can hold the model to the shape THIS app asked for.
     */
    class Style(val id: String, val label: String, val prompt: String, val bullets: Boolean = false)

    /** One row of the routing table. [id] is the provider's exact model id — what a request sends. */
    class Model(val id: String, val name: String, val note: String?)

    class Provider(val id: String, val label: String, val defaultModel: String, val models: List<Model>)

    private val registry: JSONObject by lazy {
        JSONObject(String(Base64.decode(BuildConfig.MAIL_AI_ROUTING_B64, Base64.DEFAULT), Charsets.UTF_8))
    }

    val providers: List<Provider> by lazy {
        val o = registry.getJSONObject("providers")
        o.keys().asSequence().map { id ->
            val p = o.getJSONObject(id)
            val models = p.getJSONArray("models").let { a ->
                (0 until a.length()).map { a.getJSONObject(it) }
            }.map { m ->
                val modelId = m.getString("id")
                // A row with no short name would render an empty Name cell, which reads as a
                // broken row rather than a missing field; the id always works.
                Model(modelId, m.optString("name").ifEmpty { modelId }, m.optString("note").ifEmpty { null })
            }
            Provider(id, p.getString("label"), p.getString("default_model"), models)
        }.toList()
    }

    /** A registry object of id → {label, prompt}. JSONObject keeps insertion order, so this is menu order. */
    private fun promptSet(key: String): List<Style> {
        val o = registry.optJSONObject(key) ?: return emptyList()
        return o.keys().asSequence().map { id ->
            val s = o.getJSONObject(id)
            Style(id, s.getString("label"), s.optString("prompt"), s.optBoolean("bullets"))
        }.toList()
    }

    val styles: List<Style> by lazy { promptSet("styles") }
    val tones: List<Style> by lazy { promptSet("tones") }
    val lengths: List<Style> by lazy { promptSet("lengths") }
    val languages: List<Style> by lazy { promptSet("languages") }

    /**
     * "Text Resume" — SUMMARISE. The owner's product name for condensing a text, kept exactly; not
     * a curriculum vitae and not resuming a paused operation.
     *
     * Its own prompt set rather than more [styles], for the reason the keyboard's copy gives: an
     * Enhance style listed among the summaries would offer "improve this" to a feature promising
     * "shorten this", and a summary prompt in the Enhance list would let Enhance replace the
     * user's paragraph with a précis of it.
     */
    val summaries: List<Style> by lazy { promptSet("summaries") }

    val defaultProvider: String get() = registry.getString("default_provider")
    val defaultStyle: String get() = registry.getString("default_style")
    val defaultTone: String get() = registry.optString("default_tone", "keep")
    val defaultLength: String get() = registry.optString("default_length", "keep")
    val defaultLanguage: String get() = registry.optString("default_language", "keep")
    val defaultSummary: String get() = registry.getString("default_summary")

    /**
     * Prepended to every rewrite prompt. Without it the model reads the text as a message
     * addressed to it and answers it conversationally instead of rewriting it.
     */
    val rewritePreamble: String get() = registry.optString("rewrite_preamble")

    /** Prepended to every summary prompt, and deliberately NOT [rewritePreamble]. */
    val summaryPreamble: String get() = registry.optString("summary_preamble")

    fun provider(id: String?): Provider =
        providers.firstOrNull { it.id == id } ?: providers.first { it.id == defaultProvider }

    private fun styleOf(set: List<Style>, id: String?, fallback: String): Style =
        set.firstOrNull { it.id == id } ?: set.first { it.id == fallback }

    fun style(id: String?): Style = styleOf(styles, id, defaultStyle)
    fun tone(id: String?): Style = styleOf(tones, id, defaultTone)
    fun length(id: String?): Style = styleOf(lengths, id, defaultLength)
    fun language(id: String?): Style = styleOf(languages, id, defaultLanguage)
    fun summary(id: String?): Style = styleOf(summaries, id, defaultSummary)

    /**
     * The system prompt an Enhance run sends: the preamble, the chosen style, then the tone, the
     * length and the output language pinned in this app's Text Enhancement page.
     *
     * COMPOSED HERE, IN THIS APP, and sent whole. The engine appends nothing — if it did, the
     * keyboard's own preamble and style would leak back into cloud-mail's rewrites and the two
     * apps would share a setting again through the back door. The "keep" entries carry an empty
     * prompt, so leaving all three alone reproduces the plain style prompt.
     */
    fun enhancePrompt(styleId: String?, toneId: String?, lengthId: String?, languageId: String?): String =
        listOf(
            rewritePreamble,
            style(styleId).prompt,
            tone(toneId).prompt,
            length(lengthId).prompt,
            language(languageId).prompt,
        ).filter { it.isNotBlank() }.joinToString(" ")

    /**
     * The system prompt a Resume run sends: [summaryPreamble] and the chosen summary prompt.
     *
     * No tone, no length, no output language. Those three shape a REWRITE of the user's own words
     * and applied to a summary they fight the summary prompt's own length instruction; the output
     * language is decided by the summary preamble instead.
     */
    fun summaryPrompt(summaryId: String?): String =
        listOf(summaryPreamble, summary(summaryId).prompt).filter { it.isNotBlank() }.joinToString(" ")
}
