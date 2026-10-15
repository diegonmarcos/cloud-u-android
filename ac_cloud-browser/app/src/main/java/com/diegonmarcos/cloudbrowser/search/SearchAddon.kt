package com.diegonmarcos.cloudbrowser.search

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import com.diegonmarcos.cloudbrowser.BuildConfig
import com.diegonmarcos.cloudsearch.core.SearchConfig
import com.diegonmarcos.superapp.ai.AiConfig
import com.diegonmarcos.superapp.ai.AiHost
import com.diegonmarcos.superapp.ai.AiMemoryPrefs
import com.diegonmarcos.superapp.ai.AiMemorySessions
import com.diegonmarcos.superapp.ai.AiOpenRouterBackend
import com.diegonmarcos.superapp.ai.AiSession
import com.diegonmarcos.superapp.ai.AiSink
import com.diegonmarcos.superapp.ai.AiStandardHost
import com.diegonmarcos.superapp.ai.AiToken
import com.diegonmarcos.superapp.ai.AiTokenSource
import com.diegonmarcos.superapp.browser.BrowserSearch
import com.diegonmarcos.superapp.browser.BrowserSearchEngine
import com.diegonmarcos.superapp.texttools.TextToolsClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * #802 I8 the Search add-on's engine side: cloud-search's own declaration (baked from
 * ac_cloud-search/build.json::search as BuildConfig.SEARCH_CONFIG_B64) for the engines, and libs:ai for the
 * chat: its OpenRouter backend, its sessions and its shared contract (baked from assets/ai/ai.json as
 * BuildConfig.AI_CONFIG_B64), linked by reference. The OpenRouter token is the fleet Account's, read for
 * each send into a local and never stored, logged or answered; the only thing said about it is whether one
 * is there. Chat sessions live in memory for the life of the process.
 */
class SearchAddon(val cfg: SearchConfig, val ai: AiConfig = fromBuild()) {

    data class Outcome(val session: AiSession, val error: String?, val citations: List<String>)

    private val sessions = AiMemorySessions()

    fun engines(): List<BrowserSearchEngine> = cfg.engines.map { BrowserSearchEngine(it.id, it.label, it.url) }

    fun enginesJson(): JSONArray = JSONArray().also { a ->
        cfg.engines.forEach { a.put(JSONObject().put("id", it.id).put("label", it.label).put("url", it.url)) }
    }

    /** The results URL for [q] on [engineId] (the first declared engine when absent), or null for an unknown id. */
    fun searchUrl(q: String, engineId: String?): String? {
        val e = engines().let { all -> if (engineId.isNullOrBlank()) all.firstOrNull() else all.firstOrNull { it.id == engineId } }
            ?: return null
        return BrowserSearch.searchUrl(q, e)
    }

    fun sessionsJson(): JSONArray = JSONArray().also { a ->
        sessions.all().forEach {
            a.put(JSONObject().put("id", it.id).put("title", it.title).put("model", it.model).put("updated", it.updated).put("turns", it.messages.size))
        }
    }

    /** The OpenRouter backend of libs:ai on the token [token] reads at each call (value or the reason there is none). */
    fun backend(token: () -> Pair<String?, String>) =
        AiOpenRouterBackend(ai, "cloud-browser", AiTokenSource { token().let { (v, why) -> AiToken(v, why) } })

    /** The AI page's host (libs:ai's standard host on this add-on's sessions); [open] opens a source or result as a tab. */
    fun host(app: Context, open: (String) -> Unit): AiHost =
        AiStandardHost(ai, sessions, AiMemoryPrefs(ai.defaultModel), direct = backend { accountToken(app, ai.accountProvider) }, opener = open)

    /** One send (blocking: call off the main thread). [token] is asked for only after the message is recorded. */
    fun send(sessionId: String?, text: String, model: String?, web: Boolean, token: () -> Pair<String?, String>): Outcome {
        val m = model?.takeIf { it.isNotBlank() } ?: ai.defaultModel
        val host = AiStandardHost(ai, sessions, AiMemoryPrefs(m, web), direct = backend(token))
        val prior = sessionId?.let { id -> sessions.all().firstOrNull { it.id == id } } ?: host.newSession(m, System.currentTimeMillis())
        val o = host.send(prior, text, m, web, System.currentTimeMillis(), AiSink.None)
        return Outcome(o.session, o.error, o.citations)
    }

    /** The debug answer for a send: the session and the last message, never a header or the token. */
    fun outcomeJson(o: Outcome): JSONObject = JSONObject().put("ok", o.error == null).put("session", o.session.id)
        .put("model", o.session.model).put("error", o.error ?: JSONObject.NULL)
        .put("message", o.session.messages.lastOrNull()?.takeIf { it.role == "assistant" }?.content ?: JSONObject.NULL)
        .put("citations", JSONArray(o.citations))

    companion object {
        @Volatile private var instance: SearchAddon? = null
        @Volatile private var tools: TextToolsClient? = null

        /** The shared AI contract baked into this build (BuildConfig.AI_CONFIG_B64). */
        private fun fromBuild(): AiConfig = AiConfig.parse(String(java.util.Base64.getDecoder().decode(BuildConfig.AI_CONFIG_B64)))

        fun get(b64: String): SearchAddon = instance ?: synchronized(this) {
            instance ?: SearchAddon(SearchConfig.parse(String(Base64.decode(b64, Base64.DEFAULT)))).also { instance = it }
        }

        /** The fleet Account's token for [provider] (cloud-search's Account.readAccount shape, same wording). */
        fun accountToken(ctx: Context, provider: String): Pair<String?, String> {
            val c = tools ?: synchronized(this) { tools ?: TextToolsClient(ctx.applicationContext).also { tools = it } }
            if (c.isServingAppInstalled()) {
                val until = SystemClock.elapsedRealtime() + 3_000L
                while (!c.isConnected() && SystemClock.elapsedRealtime() < until) Thread.sleep(100)
            }
            val r = c.revealAiKey(provider)
            return r.text?.takeIf { it.isNotBlank() } to (r.error ?: "no $provider token in the fleet Account")
        }
    }
}
