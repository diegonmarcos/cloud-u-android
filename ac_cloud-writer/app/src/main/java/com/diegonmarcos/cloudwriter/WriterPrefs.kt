package com.diegonmarcos.cloudwriter

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.diegonmarcos.superapp.texttools.TextToolsClient
import org.json.JSONObject

/**
 * cloud-writer's OWN text-tool settings — including THE PER-TOOL MODEL, which is the one thing
 * here that exists nowhere else in the fleet.
 *
 * SEPARATE BY THE PLATFORM, NOT BY A NAMING CONVENTION. These are cloud-writer's
 * SharedPreferences, in cloud-writer's data directory, under cloud-writer's user id. This
 * application declares no `sharedUserId`, no settings ContentProvider and no shared backup agent,
 * so Android itself is what keeps this store and the serving application's apart: there is no key
 * cloud-writer can write that the keyboard can read, and none it can read that the keyboard can
 * write. That is why the key names below can safely echo the keyboard's — same name, different
 * file, different application, no relationship.
 *
 * NO CREDENTIAL IS STORED HERE, AND THERE IS NO KEY FOR ONE. Choosing a model is a setting;
 * holding the account that pays for it is not. cloud-writer names a provider and a model and the
 * serving application spends the credential — which is exactly what `enhanceWith` and
 * `summariseWith` exist for. `settingsSnapshot()`, which [seedFromServingApp] reads, carries no
 * key by construction on the far side, so there is nothing arriving here to store even by
 * accident.
 *
 * @see WriterRegistry for the prompts and model lists these ids resolve against.
 */
object WriterPrefs {

    /**
     * cloud-writer's own preference file, named for the feature. NOT the default
     * `<package>_preferences` file: these values are read on a background thread during seeding,
     * and keeping them out of the file the rest of the application edits means a seed cannot
     * contend with an unrelated write.
     */
    private const val FILE = "text_tools"

    /** Provider id, e.g. "openrouter". */
    const val KEY_PROVIDER = "ai_provider"

    /** + provider id → the model id chosen for that provider, for any tool with no override. */
    const val KEY_MODEL_PREFIX = "ai_model_"

    /**
     * + tool id + "_" + provider id → THE MODEL THIS ONE TOOL USES. The owner's request, in one
     * preference key: "here we will define the AI model to do Summary, Grammar Only, Text
     * Enhance".
     *
     * KEYED BY TOOL **AND** PROVIDER, not by tool alone. A model id is only meaningful inside the
     * provider that serves it, so a per-tool key without the provider would hand an OpenRouter
     * model id to the mesh bridge the first time the owner switched provider — a route that
     * resolves to nothing and fails at call time with no screen having said so. It also means
     * switching provider and back remembers each provider's per-tool choices instead of flattening
     * them.
     *
     * UNSET FALLS BACK, and the fallback chain is deliberately two steps rather than one:
     * this tool's model, then the provider's [KEY_MODEL_PREFIX] model, then the registry's
     * `default_model`. So an owner who has never opened the per-tool section gets exactly the
     * behaviour of an application without one, and setting Summary alone leaves Enhance and
     * Grammar where they were.
     */
    const val KEY_TOOL_MODEL_PREFIX = "ai_tool_model_"

    /** Text Enhancement: which style, and the three lines appended after it. */
    const val KEY_ENHANCE_STYLE = "enhance_style"
    const val KEY_ENHANCE_TONE = "enhance_tone"
    const val KEY_ENHANCE_LENGTH = "enhance_length"
    const val KEY_ENHANCE_LANGUAGE = "enhance_language"

    /** Text Resume: which summary shape. */
    const val KEY_SUMMARY_STYLE = "summary_style"

    /** Translation: BCP-47 target tag; "" = the serving engine's own default. */
    const val KEY_TRANSLATE_TARGET = "translate_default_target"

