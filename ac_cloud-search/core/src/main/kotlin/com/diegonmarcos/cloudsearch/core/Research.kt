package com.diegonmarcos.cloudsearch.core

import org.json.JSONObject

/**
 * build.json::search.research: the Research section's news feed. The backend is the fleet's news service (news-gdelt),
 * named by its fleet_endpoints id; the topics are GDELT queries declared as data.
 */
data class ResearchConfig(val service: String, val articlesPath: String, val limit: Int, val timeoutMs: Int, val topics: List<Topic>) {
    data class Topic(val id: String, val label: String, val query: String)
    data class Article(val url: String, val title: String, val domain: String, val seen: String)

    /** The service path for one topic: `/articles?q=<query>&limit=<n>`. */
    fun path(t: Topic): String = articlesPath + "?q=" + Templates.enc(t.query) + "&limit=" + limit

    companion object {
        fun parse(o: JSONObject): ResearchConfig {
            val n = o.getJSONObject("news")
            val t = n.getJSONArray("topics")
            val topics = (0 until t.length()).map { t.getJSONObject(it) }.map { Topic(it.getString("id"), it.getString("label"), it.getString("query")) }
            val cfg = ResearchConfig(n.getString("service"), n.getString("articles_path"), n.getInt("limit"), n.getInt("timeout_ms"), topics)
            require(cfg.topics.isNotEmpty()) { "search.research.news.topics is empty" }
            require(cfg.topics.map { it.id }.toSet().size == cfg.topics.size) { "search.research.news.topics repeats an id" }
            require(cfg.articlesPath.startsWith("/") && cfg.limit in 1..250) { "search.research.news needs a /path and a limit of 1..250" }
            return cfg
        }

        /** The service's `{query,count,articles:[{url,title,seendate,domain,...}]}`; an entry with no http(s) url or no title is dropped, never filled in. */
        fun articles(body: String): List<Article> {
            val a = JSONObject(body).optJSONArray("articles") ?: return emptyList()
            return (0 until a.length()).map { a.getJSONObject(it) }.mapNotNull { o ->
                val url = o.optString("url").trim()
                val title = o.optString("title").trim()
                if (title.isEmpty() || !(url.startsWith("https://") || url.startsWith("http://"))) null
                else Article(url, title, o.optString("domain"), stamp(o.optString("seendate")))
            }.distinctBy { it.url }.sortedByDescending { it.seen }
        }

        /** GDELT's 20261024T101500Z as "2026-10-24 10:15"; anything else is shown as given. */
        fun stamp(s: String): String {
            val m = Regex("^(\\d{4})(\\d{2})(\\d{2})T(\\d{2})(\\d{2})\\d{2}Z$").find(s) ?: return s
            val (y, mo, d, h, mi) = m.destructured
            return "$y-$mo-$d $h:$mi"
        }
    }
}
