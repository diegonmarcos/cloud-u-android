package com.diegonmarcos.cloudsuperintel

import org.json.JSONObject

/** One article as the fleet's news-gdelt service returns it. */
data class Article(val url: String, val title: String, val seendate: String, val image: String, val domain: String)

/** One story: articles about the same event, merged. [sources] is the count of distinct domains. */
data class Story(val headline: String, val image: String, val articles: List<Article>) {
    val sources: Int get() = articles.map { it.domain }.distinct().size
    val key: String get() = articles.first().url
}

data class NewsTopic(val id: String, val label: String, val icon: String, val queries: List<String>, val mix: List<String>)

/** assets/si/news.json as Kotlin reads it. */
data class NewsConfig(
    val articlesPath: String, val limit: Int, val timeoutMs: Int, val similarity: Double, val storySourcesMax: Int,
    val cardPrompt: String, val storyPrompt: String, val topics: List<NewsTopic>,
) {
    companion object {
        const val ASSET = "si/news.json"
        fun parse(text: String): NewsConfig {
            val r = JSONObject(text); val b = r.getJSONObject("backend"); val s = r.getJSONObject("summary"); val t = r.getJSONArray("topics")
            fun list(o: JSONObject, k: String) = o.optJSONArray(k)?.let { x -> (0 until x.length()).map { x.getString(it) } } ?: emptyList()
            return NewsConfig(
                b.getString("articles_path"), b.getInt("limit"), b.getInt("timeout_ms"), r.getDouble("cluster_similarity"), r.getInt("story_sources_max"),
                s.getString("card"), s.getString("story"),
                (0 until t.length()).map { t.getJSONObject(it) }.map { NewsTopic(it.getString("id"), it.getString("label"), it.getString("icon"), list(it, "queries"), list(it, "mix")) },
            )
        }
    }
}

/** The feed's parsing and grouping, pure; the fetch itself is [NewsClient]. */
object News {
    /** The service's `{query,count,articles:[...]}`; an entry with no url or title is dropped, never filled in. */
    fun parseArticles(body: String): List<Article> {
        val a = JSONObject(body).optJSONArray("articles") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }.mapNotNull { o ->
            val url = o.optString("url"); val title = o.optString("title").trim()
            if (url.isBlank() || title.isBlank()) null else Article(url, title, o.optString("seendate"), o.optString("socialimage"), o.optString("domain"))
        }
    }

    private fun words(s: String) = Notebook.tokens(s).toSet()

    /** Articles with the same headline words (Jaccard at least [similarity]) become one story; newest first. */
    fun stories(articles: List<Article>, similarity: Double): List<Story> {
        val groups = ArrayList<MutableList<Article>>()
        val sigs = ArrayList<Set<String>>()
        for (a in articles.distinctBy { it.url }.sortedByDescending { it.seendate }) {
            val w = words(a.title)
            val i = sigs.indexOfFirst { s -> val u = (s union w).size; u > 0 && (s intersect w).size.toDouble() / u >= similarity }
            if (i >= 0) groups[i].add(a) else { groups.add(mutableListOf(a)); sigs.add(w) }
        }
        return groups.map { g -> Story(g.first().title, g.firstOrNull { it.image.isNotBlank() }?.image.orEmpty(), g) }
    }

    /** The feed's URL for one query. */
    fun url(base: String, cfg: NewsConfig, query: String, enc: (String) -> String): String =
        base.trimEnd('/') + cfg.articlesPath + "?q=" + enc(query) + "&limit=" + cfg.limit

    /** The numbered headlines a summary prompt carries (at most [max]). */
    fun headlines(s: Story, max: Int): String = s.articles.take(max).mapIndexed { i, a -> "${i + 1}. ${a.title} (${a.domain})" }.joinToString("\n")

    /** The queries a topic fetches: its own, or those of the topics it mixes. */
    fun queries(t: NewsTopic, all: List<NewsTopic>): List<String> =
        if (t.mix.isEmpty()) t.queries else t.mix.flatMap { id -> all.firstOrNull { it.id == id }?.queries.orEmpty() }
}
