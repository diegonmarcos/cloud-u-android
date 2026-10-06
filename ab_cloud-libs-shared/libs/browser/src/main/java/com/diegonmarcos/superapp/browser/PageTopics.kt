package com.diegonmarcos.superapp.browser

import org.json.JSONArray
import org.json.JSONObject

/**
 * #886 "Summarise page by topics": the page as a short list of topics, each with a few points.
 * Pure and JVM-tested ([PageTopicsTest]).
 *
 * TWO WAYS TO PRODUCE IT, chosen by the same engine setting as Translate ([PageTranslate.ENGINES]):
 *  - OpenRouter (an LLM through the text-tools binder, the key staying in the serving app): the model
 *    groups the page into topics ([prompt], read back by [parseTopics]);
 *  - On-device ML: the translation library cannot write a summary, and this screen says so in words
 *    ([ON_DEVICE_NOTE]). What it shows instead is the page's OWN outline ([outline]): its headings as
 *    topics, the key sentences of each section as points — no model, no network.
 */
object PageTopics {

    data class Section(val heading: String, val level: Int, val text: String)
    data class Page(val title: String, val lang: String, val sections: List<Section>)
    data class Topic(val title: String, val points: List<String>)

    const val ON_DEVICE_NOTE =
        "On-device ML can translate but cannot write a summary. This is the page's own outline (its headings and " +
            "key sentences). Choose OpenRouter under Configs ▸ Translate & summary ▸ Translate with for an AI summary by topics."

    fun parsePage(json: String?): Page? {
        val o = runCatching { JSONObject(json ?: return null) }.getOrNull() ?: return null
        val arr = o.optJSONArray("sections") ?: return null
        val secs = (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { Section(it.optString("h"), it.optInt("level"), it.optString("text")) }
        }
        return if (secs.isEmpty()) null else Page(o.optString("title"), o.optString("lang"), secs)
    }

    /** The text sent to the model: the title, then each section under its heading, cut at [cap] characters. */
    fun llmInput(page: Page, cap: Int): String {
        val sb = StringBuilder()
        if (page.title.isNotBlank()) sb.append("# ").append(page.title).append("\n\n")
        for (s in page.sections) {
            if (s.heading.isNotBlank()) sb.append("## ").append(s.heading).append('\n')
            if (s.text.isNotBlank()) sb.append(s.text).append("\n\n")
            if (sb.length >= cap) break
        }
        return sb.toString().take(cap)
    }

    fun prompt(targetName: String, maxTopics: Int = 6): String =
        "You summarise web pages by topic. The user message is the text of one page (headings marked with #). " +
            "Group its content into 3 to $maxTopics topics. Reply with ONLY a JSON object of the form " +
            "{\"topics\":[{\"title\":\"short topic title\",\"points\":[\"one concise sentence\",\"…\"]}]} — each topic with " +
            "2 to 4 points, in the order the page covers them, written in $targetName. Facts from the page only; no opinion, no extra text."

    /** The model's reply as topics: the first JSON object with a `topics` array; anything else as one topic of its lines. */
    fun parseTopics(reply: String?): List<Topic> {
        val text = reply?.trim().orEmpty()
        if (text.isEmpty()) return emptyList()
        val from = text.indexOf('{'); val to = text.lastIndexOf('}')
        if (from >= 0 && to > from) {
            val arr = runCatching { JSONObject(text.substring(from, to + 1)).optJSONArray("topics") }.getOrNull()
            val topics = arr?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    val t = a.optJSONObject(i) ?: return@mapNotNull null
                    val pts = t.optJSONArray("points")?.let { p -> (0 until p.length()).map { p.optString(it).trim() }.filter { it.isNotEmpty() } }.orEmpty()
                    val title = t.optString("title").trim()
                    if (title.isEmpty() && pts.isEmpty()) null else Topic(title.ifEmpty { "Topic ${i + 1}" }, pts)
                }
            }.orEmpty()
            if (topics.isNotEmpty()) return topics
        }
        val lines = text.lines().map { it.trim().trimStart('-', '*', '•', ' ') }.filter { it.isNotEmpty() }
        return listOf(Topic("Summary", lines))
    }

    /** The page's own outline: one topic per heading (text before the first heading is "Introduction"), up to [maxTopics]. */
    fun outline(page: Page, maxTopics: Int = 8, pointsPerTopic: Int = 3): List<Topic> =
        page.sections.mapNotNull { s ->
            val pts = PageSummary.extractive(s.text, pointsPerTopic, minChars = 25).lines()
                .map { it.removePrefix("• ").trim() }.filter { it.isNotEmpty() }
            val title = s.heading.ifBlank { if (s.level == 0) "Introduction" else "" }
            if (pts.isEmpty()) null else Topic(title.ifEmpty { "Topic" }, pts)
        }.take(maxTopics)

    /** [topics] as plain text, for copying / sharing. */
    fun asText(topics: List<Topic>): String =
        topics.joinToString("\n\n") { t -> t.title + "\n" + t.points.joinToString("\n") { "• $it" } }

    fun toJson(topics: List<Topic>): JSONArray = JSONArray().also { a ->
        topics.forEach { t -> a.put(JSONObject().put("title", t.title).put("points", JSONArray(t.points))) }
    }
}
