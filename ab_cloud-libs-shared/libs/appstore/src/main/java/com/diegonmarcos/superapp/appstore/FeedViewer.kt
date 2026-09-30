package com.diegonmarcos.superapp.appstore

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * #642 — THE STORE'S READ-ONLY FEEDS: commits, and CI-CD runs.
 *
 * The Store already answers "what is installed and what can I install". It
 * could not answer "what changed, and did it build" — the two questions you
 * actually have while looking at a version number. Those are the same shape as
 * each other (a list of dated entries with a state and a link) and a different
 * shape from everything else in here, so they are ONE reader over a
 * declaration rather than two screens.
 *
 * ONE TAB PER DECLARED FEED, and nothing in this file names a repository, an
 * endpoint, a JSON field or a status word. All of that is
 * assets/appstore-feeds.json, the same way [PhoneAppActions.SOURCES_ASSET]
 * owns the install sources: a third feed is an entry there and no edit here.
 * That is also what makes the derivation testable — [labels] can be handed a
 * declaration a test invented, and has to draw it.
 *
 * ONE REQUEST PATH. Everything goes through [SourceResolver.getBody], which is
 * the store's one fetch with the store's one timeout and redirect policy. This
 * file opens no connection of its own.
 *
 * A READER, not an actor. The only thing a row does is open the link the feed
 * itself supplied, so there is no install, write or credential anywhere in
 * here — which is why the declared endpoints are the unauthenticated public
 * ones. A token would buy a reader nothing and would be one more secret on the
 * device.
 */
object FeedViewer {

    const val FEEDS_ASSET = "appstore-feeds.json"

    /** One declared feed. [items] is null when the response IS the array. */
    class Feed(
        val id: String,
        val label: String,
        val blurb: String,
        val url: String,
        val items: String?,
        val ref: String,
        val title: String,
        val subtitle: String,
        val link: String,
        val state: String?,
        val ok: Set<String>,
        val bad: Set<String>,
    )

    /** One row, already rendered down to strings by the templates. */
    class Entry(val ref: String, val title: String, val subtitle: String, val link: String, val state: String)

    // ── the declaration ──────────────────────────────────────────────────────

    /** Every declared feed, in declared order. An unreadable or absent asset is
     *  NO feeds and therefore no tabs — never a half-built one. */
    fun feeds(ctx: Context): List<Feed> = runCatching {
        parse(JSONObject(ctx.assets.open(FEEDS_ASSET).use { it.readBytes().decodeToString() }))
    }.getOrDefault(emptyList())

