package com.diegonmarcos.cloudsearch.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/** One result card, whatever source it came from. [price] is in [currency]; [unit] qualifies it ("h" = per hour). */
data class Listing(
    val id: String,
    val source: String,
    val title: String,
    val subtitle: String,
    val price: Double?,
    val currency: String?,
    val unit: String?,
    val url: String?,
    val image: String?,
    val date: Long?,
    val verified: Boolean,
    val location: String?,
    val remote: Boolean?,
    val tags: List<String>,
) {
    /** The key a saved item and a de-duplication use: unique across sources. */
    val key: String get() = "$source:$id"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("source", source).put("title", title).put("subtitle", subtitle)
        .put("price", price ?: JSONObject.NULL).put("currency", currency ?: JSONObject.NULL).put("unit", unit ?: JSONObject.NULL)
        .put("url", url ?: JSONObject.NULL).put("image", image ?: JSONObject.NULL).put("date", date ?: JSONObject.NULL)
        .put("verified", verified).put("location", location ?: JSONObject.NULL).put("remote", remote ?: JSONObject.NULL)
        .put("tags", JSONArray(tags))

    companion object {
        fun fromJson(o: JSONObject): Listing = Listing(
            id = o.getString("id"), source = o.getString("source"), title = o.getString("title"), subtitle = o.optString("subtitle"),
            price = o.optNum("price"), currency = o.optStr("currency"), unit = o.optStr("unit"), url = o.optStr("url"),
            image = o.optStr("image"), date = o.optNum("date")?.toLong(), verified = o.optBoolean("verified"),
            location = o.optStr("location"), remote = if (o.isNull("remote") || !o.has("remote")) null else o.getBoolean("remote"),
            tags = o.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
        )
    }
}

data class FeedItem(val title: String, val text: String, val url: String?, val date: Long?, val feed: String)

internal fun JSONObject.optStr(k: String): String? = if (!has(k) || isNull(k)) null else optString(k).takeIf { it.isNotBlank() }
internal fun JSONObject.optNum(k: String): Double? = if (!has(k) || isNull(k)) null else optDouble(k).takeUnless { it.isNaN() }

/**
 * The parsers, one per source format, each held to a saved real response in the JVM suite.
 * [parse] is the one dispatch: test/test-search-shell.sh holds every enabled api source's
 * `parser` to a branch here.
 */
object Parsers {
    fun parse(parser: String, body: String, source: SearchConfig.Source): List<Listing> = when (parser) {
        "ba" -> ba(body, source)
        "arbeitnow" -> arbeitnow(body, source)
        "open_prices" -> openPrices(body, source)
        "off_search" -> offSearch(body, source)
        else -> throw IllegalArgumentException("no parser '$parser' for source ${source.id}")
    }

    private fun objects(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }

