package com.diegonmarcos.superapp.feed

import com.diegonmarcos.superapp.core.DataBackendService
import org.json.JSONArray
import org.json.JSONObject

/**
 * libs:feed as a BACKEND: the RSS/Atom fetcher behind [DataBackendService],
 * shipped in Cloud-Lib-Feed.apk.
 *
 * The app no longer links this module at all. It holds the UI and asks for
 * JSON; parsing feeds, dealing with two schemas and eating network errors all
 * live here. That is the whole point of the split - libs are the back end,
 * the app is the front end - and it is why the app's own view model
 * (FeedItem) is its own type rather than this module's [RssClient.Item].
 *
 * Dispatch is an explicit `when`, not reflection: R8 ships enabled and cannot
 * see a reflective call.
 *
 * A PUBLISHED CONTRACT (engine-apk-split move 4): the SuperApp's RSS pane
 * (rss/RemoteFeed.kt) binds this by handshake and compiles nothing of this
 * module, so an edit here ships Cloud-Lib-Feed.apk alone. [methodNames] may only
 * grow; an answer that changes shape ships under a new method name and a higher
 * CONTRACT in the manifest. Held by the contract guard (K4: every method the
 * SuperApp calls is listed here; K6: no app declares or watches this module) and
 * by lib-apks test-engine-services.sh E8 (this engine must be discoverable).
 */
class FeedBackendService : DataBackendService() {

    override fun methodNames(): Array<String> = arrayOf(FETCH)

    override fun dispatch(method: String, args: Array<String>): String = when (method) {
        FETCH -> {
            val url = args.getOrNull(0).orEmpty()
            val max = args.getOrNull(1)?.toIntOrNull() ?: 25
            require(url.isNotBlank()) { "fetch needs a url" }
            // Throwing here is fine and deliberate: DataBackendService turns it
            // into the same {"error": ...} JSON the client already handles, so
            // a dead feed degrades to an empty list with a message rather than
            // taking the caller down.
            JSONArray().apply {
                RssClient.fetch(url, max).forEach { item ->
                    put(JSONObject()
                        .put("title", item.title)
                        .put("link", item.link)
                        .put("date", item.date))
                }
            }.toString()
        }
        else -> throw IllegalArgumentException("unknown method: $method")
    }

    private companion object { const val FETCH = "fetch" }
}
