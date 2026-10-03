package com.diegonmarcos.cloudbrowser.search

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import com.diegonmarcos.cloudsearch.core.Chat
import com.diegonmarcos.cloudsearch.core.SearchConfig
import com.diegonmarcos.superapp.browser.BrowserSearch
import com.diegonmarcos.superapp.browser.BrowserSearchEngine
import com.diegonmarcos.superapp.texttools.TextToolsClient
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * #802 I8 the Search add-on's engine side: cloud-search's own declaration (baked from
 * ac_cloud-search/build.json::search as BuildConfig.SEARCH_CONFIG_B64) and its own chat protocol
 * (search-core [Chat]), linked by reference. The OpenRouter token is the fleet Account's, read for
 * each send into a local and never stored, logged or answered; the only thing said about it is
 * whether one is there. Chat sessions live in memory for the life of the process.
 */
class SearchAddon(val cfg: SearchConfig) {

    data class Outcome(val session: Chat.Session, val error: String?, val citations: List<String>)

    private val sessions = ConcurrentHashMap<String, Chat.Session>()

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
        sessions.values.sortedByDescending { it.updated }.forEach {
            a.put(JSONObject().put("id", it.id).put("title", it.title).put("model", it.model).put("updated", it.updated).put("turns", it.messages.size))
        }
    }

    /** One send (blocking: call off the main thread). [token] is asked for only after the message is recorded. */
    fun send(sessionId: String?, text: String, model: String?, web: Boolean, token: () -> Pair<String?, String>): Outcome {
        val ai = cfg.ai
        val m = model?.takeIf { it.isNotBlank() } ?: ai.defaultModel
        val now = System.currentTimeMillis()
        val prior = sessionId?.let { sessions[it] } ?: Chat.Session(UUID.randomUUID().toString(), "", m, now, emptyList())
        val asked = prior.copy(title = prior.title.ifBlank { Chat.title(text, ai.titleChars) }, model = m, updated = now,
            messages = prior.messages + Chat.Msg("user", text))
        sessions[asked.id] = asked
        val (value, why) = token()
        if (value == null) return Outcome(asked, why, emptyList())
        val res = runCatching { post(ai.chatUrl, Chat.headers(ai, value), Chat.body(ai, m, asked.messages, web, false), ai.timeoutMs) }
        val body = res.getOrNull() ?: return Outcome(asked, res.exceptionOrNull()?.javaClass?.simpleName ?: "no answer", emptyList())
        val reply = Chat.reply(body.second)
        val answer = reply.text ?: return Outcome(asked, reply.error ?: "HTTP ${body.first}", emptyList())
        val answered = asked.copy(messages = asked.messages + Chat.Msg("assistant", answer), updated = System.currentTimeMillis())
        sessions[answered.id] = answered
        return Outcome(answered, null, reply.citations)
    }

    /** The debug answer for a send: the session and the last message, never a header or the token. */
    fun outcomeJson(o: Outcome): JSONObject = JSONObject().put("ok", o.error == null).put("session", o.session.id)
        .put("model", o.session.model).put("error", o.error ?: JSONObject.NULL)
        .put("message", o.session.messages.lastOrNull()?.takeIf { it.role == "assistant" }?.content ?: JSONObject.NULL)
        .put("citations", JSONArray(o.citations))

    private fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Int): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true
            c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
            c.setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            return code to text
        } finally { c.disconnect() }
    }

    companion object {
        @Volatile private var instance: SearchAddon? = null
        @Volatile private var tools: TextToolsClient? = null

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
