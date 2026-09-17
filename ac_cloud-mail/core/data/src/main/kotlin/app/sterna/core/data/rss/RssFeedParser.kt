package app.sterna.core.data.rss

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.Reader

/**
 * What a subscription could not be read as. Each names the reason so the screen shows the exact
 * sentence rather than one catch-all — an RSS reader whose failures all look the same teaches the
 * user that every feed is broken.
 */
enum class RssParseFailure {
    /** The address fetched, but the body was not an RSS 2.0 or Atom document. */
    NOT_A_FEED,
    /** A valid feed, but it contained no articles. */
    EMPTY,
}

/** A feed was parsed, or the specific reason it was not. */
sealed interface RssParseResult {
    data class Ok(val feed: RssFeed) : RssParseResult
    data class Failed(val reason: RssParseFailure) : RssParseResult
}

/**
 * Parser for RSS 2.0 and Atom feeds (#465), built on Android's XmlPullParser — the pull parser the
 * platform ships rather than a feed library. `XmlPullParserFactory` is the canonical way Android
 * hands out KXmlParser, and the same factory on the JVM (kxml2, the very implementation Android
 * bundles) is what the unit tests feed it, so production and test parse through the same
 * `org.xmlpull.v1.XmlPullParser` interface.
 *
 * It recognises the two dialects by their root element, reads only the fields the reader shows,
 * and treats a body that is not a feed it knows as [RssParseFailure.NOT_A_FEED] rather than an
 * exception — fetching and parsing must never take the app down over a stranger's feed.
 */
class RssFeedParser(
    private val createParser: () -> XmlPullParser = { XmlPullParserFactory.newInstance().newPullParser() },
) {
    /** Parse [input] as an RSS 2.0 or Atom document. Never throws on feed content; a body that is
     *  not a recognised feed is reported as a failure, not raised. */
    fun parse(input: Reader): RssParseResult = try {
        val parser = createParser()
        parser.setInput(input)
        parseDocument(parser)
    } catch (_: Exception) {
        RssParseResult.Failed(RssParseFailure.NOT_A_FEED)
    }

    /** The document root selects the dialect; anything else is not a feed this reader knows. */
    private fun parseDocument(parser: XmlPullParser): RssParseResult {
        var rootSeen = false
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                if (!rootSeen) {
                    rootSeen = true
                    return when (parser.name?.lowercase()) {
                        "rss" -> parseRss(parser)
                        "feed" -> parseAtom(parser)
                        else -> RssParseResult.Failed(RssParseFailure.NOT_A_FEED)
                    }
                }
            }
        }
        return RssParseResult.Failed(RssParseFailure.NOT_A_FEED)
    }

    // ---- RSS 2.0: rss > channel > (item*) ----

    private fun parseRss(parser: XmlPullParser): RssParseResult {
        val items = mutableListOf<RssItem>()
        var title = ""
        var link = ""
        var description = ""
        var sawChannel = false
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name?.lowercase()) {
                    "channel" -> sawChannel = true
                    "item" -> items.add(parseRssItem(parser))
                    "title" -> if (title.isEmpty()) title = textOf(parser)
                    "link" -> if (link.isEmpty()) link = textOf(parser)
                    "description" -> if (description.isEmpty()) description = textOf(parser)
                }
            }
        }
        if (!sawChannel) return RssParseResult.Failed(RssParseFailure.NOT_A_FEED)
        val feed = RssFeed(title, link, description, items)
        return if (items.isEmpty()) RssParseResult.Failed(RssParseFailure.EMPTY) else RssParseResult.Ok(feed)
    }

    private fun parseRssItem(parser: XmlPullParser): RssItem {
        val fields = itemFields()
        while (true) {
            parser.next()
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name?.lowercase()) {
                    "title" -> fields.title = textOf(parser)
                    "link" -> fields.link = textOf(parser)
                    "pubdate" -> fields.published = textOf(parser)
                    "description" -> fields.summary = textOf(parser)
                }
                XmlPullParser.END_TAG -> if (parser.name?.lowercase() == "item") break
            }
        }
        return fields.toItem()
    }

    // ---- Atom: feed > (entry*) ----
    // Atom differs from RSS in two places the reader cares about: its link is an ELEMENT carrying
    // an href attribute rather than a text link, and its article is an entry with content in place
    // of RSS's description. Both are honoured below; the rest of the shapes overlap.

    private fun parseAtom(parser: XmlPullParser): RssParseResult {
        val items = mutableListOf<RssItem>()
        var title = ""
        var link = ""
        var description = ""
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name?.lowercase()) {
                    "title" -> if (title.isEmpty()) title = textOf(parser)
                    "link" -> if (link.isEmpty()) link = parser.getAttributeValue(null, "href").orEmpty()
                    "subtitle", "tagline" -> if (description.isEmpty()) description = textOf(parser)
                    "entry" -> items.add(parseAtomEntry(parser))
                }
            }
        }
        val feed = RssFeed(title, link, description, items)
        return if (items.isEmpty()) RssParseResult.Failed(RssParseFailure.EMPTY) else RssParseResult.Ok(feed)
    }

    private fun parseAtomEntry(parser: XmlPullParser): RssItem {
        val fields = itemFields()
        while (true) {
            parser.next()
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name?.lowercase()) {
                    "title" -> fields.title = textOf(parser)
                    "link" -> fields.link = parser.getAttributeValue(null, "href").orEmpty()
                    "published" -> fields.published = textOf(parser)
                    "updated" -> if (fields.published.isEmpty()) fields.published = textOf(parser)
                    "summary", "content" -> fields.summary = textOf(parser)
                }
                XmlPullParser.END_TAG -> if (parser.name?.lowercase() == "entry") break
            }
        }
        return fields.toItem()
    }

    /** The text content of the current element. XmlPullParser reports text and CDATA as separate
     *  events, so a titled span across a tag boundary is accumulated rather than read once. */
    private fun textOf(parser: XmlPullParser): String {
        val sb = StringBuilder()
        while (true) {
            parser.next()
            when (parser.eventType) {
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> sb.append(parser.text)
                XmlPullParser.END_TAG, XmlPullParser.END_DOCUMENT -> return sb.toString().trim()
                else -> {}
            }
        }
    }

    private fun itemFields(): ItemFields = ItemFields()

    private class ItemFields {
        var title = ""
        var link = ""
        var published = ""
        var summary = ""

        fun toItem() = RssItem(title, link, published, summary)
    }
}