package app.sterna.ui.inbox

import android.content.Context
import com.diegonmarcos.superapp.decisions.link.Advice
import com.diegonmarcos.superapp.decisions.link.DecisionsLink
import org.json.JSONObject

/**
 * #883 (Jev wave 2, spec section 3 item 4): which folder a message most likely belongs in, for the move picker.
 *
 * SUGGEST-ONLY: the picker lists exactly the folders it always did; the suggested one is only listed FIRST. Nothing
 * is moved, filed or marked spam by this, and there is no automatic anything. It sees the sender address and the
 * subject (headers; never the body, the preview or an attachment) and, because mail is sensitive content, it only
 * runs when the person has granted the use in the SuperApp (the engine refuses it otherwise). Mail holds no key and
 * binds no credential: it names a use and the engine answers or does not. Every doubt (no engine, no consent, a
 * closed evidence gate, unsure, a folder the picker does not offer) leaves the picker in its own order.
 * The folder the person finally picks is the outcome.
 */
internal object MailJev {

    private const val WAIT_MS = 1500L

    /** [rows] with the row whose id is [suggestedId] first; every other row keeps its order; no match = unchanged. */
    fun <T> promote(rows: List<T>, suggestedId: String?, idOf: (T) -> String): List<T> {
        if (suggestedId == null) return rows
        val i = rows.indexOfFirst { idOf(it) == suggestedId }
        if (i <= 0) return rows
        return listOf(rows[i]) + rows.filterIndexed { j, _ -> j != i }
    }

    /** The folder id to promote: an offered one only. */
    fun valid(pick: String?, offered: Collection<String>): String? = pick?.takeIf { it.isNotEmpty() && it in offered }

    /** Headers only. */
    fun state(from: String, subject: String): JSONObject = JSONObject().put("from", from.take(200)).put("subject", subject.take(200))

    fun questions(offered: List<Pair<String, String>>): JSONObject {
        val crit = JSONObject()
        offered.forEach { (id, name) -> crit.put(id, name) }
        return JSONObject().put(
            "folder", JSONObject().put("type", "choice").put(
                "instructions",
                "Which folder should a message from `from` with the subject `subject` be moved to? Each option's text is the folder's name.",
            ).put("criteria", crit),
        )
    }

    /** [offered] = (folder id, display name) as the picker lists them. Blocking up to [WAIT_MS]; null on the main thread. */
    fun suggest(ctx: Context, from: String, subject: String, offered: List<Pair<String, String>>): Advice.Suggestion? {
        if (offered.size < 2) return null
        val app = ctx.applicationContext
        val ids = offered.map { it.first }
        val got = Advice.within(WAIT_MS) {
            val o = DecisionsLink.outcome(app, "mail_move_target", state(from, subject), questions(offered))
            Advice.Suggestion(valid(o.verdictOrNull?.pick("folder", ids.toSet()), ids).orEmpty(), o.callOrNull)
        } ?: return null
        if (got.pick.isEmpty()) return null
        Advice.remember(app, "mail_move_target", got, JSONObject().put("folder", got.pick))
        Advice.settleDue(app)
        return got
    }
}
