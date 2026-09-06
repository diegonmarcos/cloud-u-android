// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import android.util.Base64
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * SuperApp addition — THE shared LLM call path behind Settings → "AI Model Routing".
 *
 * Registry = build.json::keyboard_ai, baked by libs/keyboard/build.gradle into
 * BuildConfig.AI_ROUTING_B64 (providers, model lists, enhancement prompts). User
 * choices live in the keyboard prefs: PREF_AI_PROVIDER, PREF_AI_TOKEN_PREFIX+provider,
 * PREF_AI_MODEL_PREFIX+provider — the same SharedPreferences the LanguageTool URL and
 * every other keyboard setting use (there is no other keyboard secret store).
 *
 * Every provider speaks the OpenAI chat-completions shape (the mesh bridge
 * my-ai_claude-api mimics it, OpenRouter is it), so [complete] is one POST.
 * Consumers: [TextEnhancer] (ENHANCE toolbar key), [GrammarChecker] mode "ai",
 * and translation once it opts in. Blocking — never call on the IME main thread.
 */
object AiRouter {
    class Provider(val id: String, val label: String, val url: String, val needsToken: Boolean,
                   val defaultModel: String, val models: List<String>)
    class Style(val id: String, val label: String, val prompt: String)

    private val registry: JSONObject by lazy {
        JSONObject(String(Base64.decode(BuildConfig.AI_ROUTING_B64, Base64.DEFAULT), Charsets.UTF_8))
    }
    val providers: List<Provider> by lazy {
        val o = registry.getJSONObject("providers")
        o.keys().asSequence().map { id ->
            val p = o.getJSONObject(id)
            Provider(id, p.getString("label"), p.getString("url"), p.optBoolean("needs_token", true),
                p.getString("default_model"), p.getJSONArray("models").toStringList())
        }.toList()
    }
    val styles: List<Style> by lazy {
        val o = registry.getJSONObject("styles")
        o.keys().asSequence().map { id -> val s = o.getJSONObject(id); Style(id, s.getString("label"), s.getString("prompt")) }.toList()
    }
    val defaultProvider: String get() = registry.getString("default_provider")
    val defaultStyle: String get() = registry.getString("default_style")
    val timeoutMs: Int get() = registry.optInt("timeout_ms", 30_000)
    /** Field-text cap sent to the model; also what the enhancer reads around the cursor. */
    val maxChars: Int get() = registry.optInt("max_chars", 4096)

    private fun JSONArray.toStringList() = (0 until length()).map { getString(it) }

    fun provider(context: Context): Provider {
        val id = context.prefs().getString(Settings.PREF_AI_PROVIDER, defaultProvider) ?: defaultProvider
        return providers.firstOrNull { it.id == id } ?: providers.first { it.id == defaultProvider }
    }
    fun model(context: Context, p: Provider = provider(context)): String =
        context.prefs().getString(Settings.PREF_AI_MODEL_PREFIX + p.id, p.defaultModel)?.takeIf { it.isNotBlank() } ?: p.defaultModel
    fun token(context: Context, p: Provider = provider(context)): String =
        context.prefs().getString(Settings.PREF_AI_TOKEN_PREFIX + p.id, "")?.trim() ?: ""
    fun style(context: Context): Style {
        val id = context.prefs().getString(Settings.PREF_ENHANCE_STYLE, defaultStyle) ?: defaultStyle
        return styles.firstOrNull { it.id == id } ?: styles.first { it.id == defaultStyle }
    }
    fun styleById(id: String): Style = styles.firstOrNull { it.id == id } ?: styles.first { it.id == defaultStyle }

    /** Thrown when the selected provider needs a key and none is set — the settings screen is the fix. */
    class NoTokenException(val provider: Provider) : IllegalStateException("no API key for ${provider.label}")

    /**
     * One chat completion: [system] instructions + [user] text → assistant text.
     * Throws on any failure (no token, HTTP != 200, timeout, malformed reply); callers
     * turn the exception into a visible message. Never call on the main thread.
     */
    @JvmStatic
    fun complete(context: Context, system: String, user: String): String {
        val p = provider(context)
        val token = token(context, p)
        if (p.needsToken && token.isEmpty()) throw NoTokenException(p)
        val body = JSONObject()
            .put("model", model(context, p))
            .put("temperature", 0.2)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
            .toString()
        val conn = (URL(p.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            // The mesh bridge can take long (its own limit is 180 s); the keyboard caps
            // far lower so a stuck call never leaves the user staring at "Enhancing…".
            connectTimeout = 5000
            readTimeout = timeoutMs
            doOutput = true
            instanceFollowRedirects = false
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            if (token.isNotEmpty()) setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code != 200) {
                val err = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                val msg = runCatching { JSONObject(err ?: "").getJSONObject("error").getString("message") }.getOrNull()
                throw IllegalStateException("${p.label} HTTP $code${msg?.let { ": $it" } ?: ""}")
            }
            val reply = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            return reply.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
        } finally {
            conn.disconnect()
        }
    }
}
