package com.diegonmarcos.cloudcalc.decide

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.diegonmarcos.cloudcalc.BuildConfig
import com.diegonmarcos.cloudcalc.Declarations
import com.diegonmarcos.superapp.decisions.Http
import com.diegonmarcos.cloudcalc.jev.JevConfig
import com.diegonmarcos.cloudcalc.jev.Models
import com.diegonmarcos.superapp.decisions.UrlHttp
import com.diegonmarcos.superapp.texttools.TextToolsClient
import org.json.JSONObject

/**
 * Everything the Jev section keeps on the phone (#770), and nothing more:
 *  - the routing declaration: build.json::jev as baked, or the user's edit of that same JSON
 *    (Configs › Routing), validated by [JevConfig.parse] AND against this app's modes first;
 *  - the per-use model choice and the cached decision-model catalogue;
 *  - the OpenRouter token: the fleet Account's, read on use through libs:text-tools and never
 *    stored here, unless the user sets a manual override, which lives only in Keystore-backed
 *    EncryptedSharedPreferences and is never logged, shown whole or sent anywhere but OpenRouter.
 *
 * Token and network calls BLOCK: call them off the main thread.
 */
object JevStore {
    private const val PREFS = "cloud_calc_jev"
    private const val SECRET_PREFS = "cloud_calc_jev_secret"
    private const val K_CONFIG = "config_override"
    private const val K_CATALOG = "catalog"
    private const val K_CATALOG_AT = "catalog_at"
    private const val K_MODEL = "model_"
    private const val K_TOKEN = "openrouter_token"
    private const val ACCOUNT_WAIT_MS = 3000L

    /** The transport every Jev call uses; the Robolectric suites swap in a fake. */
    @Volatile var http: Http = UrlHttp

