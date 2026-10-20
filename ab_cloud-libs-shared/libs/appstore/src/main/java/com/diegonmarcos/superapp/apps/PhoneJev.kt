package com.diegonmarcos.superapp.apps

import android.content.Context
import com.diegonmarcos.superapp.decisions.link.Advice
import com.diegonmarcos.superapp.decisions.link.DecisionsLink
import org.json.JSONObject

/**
 * #882 (Jev wave 1, spec section 3 item 7): which phone folder an app no rule claimed belongs in.
 *
 * ONLY for an app [PhoneTaxonomy] would put on the sink (no keyword and no metadata rule claimed it): the
 * decision model may suggest one of the other folders. It never overrules a rule, it never blocks a screen (a
 * suggestion is read from a memo; the first draw after an install is today's, the next draw sees the answer),
 * and EVERY doubt leaves the app on the sink exactly as before: no engine, no consent, the use's evidence gate
 * closed, unsure, a folder that does not exist. A standing suggestion that is never corrected counts as accepted
 * after a week (it is reported as such, so the use's gate can open).
 */
object PhoneJev {

    private const val TTL_MS = 7L * 24 * 60 * 60 * 1000
    private const val MAX_ASKS_PER_BATCH = 8
    private val memo = Advice.Suggestions(512)

    /** The folder id to file [pkg] under: [id] unless it is the sink and a suggestion stands. */
    fun refine(id: String, sinkId: String, pkg: String, now: Long = System.currentTimeMillis()): String =
        if (id != sinkId) id else memo.peek(pkg, now)?.pick?.takeIf { it.isNotEmpty() && it != sinkId } ?: id

    /** The pick, if it is one of the offered folders and not the sink; else null. */
    fun valid(pick: String?, folders: List<PhoneFolders.Folder>, sinkId: String): String? =
        pick?.takeIf { p -> p != sinkId && folders.any { it.id == p } }

    fun questions(folders: List<PhoneFolders.Folder>, sinkId: String): JSONObject {
        val crit = JSONObject()
        folders.filter { it.id != sinkId }.forEach { crit.put(it.id, it.label) }
        return JSONObject().put(
            "folder", JSONObject().put("type", "choice").put(
                "instructions",
                "Which folder of the person's phone app launcher fits the app with package `package` and label `label`? " +
                    "Each option's text is the folder's name.",
            ).put("criteria", crit),
        )
    }

    /** Start (never wait for) a suggestion for each unclaimed app that has none yet. */
    fun ask(ctx: Context, unclaimed: Map<String, String>, folders: List<PhoneFolders.Folder>, sinkId: String) {
        if (folders.count { it.id != sinkId } < 2) return
        val app = ctx.applicationContext
        var started = 0
        for ((pkg, label) in unclaimed) {
            if (started >= MAX_ASKS_PER_BATCH) break
            if (memo.peek(pkg) != null) continue
            started++
            memo.peekOrAsk(pkg, TTL_MS) {
                val o = DecisionsLink.outcome(app, "phone_folder",
                    JSONObject().put("package", pkg).put("label", label), questions(folders, sinkId))
                val ids = folders.map { it.id }.filter { it != sinkId }.toSet()
                val pick = valid(o.verdictOrNull?.pick("folder", ids), folders, sinkId)
                Advice.Suggestion(pick.orEmpty(), o.callOrNull).also { s ->
                    if (pick != null) Advice.remember(app, "phone_folder", s, JSONObject().put("folder", pick))
                }
            }
        }
        Advice.settleDue(app)
    }
}
