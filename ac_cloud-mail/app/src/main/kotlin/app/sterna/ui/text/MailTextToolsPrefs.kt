package app.sterna.ui.text

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.diegonmarcos.superapp.texttools.TextToolsClient
import org.json.JSONObject

/**
 * cloud-mail's OWN text-tool settings, and the reason there is a second copy of them.
 *
 * The four Text pages — AI Model Routing, Text Enhancement, Text Resume, Translation — used to be
 * the Cloud Keyboard's pages, opened from cloud-mail by an intent. One store, one set of values,
 * owned by the keyboard. Tapping a row here left this app; changing anything changed the keyboard
 * everywhere. The owner asked for cloud-mail to have its own set, and this file is that set.
 *
 * SEPARATE BY THE PLATFORM, NOT BY A NAMING CONVENTION. These are cloud-mail's SharedPreferences,
 * in cloud-mail's data directory, under cloud-mail's uid. The two apps declare no `sharedUserId`,
 * no settings ContentProvider and no shared backup agent, so Android itself is what keeps the two
 * stores apart: there is no key cloud-mail can write that Cloud Keyboard can read, and none it
 * can read that Cloud Keyboard can write. That is why the keys below can safely reuse the
 * keyboard's own names — same name, different file, different app, no relationship.
 *
 * WHAT IS STILL SHARED, ON PURPOSE: the engine behind [TextToolsClient] and the provider API KEY.
 * Choosing a model is a setting; holding the credential for it is an account. The owner asked to
 * be able to edit their settings in mail, not to keep a second copy of their key on the device.
 *
 * @see MailAiRegistry for the prompts and model lists these ids resolve against.
 */
object MailTextToolsPrefs {

    /**
     * cloud-mail's own preference file. Named for the feature, as this app's other stores are.
     * NOT the default `<package>_preferences` file: these values are read on a background thread
     * during seeding, and keeping them out of the file the rest of Settings edits means a seed
     * cannot contend with an unrelated write.
     */
    private const val FILE = "text_tools"

    /** Provider id, e.g. "openrouter". */
    const val KEY_PROVIDER = "ai_provider"

    /** + provider id → the model id chosen for that provider. */
    const val KEY_MODEL_PREFIX = "ai_model_"

    /** Text Enhancement: which style, and the three lines appended after it. */
    const val KEY_ENHANCE_STYLE = "enhance_style"
    const val KEY_ENHANCE_TONE = "enhance_tone"
    const val KEY_ENHANCE_LENGTH = "enhance_length"
    const val KEY_ENHANCE_LANGUAGE = "enhance_language"

    /** Text Resume: which summary shape. */
    const val KEY_SUMMARY_STYLE = "summary_style"

    /** Translation: BCP-47 target tag; "" = the engine's own default. */
    const val KEY_TRANSLATE_TARGET = "translate_default_target"

    /**
     * Set once the values below have been seeded from whatever the owner had already configured.
     *
     * Its ABSENCE is what triggers [seedFromKeyboard], so it is written only after a seed that
     * actually got an answer. A seed attempted while nothing is bound must leave this unset and
     * try again later: marking the app seeded because the keyboard happened to be unreachable for
     * one call is how a migration silently resets someone's settings to defaults.
     */
    private const val KEY_SEEDED = "seeded_from_keyboard"

    /** [TextToolsClient.settingsSnapshot]'s object of provider id → chosen model id. */
    private const val SNAPSHOT_MODELS = "ai_models"

    private const val TAG = "MailTextToolsPrefs"

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---- reads: every one falls back to this app's registry default, never to the keyboard ----

    fun providerId(context: Context): String =
        prefs(context).getString(KEY_PROVIDER, null) ?: MailAiRegistry.defaultProvider

    fun modelId(context: Context, providerId: String = providerId(context)): String {
        val provider = MailAiRegistry.provider(providerId)
        return prefs(context).getString(KEY_MODEL_PREFIX + provider.id, null)
            ?.takeIf { it.isNotBlank() }
            ?: provider.defaultModel
    }

    fun enhanceStyleId(context: Context): String =
        prefs(context).getString(KEY_ENHANCE_STYLE, null) ?: MailAiRegistry.defaultStyle

    fun enhanceToneId(context: Context): String =
        prefs(context).getString(KEY_ENHANCE_TONE, null) ?: MailAiRegistry.defaultTone

    fun enhanceLengthId(context: Context): String =
        prefs(context).getString(KEY_ENHANCE_LENGTH, null) ?: MailAiRegistry.defaultLength

    fun enhanceLanguageId(context: Context): String =
        prefs(context).getString(KEY_ENHANCE_LANGUAGE, null) ?: MailAiRegistry.defaultLanguage

    fun summaryStyleId(context: Context): String =
        prefs(context).getString(KEY_SUMMARY_STYLE, null) ?: MailAiRegistry.defaultSummary

    fun translateTarget(context: Context): String =
        prefs(context).getString(KEY_TRANSLATE_TARGET, null).orEmpty()

    // ---- writes: the whole point of the split. Each one lands in THIS app's file only. ----