    /** The Keystore-backed store; null where no Keystore exists (Robolectric) — then no override can be kept. */
    @Volatile var secrets: (Context) -> SharedPreferences? = { ctx ->
        runCatching {
            val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                ctx, SECRET_PREFS, key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrNull()
    }

    /** The fleet Account's token for a provider id; the suites swap in a fake. */
    @Volatile var account: (Context, String) -> Pair<String?, String> = { ctx, provider -> readAccount(ctx, provider) }

    private fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── the routing declaration ─────────────────────────────────────────────────────────────

    val defaultJson: String by lazy {
        JSONObject(String(java.util.Base64.getDecoder().decode(BuildConfig.JEV_CONFIG_B64), Charsets.UTF_8)).toString(2)
    }
    val defaults: JevConfig by lazy { JevConfig.parse(defaultJson) }

    fun isEdited(ctx: Context): Boolean = prefs(ctx).getString(K_CONFIG, null) != null

    /** The declaration in force: the user's edit if it still parses, else the baked default. */
    fun config(ctx: Context): JevConfig =
        prefs(ctx).getString(K_CONFIG, null)?.let { runCatching { JevConfig.parse(it) }.getOrNull() } ?: defaults

    fun configJson(ctx: Context): String = prefs(ctx).getString(K_CONFIG, null) ?: defaultJson

    /** Store [json] as the declaration; null when stored, else every reason it was refused. */
    fun saveConfig(ctx: Context, json: String): String? {
        val cfg = runCatching { JevConfig.parse(json) }.getOrElse { return it.message ?: "invalid" }
        appErrors(cfg).takeIf { it.isNotEmpty() }?.let { return it.joinToString("\n") }
        prefs(ctx).edit().putString(K_CONFIG, json).apply()
        return null
    }

    fun resetConfig(ctx: Context) = prefs(ctx).edit().remove(K_CONFIG).apply()

    /** What only this app can check: every tool's mode and form, and every ask key, exist here. */
    fun appErrors(cfg: JevConfig): List<String> {
        val out = mutableListOf<String>()
        for (t in cfg.route.tools) {
            if (t.action == "timer") continue
            val m = Declarations.mode(t.mode)
            if (m == null) { out += "tool ${t.id}: no mode ${t.mode} in this app"; continue }
            if (t.action == "form" && m.forms.none { it.id == t.form }) out += "tool ${t.id}: mode ${t.mode} has no form ${t.form}"
        }
        if (cfg.route.onError == "expression" && Declarations.mode(cfg.route.fallbackMode) == null)
            out += "route.fallback_mode: no mode ${cfg.route.fallbackMode} in this app"
        for (k in cfg.ask.keys) if (k != JevConfig.ANY_MODE && Declarations.mode(k) == null) out += "ask.$k: no such mode"
        return out
    }

    // ── models ──────────────────────────────────────────────────────────────────────────────

    fun catalogue(ctx: Context): List<Models.Info> = Models.parse(prefs(ctx).getString(K_CATALOG, null))
    fun catalogueAt(ctx: Context): Long = prefs(ctx).getLong(K_CATALOG_AT, 0)

    /** Re-read the live catalogue when older than the TTL (or [force]); null when fresh, else why not. */
    fun refreshCatalogue(ctx: Context, force: Boolean): String? {
        val cfg = config(ctx)
        val fresh = System.currentTimeMillis() - catalogueAt(ctx) < cfg.catalogTtlHours * 3_600_000L
        if (!force && fresh && catalogue(ctx).isNotEmpty()) return null
        val res = runCatching { http.send(cfg.modelsUrl, null, null, cfg.timeoutMs) }.getOrElse { return "network: ${it.message ?: it.javaClass.simpleName}" }
        if (res.code !in 200..299) return "HTTP ${res.code}"
        if (Models.parse(res.body).isEmpty()) return "the catalogue listed no decision model"
        prefs(ctx).edit().putString(K_CATALOG, res.body).putLong(K_CATALOG_AT, System.currentTimeMillis()).apply()
        return null
    }

    /** What the user picked for [use] ("route", "score"), else the declaration's. */
    fun modelChoice(ctx: Context, use: String): String = prefs(ctx).getString(K_MODEL + use, null) ?: config(ctx).model(use)

    fun setModelChoice(ctx: Context, use: String, slug: String?) =
        prefs(ctx).edit().apply { if (slug == null) remove(K_MODEL + use) else putString(K_MODEL + use, slug) }.apply()

    /** The model [use] actually runs on (Models.resolve over the cached catalogue). */
    fun model(ctx: Context, use: String): String = Models.resolve(modelChoice(ctx, use), catalogue(ctx), config(ctx).fallbackModel)

    // ── the token ───────────────────────────────────────────────────────────────────────────

    /** [value] null = none; [source] says where it came from, or why there is none. */
    data class Token(val value: String?, val source: String)

    fun manualToken(ctx: Context): String? = secrets(ctx)?.getString(K_TOKEN, null)?.takeIf { it.isNotBlank() }

    /** Keep (or with null/blank, drop) the manual override; false when no Keystore store exists. */
    fun setManualToken(ctx: Context, token: String?): Boolean {
        val s = secrets(ctx) ?: return false
        s.edit().apply { if (token.isNullOrBlank()) remove(K_TOKEN) else putString(K_TOKEN, token.trim()) }.apply()
        return true
    }

    /** The override while one is set, else the fleet Account's. Blocks on the Account binder. */
    fun token(ctx: Context): Token {
        manualToken(ctx)?.let { return Token(it, SOURCE_MANUAL) }
        val (value, why) = account(ctx, config(ctx).accountProvider)
        return if (value.isNullOrBlank()) Token(null, why) else Token(value, SOURCE_ACCOUNT)
    }

    const val SOURCE_MANUAL = "manual override"
    const val SOURCE_ACCOUNT = "fleet Account"

    @Volatile private var tools: TextToolsClient? = null

    /** TextToolsClient.revealAiKey: the token the SuperApp's Profile wrote from the vault's ai.tokens. */
    private fun readAccount(ctx: Context, provider: String): Pair<String?, String> {
        val c = tools ?: synchronized(this) { tools ?: TextToolsClient(ctx.applicationContext).also { tools = it } }
        if (c.isServingAppInstalled()) {
            val until = SystemClock.elapsedRealtime() + ACCOUNT_WAIT_MS
            while (!c.isConnected() && SystemClock.elapsedRealtime() < until) Thread.sleep(100)
        }
        val r = c.revealAiKey(provider)
        return r.text to "fleet Account: ${r.error ?: "no token"}"
    }
}
