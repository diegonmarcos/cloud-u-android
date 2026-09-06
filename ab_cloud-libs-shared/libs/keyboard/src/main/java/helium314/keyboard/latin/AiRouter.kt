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
    /** USD per million tokens. */
    class Pricing(val prompt: Double, val completion: Double)
    /** [baked] = registry fallback price (null when the registry has none, e.g. the mesh bridge). */
    class Model(val id: String, val open: Boolean, val baked: Pricing?)
    class Provider(val id: String, val label: String, val url: String, val needsToken: Boolean,
                   val defaultModel: String, val models: List<Model>,
                   val catalogUrl: String?, val pricingAsOf: String?)
    class Style(val id: String, val label: String, val prompt: String)

    private val registry: JSONObject by lazy {
        JSONObject(String(Base64.decode(BuildConfig.AI_ROUTING_B64, Base64.DEFAULT), Charsets.UTF_8))
    }
    val providers: List<Provider> by lazy {
        val o = registry.getJSONObject("providers")
        o.keys().asSequence().map { id ->
            val p = o.getJSONObject(id)
            val models = p.getJSONArray("models").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.map { m ->
                val pr = m.optDouble("prompt"); val co = m.optDouble("completion")
                Model(m.getString("id"), m.optBoolean("open"), if (pr.isNaN() || co.isNaN()) null else Pricing(pr, co))
            }
            Provider(id, p.getString("label"), p.getString("url"), p.optBoolean("needs_token", true),
                p.getString("default_model"), models, p.optString("catalog_url").ifEmpty { null }, p.optString("pricing_as_of").ifEmpty { null })
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
    /** How long a fetched price catalog stays fresh before the settings screen re-fetches it. */
    val catalogTtlMs: Long get() = registry.optLong("catalog_ttl_ms", 86_400_000L)

    // ---- live pricing: provider catalog → prefs cache {"fetched": epochMs, "prices": {id: [prompt, completion]}} ----

    /** Cached live prices for [p] ($/M), with the fetch time; null when never fetched. */
    fun livePricing(context: Context, p: Provider): Pair<Long, Map<String, Pricing>>? {
        val raw = context.prefs().getString(Settings.PREF_AI_PRICING_PREFIX + p.id, null) ?: return null
        return runCatching {
            val o = JSONObject(raw); val prices = o.getJSONObject("prices")
            o.getLong("fetched") to prices.keys().asSequence().associateWith { id ->
                val a = prices.getJSONArray(id); Pricing(a.getDouble(0), a.getDouble(1))
            }
        }.getOrNull()
    }
    fun pricingStale(context: Context, p: Provider): Boolean =
        p.catalogUrl != null && (livePricing(context, p)?.first ?: 0L) + catalogTtlMs < System.currentTimeMillis()
    /** Live price if fetched, else the registry's baked one, else null. */
    fun pricing(context: Context, p: Provider, m: Model): Pricing? = livePricing(context, p)?.second?.get(m.id) ?: m.baked

    /**
     * GET the provider's public model catalog (OpenRouter shape: {"data":[{"id","pricing":{"prompt","completion"}}]},
     * prices in $/token) and cache $/M for the registry's models only. Blocking; throws on failure — the screen
     * keeps showing the baked table then. Never call on the main thread.
     */
    fun refreshPricing(context: Context, p: Provider) {
        val url = p.catalogUrl ?: return
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000; readTimeout = timeoutMs; instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
        }
        val body = try {
            if (conn.responseCode != 200) throw IllegalStateException("${p.label} catalog HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
        val wanted = p.models.map { it.id }.toSet()
        val prices = JSONObject()
        val data = JSONObject(body).getJSONArray("data")
        for (i in 0 until data.length()) {
            val m = data.getJSONObject(i)
            if (m.getString("id") !in wanted) continue
            val pr = m.getJSONObject("pricing")
            prices.put(m.getString("id"), JSONArray().put(pr.getString("prompt").toDouble() * 1e6).put(pr.getString("completion").toDouble() * 1e6))
        }
        if (prices.length() == 0) throw IllegalStateException("${p.label} catalog has none of the registry models")
        context.prefs().edit().putString(Settings.PREF_AI_PRICING_PREFIX + p.id,
            JSONObject().put("fetched", System.currentTimeMillis()).put("prices", prices).toString()).apply()
    }

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
