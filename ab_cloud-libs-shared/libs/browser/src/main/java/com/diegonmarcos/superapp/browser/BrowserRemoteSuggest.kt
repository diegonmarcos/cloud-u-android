package com.diegonmarcos.superapp.browser

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * #886 the default engine's QUERY SUGGESTIONS (what DuckDuckGo offers as you type), the one place the
 * address bar talks to a network. It is its own file, and gated, because [BrowserSuggest] and
 * [BrowserHistory] must stay free of any network client (test-browser-features-wired.sh holds that):
 *
 *  - only when the `search_suggestions` setting is on (Configs ▸ Search; it says in words that what is
 *    typed goes to the engine), never for an empty or URL-looking entry, and never from a private tab;
 *  - only to the engine's own declared `suggest` endpoint (build.json::ui.browser.search_engines[].suggest),
 *    over https, with the OS resolver (HttpURLConnection → Android's resolver, no own DNS);
 *  - no cookies, no identifier, a short timeout, and any failure is "no suggestions", never an error.
 *
 * The answer is the OpenSearch suggestions shape `["query", ["s1", "s2", …]]`.
 */
object BrowserRemoteSuggest {

    /** The suggestion URL for [query], or null when the engine declares no feed or it is not https. */
    fun urlFor(template: String?, query: String): String? {
        val t = template?.takeIf { it.startsWith("https://") && it.contains(BrowserSearchEngine.QUERY) } ?: return null
        return t.replace(BrowserSearchEngine.QUERY, URLEncoder.encode(query.trim(), "UTF-8"))
    }

    /** Whether to ask at all. */
    fun shouldAsk(enabled: Boolean, query: String, privateTab: Boolean): Boolean =
        enabled && !privateTab && query.trim().length >= 2 && !BrowserSearch.isUrlLike(query)

    /** The strings out of an OpenSearch suggestions body; empty for anything else. */
    fun parse(body: String?): List<String> = runCatching {
        val arr = JSONArray(body ?: return emptyList())
        val list = arr.optJSONArray(1) ?: return emptyList()
        (0 until list.length()).map { list.optString(it) }.filter { it.isNotBlank() }.take(10)
    }.getOrDefault(emptyList())

    /** BLOCKING: call off the main thread. */
    fun fetch(template: String?, query: String, timeoutMs: Int = 2500): List<String> {
        val url = urlFor(template, query) ?: return emptyList()
        return runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
                c.requestMethod = "GET"; c.instanceFollowRedirects = false
                c.setRequestProperty("Accept", "application/json")
                if (c.responseCode != 200) emptyList() else parse(c.inputStream.bufferedReader().use { it.readText().take(64_000) })
            } finally { c.disconnect() }
        }.getOrDefault(emptyList())
    }
}
