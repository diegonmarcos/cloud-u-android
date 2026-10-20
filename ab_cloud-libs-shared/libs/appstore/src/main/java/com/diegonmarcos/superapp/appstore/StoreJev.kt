package com.diegonmarcos.superapp.appstore

import android.content.Context
import com.diegonmarcos.superapp.decisions.link.Advice
import com.diegonmarcos.superapp.decisions.link.DecisionsLink
import org.json.JSONObject

/**
 * #882 (Jev wave 1, spec section 3 item 6): the shelf a Store row with NO declared shelf is filed on.
 *
 * Today a row that neither the lib categories nor the host's classification files is drawn under "Other". The
 * decision model may suggest one of the EXISTING shelves for it. It never moves a filed row, never creates a
 * shelf, never blocks a draw (the first draw is today's; a suggestion made meanwhile is used on the next), and
 * every doubt leaves the row exactly where it was. A suggestion nobody corrects counts as accepted after a week.
 */
object StoreJev {

    private const val TTL_MS = 24L * 60 * 60 * 1000
    private val memo = Advice.Suggestions(256)

    /** The shelf to draw [id] under when nothing filed it: a standing suggestion that names one of [shelves]. */
    fun shelfFor(id: String, shelves: Collection<AppStoreHost.Shelf>, now: Long = System.currentTimeMillis()): AppStoreHost.Shelf? =
        memo.peek(id, now)?.pick?.let { pick -> pick(pick, shelves) }

    /** The shelf whose heading is [pick], if any. */
    fun pick(pick: String?, shelves: Collection<AppStoreHost.Shelf>): AppStoreHost.Shelf? =
        pick?.takeIf { it.isNotEmpty() }?.let { h -> shelves.firstOrNull { it.heading == h } }

    fun questions(shelves: Collection<AppStoreHost.Shelf>): JSONObject {
        val crit = JSONObject()
        shelves.map { it.heading }.distinct().forEach { crit.put(it, "Apps of the kind \"$it\"") }
        return JSONObject().put(
            "shelf", JSONObject().put("type", "choice").put(
                "instructions", "Which shelf of an app store fits the app with id `id` and name `name`?",
            ).put("criteria", crit),
        )
    }

    /** Start (never wait for) a suggestion for [rows] that no shelf claims. */
    fun ask(ctx: Context, rows: List<Pair<String, String>>, shelves: Collection<AppStoreHost.Shelf>) {
        val headings = shelves.map { it.heading }.distinct()
        if (headings.size < 2) return
        val app = ctx.applicationContext
        for ((id, name) in rows.take(8)) {
            if (memo.peek(id) != null) continue
            memo.peekOrAsk(id, TTL_MS) {
                val o = DecisionsLink.outcome(app, "store_shelf",
                    JSONObject().put("id", id).put("name", name), questions(shelves))
                val pick = o.verdictOrNull?.pick("shelf", headings.toSet())
                Advice.Suggestion(pick.orEmpty(), o.callOrNull).also { s ->
                    if (pick != null) Advice.remember(app, "store_shelf", s, JSONObject().put("shelf", pick))
                }
            }
        }
        Advice.settleDue(app)
    }
}