    /**
     * Set once the values below have been seeded from whatever the owner had already configured.
     *
     * Its ABSENCE is what triggers [seedFromServingApp], so it is written only after a seed that
     * actually got an answer. A seed attempted while nothing is bound must leave this unset and
     * try again later: marking the application seeded because the peer happened to be unreachable
     * for one call is how a migration silently resets someone's settings to defaults.
     */
    private const val KEY_SEEDED = "seeded_from_serving_app"

    /** [TextToolsClient.settingsSnapshot]'s object of provider id → chosen model id. */
    private const val SNAPSHOT_MODELS = "ai_models"

    private const val TAG = "WriterPrefs"

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---- reads: every one falls back to this application's registry, never to a peer's ----

    fun providerId(context: Context): String =
        prefs(context).getString(KEY_PROVIDER, null) ?: WriterRegistry.defaultProvider

    /** The provider-wide model — what a tool with no override of its own uses. */
    fun modelId(context: Context, providerId: String = providerId(context)): String {
        val provider = WriterRegistry.provider(providerId)
        return prefs(context).getString(KEY_MODEL_PREFIX + provider.id, null)
            ?.takeIf { it.isNotBlank() }
            ?: provider.defaultModel
    }

    /**
     * THE MODEL [tool] RUNS ON. The whole point of the per-tool section, and the only read in this
     * file a run actually uses.
     *
     * A stored id that the registry no longer holds is IGNORED rather than sent. Model catalogues
     * are edited — a model is withdrawn, an id is renamed — and a route to a model the provider
     * has never heard of fails at call time, mid-run, with the owner looking at a progress line.
     * Falling back here means the tool keeps working and the picker simply shows the fallback,
     * which is a state the owner can see and correct.
     */
    fun modelFor(context: Context, tool: WriterTool, providerId: String = providerId(context)): String {
        val provider = WriterRegistry.provider(providerId)
        val chosen = prefs(context)
            .getString(KEY_TOOL_MODEL_PREFIX + tool.id + "_" + provider.id, null)
            ?.takeIf { id -> provider.models.any { it.id == id } }
        return chosen ?: modelId(context, provider.id)
    }

    fun enhanceStyleId(context: Context): String =
        prefs(context).getString(KEY_ENHANCE_STYLE, null) ?: WriterRegistry.defaultStyle

    fun enhanceToneId(context: Context): String =
        prefs(context).getString(KEY_ENHANCE_TONE, null) ?: WriterRegistry.defaultTone

    fun enhanceLengthId(context: Context): String =
        prefs(context).getString(KEY_ENHANCE_LENGTH, null) ?: WriterRegistry.defaultLength

    fun enhanceLanguageId(context: Context): String =
        prefs(context).getString(KEY_ENHANCE_LANGUAGE, null) ?: WriterRegistry.defaultLanguage

    fun summaryStyleId(context: Context): String =
        prefs(context).getString(KEY_SUMMARY_STYLE, null) ?: WriterRegistry.defaultSummary

    fun translateTarget(context: Context): String =
        prefs(context).getString(KEY_TRANSLATE_TARGET, null).orEmpty()

    // ---- writes: each one lands in THIS application's file only ----

    fun put(context: Context, key: String, value: String) {
        prefs(context).edit().putString(key, value).apply()
    }

    /** The provider-wide model, so switching provider and back remembers each choice. */
    fun putModel(context: Context, providerId: String, modelId: String) =
        put(context, KEY_MODEL_PREFIX + providerId, modelId)

    /** The model for ONE tool under ONE provider. */
    fun putToolModel(context: Context, tool: WriterTool, providerId: String, modelId: String) =
        put(context, KEY_TOOL_MODEL_PREFIX + tool.id + "_" + providerId, modelId)

    // ---- the composed prompts a run sends. Built from THIS application's values, sent whole. ----

    fun enhancePrompt(context: Context): String = WriterRegistry.enhancePrompt(
        enhanceStyleId(context),
        enhanceToneId(context),
        enhanceLengthId(context),
        enhanceLanguageId(context),
    )

    fun summaryPrompt(context: Context): String =
        WriterRegistry.summaryPrompt(summaryStyleId(context))

