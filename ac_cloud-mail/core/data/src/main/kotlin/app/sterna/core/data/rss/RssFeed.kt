package app.sterna.core.data.rss

/**
 * RSS / Atom feed models (#465). Two wire formats, one view: a channel of titled, dated entries,
 * each pointing at a page that opens in the app's own mini-browser.
 */

/** A feed as the reader lists it: the channel's identity and its articles. */
data class RssFeed(
    /** Channel (RSS) or feed (Atom) title. */
    val title: String,
    /** The channel's site, or "" when the feed said none. */
    val link: String,
    /** Channel summary/description; "" when absent. */
    val description: String,
    val items: List<RssItem>,
)

/** One article the feed published. Every field is "" when the source omitted it. */
data class RssItem(
    val title: String,
    /** Absolute or relative; resolved against the feed URL when opening it. */
    val link: String,
    /** The date the feed wrote, verbatim — the reader shows what the author published. */
    val published: String,
    /** A summary or full body, as the feed carries it. */
    val summary: String,
)