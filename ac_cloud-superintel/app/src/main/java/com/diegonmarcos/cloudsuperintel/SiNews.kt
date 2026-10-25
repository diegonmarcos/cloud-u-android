package com.diegonmarcos.cloudsuperintel

import android.content.Context
import com.diegonmarcos.superapp.ai.AiUrlHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What a feed fetch ended with: the [stories], or [error] when the backend could not be reached (an honest empty state, never invented stories). */
data class Feed(val stories: List<Story>, val error: String? = null)

/**
 * The News tab's data: the fleet's news-gdelt service (BuildConfig.NEWS_MESH_URL, then NEWS_PUBLIC_URL with the fleet bearer: [FleetNews], baked from the fleet_endpoints `news` entry),
 * parsed by [News]; the card and story summaries are the gateway's, from the fetched headlines only. Feeds and summaries are
 * cached in memory for the process.
 */
object SiNews {
    private val feeds = HashMap<String, Feed>()
    private val summaries = HashMap<String, String>()
    /** One summary request at a time: a feed of cards must not flood the gateway. */
    private val gate = Mutex()

    fun config(ctx: Context): NewsConfig = NewsConfig.parse(ctx.assets.open(NewsConfig.ASSET).bufferedReader().use { it.readText() })

    suspend fun feed(ctx: Context, topic: NewsTopic, refresh: Boolean = false): Feed = withContext(Dispatchers.IO) {
        val cfg = config(ctx)
        synchronized(feeds) { if (!refresh) feeds[topic.id]?.let { return@withContext it } }
        val articles = ArrayList<Article>()
        var error: String? = null
        for (q in News.queries(topic, cfg.topics)) {
            val r = runCatching { FleetNews.get(ctx, cfg, q) { AiUrlHttp.get(it.first, it.second, cfg.timeoutMs) } }
            val http = r.getOrNull()
            if (http == null) {
                error = r.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
            } else if (http.code !in 200..299) {
                error = "news backend answered ${http.code}"
            } else {
                try { articles += News.parseArticles(http.body) } catch (e: Exception) { error = "unreadable answer: ${e.message}" }
            }
        }
        val stories = News.stories(articles, cfg.similarity)
        Feed(stories, if (stories.isEmpty()) error ?: "no stories right now" else null).also { f -> if (f.error == null) synchronized(feeds) { feeds[topic.id] = f } }
    }

    /** The AI summary of [s] (card: one sentence; story: a cited briefing), from its headlines only; null when the gateway gave none. */
    suspend fun summary(ctx: Context, s: Story, story: Boolean): String? = withContext(Dispatchers.IO) {
        val cfg = config(ctx)
        val key = (if (story) "s:" else "c:") + s.key
        synchronized(summaries) { summaries[key]?.let { return@withContext it } }
        val prompt = (if (story) cfg.storyPrompt else cfg.cardPrompt).replace("{{headlines}}", News.headlines(s, cfg.storySourcesMax))
        gate.withLock {
            synchronized(summaries) { summaries[key] }
                ?: SiAi.complete(ctx, "You summarise news headlines faithfully.", prompt).getOrNull()?.trim()?.also { t -> synchronized(summaries) { summaries[key] = t } }
        }
    }
}