    /** Whether this application's chosen summary prompt asked the model for a list. */
    fun summaryWantsBullets(context: Context): Boolean =
        WriterRegistry.summary(summaryStyleId(context)).bullets

    // ---- migration ----

    fun isSeeded(context: Context): Boolean = prefs(context).getBoolean(KEY_SEEDED, false)

    /**
     * Adopt, ONCE, whatever the owner had already configured in the serving application, so that
     * opening cloud-writer for the first time does not look like their configuration being thrown
     * away.
     *
     * The owner has been using these tools from the keyboard and from cloud-mail for months. The
     * style they pinned, the tone, the summary shape, the model they chose — all of it lives in
     * the serving application's store. Starting cloud-writer at the registry defaults would reset
     * every one of them as far as the owner can tell, so the first run reads what is there and
     * keeps it.
     *
     * WHAT IT ADOPTS: provider, the chosen model for each provider, Enhance style/tone/length/
     * output language, the Resume shape, the translation target. WHAT IT DOES NOT: the API key,
     * which the snapshot does not carry and this application has no place to put; and the PER-TOOL
     * models, which no peer has because no peer has ever had per-tool models — they start at the
     * adopted provider-wide model, which is precisely the behaviour the owner has today.
     *
     * ONLY EVER A STARTING POINT. After this runs the two stores are unrelated; editing one never
     * touches the other, and this function never runs again.
     *
     * BLOCKING — it is a binder call. Callers put it on a background thread.
     */
    fun seedFromServingApp(context: Context, client: TextToolsClient) {
        if (isSeeded(context)) return
        val raw = client.settingsSnapshot()
        if (raw.isNullOrBlank()) {
            // Nothing bound, or a peer too old to know the method. Stay unseeded and try again
            // next time rather than declaring the owner's settings to be the defaults.
            Log.i(TAG, "no settings snapshot available yet - leaving unseeded, will retry")
            return
        }
        val snapshot = runCatching { JSONObject(raw) }.getOrElse {
            Log.w(TAG, "settings snapshot was not JSON - leaving unseeded", it)
            return
        }
        val edit = prefs(context).edit()

        // Only ids THIS application's registry knows. The registries are separate copies and the
        // peer's may hold a style or a model that cloud-writer's does not; adopting one of those
        // would store a value no screen here can display or change, which is the uneditable state
        // this whole arrangement exists to end.
        fun adopt(key: String, known: List<WriterRegistry.Style>) {
            val id = snapshot.optString(key).takeIf { it.isNotEmpty() } ?: return
            if (known.any { it.id == id }) edit.putString(key, id)
        }
        adopt(KEY_ENHANCE_STYLE, WriterRegistry.styles)
        adopt(KEY_ENHANCE_TONE, WriterRegistry.tones)
        adopt(KEY_ENHANCE_LENGTH, WriterRegistry.lengths)
        adopt(KEY_ENHANCE_LANGUAGE, WriterRegistry.languages)
        adopt(KEY_SUMMARY_STYLE, WriterRegistry.summaries)

        snapshot.optString(KEY_PROVIDER).takeIf { id -> WriterRegistry.providers.any { it.id == id } }
            ?.let { edit.putString(KEY_PROVIDER, it) }

        snapshot.optJSONObject(SNAPSHOT_MODELS)?.let { models ->
            WriterRegistry.providers.forEach { provider ->
                val chosen = models.optString(provider.id).takeIf { it.isNotEmpty() } ?: return@forEach
                if (provider.models.any { it.id == chosen }) {
                    edit.putString(KEY_MODEL_PREFIX + provider.id, chosen)
                }
            }
        }

        // A target tag is free text from an engine's language list rather than a registry id, so
        // there is nothing here to validate it against; it is taken as given, and an empty one
        // means "the engine's default", which is also this application's default.
        edit.putString(KEY_TRANSLATE_TARGET, snapshot.optString(KEY_TRANSLATE_TARGET))

        edit.putBoolean(KEY_SEEDED, true).apply()
        Log.i(TAG, "seeded cloud-writer's text-tool settings from the serving app's, once")
    }
}