    /** Split from [feeds] so a test can hand it a declaration of its own
     *  invention: a hardcoded strip passes a test that only ever sees today's
     *  two feeds, and fails the moment it is handed three. */
    fun parse(decl: JSONObject): List<Feed> {
        val array = decl.optJSONArray("feeds") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val f = array.optJSONObject(i) ?: return@mapNotNull null
            val id = f.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val url = f.optString("url").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Feed(id = id, label = f.optString("label", id), blurb = f.optString("blurb"),
                url = url, items = f.optString("items").takeIf { it.isNotEmpty() },
                ref = f.optString("ref"), title = f.optString("title"),
                subtitle = f.optString("subtitle"), link = f.optString("link"),
                state = f.optString("state").takeIf { it.isNotEmpty() },
                ok = words(f.optJSONArray("ok")), bad = words(f.optJSONArray("bad")))
        }
    }

    private fun words(a: JSONArray?): Set<String> =
        (0 until (a?.length() ?: 0)).mapNotNull { a?.optString(it)?.takeIf { s -> s.isNotEmpty() } }.toSet()

    /** The tab labels, in declared order — the derivation the strip renders. */
    fun labels(feeds: List<Feed>): List<String> = feeds.map { it.label }

    // ── the fetch, and the templates ─────────────────────────────────────────

    /**
     * [feed]'s entries, newest first as the endpoint returns them. Blocking:
     * called on a worker thread by [render].
     */
    fun load(feed: Feed): List<Entry> {
        val body = SourceResolver.getBody(feed.url) ?: return emptyList()
        val root = JSONTokener(body).nextValue()
        val array = when {
            feed.items != null -> (root as? JSONObject)?.optJSONArray(feed.items)
            else -> root as? JSONArray
        } ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            Entry(ref = fill(feed.ref, item), title = fill(feed.title, item),
                subtitle = fill(feed.subtitle, item), link = fill(feed.link, item),
                state = feed.state?.let { path(item, it) } ?: "")
        }
    }

    /**
     * Substitute every `{a.b.c}` in [template] with that dotted path out of
     * [item]. A path the item does not carry substitutes EMPTY rather than
     * throwing: a feed that changes shape must degrade to a thinner row, not
     * take the tab down — the row is a reader, and a missing author name is
     * not a reason to show nothing at all.
     */
    fun fill(template: String, item: JSONObject): String =
        TEMPLATE.replace(template) { path(item, it.groupValues[1]) }.trim()

    /** `commit.author.name` out of nested objects. Empty when absent. */
    private fun path(item: JSONObject, dotted: String): String {
        var node: JSONObject = item
        val parts = dotted.split('.')
        for (p in parts.dropLast(1)) node = node.optJSONObject(p) ?: return ""
        return node.opt(parts.last())?.takeIf { it != JSONObject.NULL }?.toString().orEmpty()
    }

    private val TEMPLATE = Regex("\\{([A-Za-z0-9_.]+)\\}")

    // ── the view ─────────────────────────────────────────────────────────────

    /**
     * Draw [feed] into [into]. Fetches on its own thread and posts back, the
     * same rule the fleet rows follow: one slow endpoint must never block the
     * screen that is already drawn.
     *
     * An EMPTY result and a FAILED fetch are drawn differently on purpose.
     * "Nothing to show" and "we could not ask" are different facts, and a
     * reader that renders a failure as an empty list is the quiet-green shape
     * this repo keeps finding.
     */
    fun render(ctx: Context, into: LinearLayout, feed: Feed, open: (String) -> Unit) {
        into.removeAllViews()
        into.addView(caption(ctx, feed.blurb))
        val status = caption(ctx, "loading…")
        into.addView(status)
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        into.addView(list)
        thread(name = "store-feed-${feed.id}") {
            val result = runCatching { load(feed) }
            into.post {
                result.onSuccess { entries ->
                    if (entries.isEmpty()) status.text = "Nothing in this feed."
                    else {
                        status.visibility = View.GONE
                        for (e in entries) list.addView(row(ctx, feed, e, open))
                    }
                }.onFailure { status.text = "Could not read this feed — ${it.message}" }
            }
        }
    }

    private fun row(ctx: Context, feed: Feed, e: Entry, open: (String) -> Unit): View {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1C1C24.toInt())
            setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(ctx, 2), 0, dp(ctx, 2)) }
            isClickable = e.link.isNotEmpty()
            if (e.link.isNotEmpty()) setOnClickListener { open(e.link) }
        }
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        // A value in NEITHER list is neutral: an in-flight run is not a failure,
        // and colouring it red is how a reader invents bad news.
        val colour = when (e.state) {
            in feed.ok -> OK
            in feed.bad -> BAD
            else -> DIM
        }
        head.addView(TextView(ctx).apply {
            // 7 characters is a readable sha and a short run number alike; the
            // field itself is declared, so this trims whatever it was handed.
            text = e.ref.take(7)
            textSize = 11f; typeface = Typeface.MONOSPACE; setTextColor(colour)
            setPadding(0, 0, dp(ctx, 8), 0)
        })
        head.addView(TextView(ctx).apply {
            // Only the FIRST line of a commit message: the body belongs on the
            // page the row opens, not squeezed into a list.
            text = e.title.lineSequence().firstOrNull().orEmpty()
            textSize = 13f; setTextColor(0xFFFFFFFF.toInt()); typeface = Typeface.DEFAULT_BOLD
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        card.addView(head)
        if (e.subtitle.isNotEmpty()) card.addView(caption(ctx, e.subtitle))
        return card
    }

    /** The one place a row's link is opened. Handed in by the host fragment so
     *  this object needs no Activity of its own. */
    fun opener(ctx: Context): (String) -> Unit = { url ->
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    private fun caption(ctx: Context, t: String) = TextView(ctx).apply {
        text = t; textSize = 11f; setTextColor(DIM); setPadding(0, dp(ctx, 2), 0, dp(ctx, 4))
    }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private const val OK = 0xFF48BB78.toInt()
    private const val BAD = 0xFFF56565.toInt()
    private const val DIM = 0x99FFFFFF.toInt()
}