    /** Midnight UTC of an ISO date (2026-09-24), or of an ISO date-time's date part. */
    fun isoDay(s: String?): Long? = runCatching { LocalDate.parse(s!!.take(10)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()

    /** Bundesagentur für Arbeit, Jobsuche v6: ergebnisliste[]. The detail page is item_url with the reference number. */
    fun ba(body: String, s: SearchConfig.Source): List<Listing> = objects(JSONObject(body).optJSONArray("ergebnisliste")).map { j ->
        val ref = j.getString("referenznummer")
        val place = objects(j.optJSONArray("stellenlokationen")).firstOrNull()?.optJSONObject("adresse")
        val city = place?.optStr("ort")
        val hourly = j.optStr("verguetungsangabe") == "STUNDENLOHN"
        val pay = j.optNum("festgehalt")
        Listing(
            id = ref, source = s.id, title = j.optString("stellenangebotsTitel", j.optString("titel")),
            subtitle = listOfNotNull(j.optStr("firma"), city).joinToString(" • "),
            price = pay, currency = pay?.let { "EUR" }, unit = if (pay == null) null else if (hourly) "h" else "yr",
            url = s.itemUrl.replace("{id}", ref).takeIf { it.isNotBlank() }, image = null,
            date = isoDay(j.optJSONObject("veroeffentlichungszeitraum")?.optStr("von") ?: j.optStr("datumErsteVeroeffentlichung")),
            verified = s.verified, location = city, remote = null,
            tags = buildList {
                if (j.optBoolean("arbeitszeitVollzeit")) add("Full-time")
                j.optStr("hauptberuf")?.let { add(it) }
            },
        )
    }

    /** Arbeitnow's free job-board API: data[] with created_at in epoch seconds. */
    fun arbeitnow(body: String, s: SearchConfig.Source): List<Listing> = objects(JSONObject(body).optJSONArray("data")).map { j ->
        val types = strings(j.optJSONArray("job_types"))
        Listing(
            id = j.getString("slug"), source = s.id, title = j.getString("title"),
            subtitle = listOfNotNull(j.optStr("company_name"), j.optStr("location")).joinToString(" • "),
            price = null, currency = null, unit = null, url = j.optStr("url"), image = null,
            date = j.optNum("created_at")?.let { (it * 1000).toLong() }, verified = s.verified,
            location = j.optStr("location"), remote = j.optBoolean("remote"),
            tags = types + strings(j.optJSONArray("tags")),
        )
    }

    /** Open Prices (Open Food Facts): items[], each a price with its product, shop and proof. */
    fun openPrices(body: String, s: SearchConfig.Source): List<Listing> = objects(JSONObject(body).optJSONArray("items")).map { p ->
        val product = p.optJSONObject("product")
        val shop = p.optJSONObject("location")
        val name = product?.optStr("product_name")?.trim()?.takeIf { it.isNotBlank() } ?: p.optStr("product_name") ?: p.optStr("product_code") ?: "?"
        Listing(
            id = p.get("id").toString(), source = s.id, title = name,
            subtitle = listOfNotNull(product?.optStr("quantity"), shop?.optStr("osm_name"), shop?.optStr("osm_address_city")).joinToString(" • "),
            price = p.optNum("price"), currency = p.optStr("currency"), unit = p.optStr("price_per")?.lowercase(Locale.ROOT),
            url = p.optStr("product_code")?.let { s.itemUrl.replace("{id}", it) }?.takeIf { it.isNotBlank() },
            image = product?.optStr("image_url"), date = isoDay(p.optStr("date")),
            // A price is verified when a proof (receipt or price-tag photo) backs it.
            verified = s.verified && p.optStr("proof_id") != null,
            location = shop?.optStr("osm_address_city"), remote = null,
            tags = listOfNotNull(shop?.optStr("osm_name"), if (p.optBoolean("price_is_discounted")) "Discount" else null),
        )
    }

    /** Open Food Facts search-a-licious: hits[] of products, no price. */
    fun offSearch(body: String, s: SearchConfig.Source): List<Listing> = objects(JSONObject(body).optJSONArray("hits")).map { h ->
        val code = h.getString("code")
        val grade = h.optStr("nutriscore_grade")?.takeIf { it.length == 1 }
        Listing(
            id = code, source = s.id, title = h.optStr("product_name") ?: code,
            subtitle = listOfNotNull(strings(h.optJSONArray("brands")).joinToString(", ").takeIf { it.isNotBlank() }, h.optStr("quantity")).joinToString(" • "),
            price = null, currency = null, unit = null, url = s.itemUrl.replace("{id}", code).takeIf { it.isNotBlank() },
            image = h.optStr("image_url"), date = null, verified = s.verified, location = null, remote = null,
            tags = listOfNotNull(grade?.let { "Nutri-Score ${it.uppercase(Locale.ROOT)}" }),
        )
    }

    /** RSS 2.0 <item>s: title, description (tags stripped), link, pubDate (RFC 1123). */
    fun rss(xml: String, feed: String): List<FeedItem> {
        val f = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            // A feed is remote input: no DTDs, no external entities.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            isExpandEntityReferences = false
        }
        val doc = f.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        val items = doc.getElementsByTagName("item")
        return (0 until items.length).map { i ->
            val e = items.item(i) as org.w3c.dom.Element
            fun child(tag: String): String? = e.getElementsByTagName(tag).item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() }
            FeedItem(
                title = child("title") ?: "",
                text = strip(child("description") ?: ""),
                url = child("link"),
                date = child("pubDate")?.let { d -> runCatching { ZonedDateTime.parse(d, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull() },
                feed = feed,
            )
        }
    }

    fun strip(html: String): String = html.replace(Regex("<[^>]*>"), " ").replace("&nbsp;", " ").replace(Regex("\\s+"), " ").trim()
}