    fun put(context: Context, key: String, value: String) {
        prefs(context).edit().putString(key, value).apply()
    }

    /** The model for one provider, so switching provider and back remembers each choice. */
    fun putModel(context: Context, providerId: String, modelId: String) =
        put(context, KEY_MODEL_PREFIX + providerId, modelId)

    // ---- the composed prompts a run sends. Built from THIS app's values, and sent whole. ----

    fun enhancePrompt(context: Context): String = MailAiRegistry.enhancePrompt(
        enhanceStyleId(context),
        enhanceToneId(context),
        enhanceLengthId(context),
        enhanceLanguageId(context),
    )

    fun summaryPrompt(context: Context): String = MailAiRegistry.summaryPrompt(summaryStyleId(context))

    /** Whether this app's chosen summary prompt asked the model for a list. */
    fun summaryWantsBullets(context: Context): Boolean =
        MailAiRegistry.summary(summaryStyleId(context)).bullets

    // ---- migration ----

    fun isSeeded(context: Context): Boolean = prefs(context).getBoolean(KEY_SEEDED, false)

    /**
     * Adopt, ONCE, whatever the owner had already configured, so the split does not reset them.
     *
     * Before this change cloud-mail had no settings of its own: every Text page it offered was the
     * keyboard's, and every value the owner picked on one of those pages was stored by the
     * keyboard. Giving cloud-mail its own store and starting it at the registry defaults would
     * therefore look, to the owner, exactly like their configuration being thrown away — the model
     * they chose, the tone they pinned, the summary shape they preferred, all quietly back to
     * factory. So the first run reads what is there and keeps it.
     *
     * WHAT IT ADOPTS: provider, the chosen model for each provider, Enhance style/tone/length/
     * output language, the Resume shape, the translation target. WHAT IT DOES NOT: the API key,
     * which the snapshot does not carry and this app has no place to put.
     *
     * WHAT A FRESH INSTALL GETS: the defaults baked into `build.json::mail_ai` — the same values
     * the keyboard ships with, because the block began as a copy of the keyboard's. A device with
     * no Cloud Keyboard installed gets those too, and stays unseeded so that installing the
     * keyboard later still brings its settings across on the next visit to a Text page.
     *
     * ONLY EVER A STARTING POINT. After this runs the two stores are unrelated; editing one never
     * touches the other, and this function never runs again.
     *
     * BLOCKING — it is a binder call. Callers put it on an IO dispatcher.
     */
    fun seedFromKeyboard(context: Context, client: TextToolsClient) {
        if (isSeeded(context)) return
        val raw = client.settingsSnapshot()
        if (raw.isNullOrBlank()) {
            // Nothing bound, or an older keyboard without the method. Stay unseeded and try
            // again next time rather than declaring the owner's settings to be the defaults.
            Log.i(TAG, "no settings snapshot available yet — leaving unseeded, will retry")
            return
        }
        val snapshot = runCatching { JSONObject(raw) }.getOrElse {
            Log.w(TAG, "settings snapshot was not JSON — leaving unseeded", it)
            return
        }
        val edit = prefs(context).edit()

        // Only ids THIS app's registry knows. The two registries are separate copies now and the
        // keyboard's may hold a style or a model that cloud-mail's does not; adopting one of those
        // would store a value no screen here can display or change, which is the uneditable state
        // this whole change exists to end.
        fun adopt(key: String, known: List<MailAiRegistry.Style>) {
            val id = snapshot.optString(key).takeIf { it.isNotEmpty() } ?: return
            if (known.any { it.id == id }) edit.putString(key, id)
        }
        adopt(KEY_ENHANCE_STYLE, MailAiRegistry.styles)
        adopt(KEY_ENHANCE_TONE, MailAiRegistry.tones)
        adopt(KEY_ENHANCE_LENGTH, MailAiRegistry.lengths)
        adopt(KEY_ENHANCE_LANGUAGE, MailAiRegistry.languages)
        adopt(KEY_SUMMARY_STYLE, MailAiRegistry.summaries)

        snapshot.optString(KEY_PROVIDER).takeIf { id -> MailAiRegistry.providers.any { it.id == id } }
            ?.let { edit.putString(KEY_PROVIDER, it) }

        snapshot.optJSONObject(SNAPSHOT_MODELS)?.let { models ->
            MailAiRegistry.providers.forEach { provider ->
                val chosen = models.optString(provider.id).takeIf { it.isNotEmpty() } ?: return@forEach
                if (provider.models.any { it.id == chosen }) {
                    edit.putString(KEY_MODEL_PREFIX + provider.id, chosen)
                }
            }
        }

        // A target tag is free text from an engine's language list rather than a registry id, so
        // there is nothing here to validate it against; it is taken as given, and an empty one
        // means "the engine's default", which is also this app's default.
        edit.putString(KEY_TRANSLATE_TARGET, snapshot.optString(KEY_TRANSLATE_TARGET))

        edit.putBoolean(KEY_SEEDED, true).apply()
        Log.i(TAG, "seeded cloud-mail's text-tool settings from the keyboard's, once")
    }
}
