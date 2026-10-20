package helium314.keyboard.latin

import android.content.Context
import com.diegonmarcos.superapp.decisions.link.Advice
import com.diegonmarcos.superapp.decisions.link.DecisionsLink
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import org.json.JSONObject

/**
 * #883 (Jev wave 2, spec section 3 item 8): which ENHANCE style fits this text, when the owner never picked one.
 *
 * Only when `enhance_style` was never saved (Settings → Text Enhancements untouched): a style the owner chose is
 * never replaced. The decision model sees the SHAPE of the text (lengths, lines, links) and NEVER the text: what
 * somebody types is not ours to send. It runs on the enhance worker thread (never the typing path), waits at most
 * [WAIT_MS], and every doubt (no engine, no consent, a closed gate, unsure, an id the registry lacks) keeps the
 * default style exactly as before. Accepted by default after a week, like every suggestion.
 */
internal object KeyboardJev {

    private const val WAIT_MS = 1200L

    /** The style to use instead of the default, or null. [pick] is the model's id; [userChose] true = never override. */
    internal fun applies(userChose: Boolean, pick: String?, styles: List<AiRouter.Style>): AiRouter.Style? =
        if (userChose || pick.isNullOrEmpty()) null else styles.firstOrNull { it.id == pick }

    /** Structure only, no content. */
    internal fun shape(text: String): JSONObject = JSONObject()
        .put("chars", text.length)
        .put("words", text.split(Regex("\\s+")).count { it.isNotEmpty() })
        .put("lines", text.count { it == '\n' } + 1)
        .put("has_link", text.contains("http://") || text.contains("https://"))

    internal fun questions(styles: List<AiRouter.Style>): JSONObject {
        val crit = JSONObject()
        styles.forEach { crit.put(it.id, it.label) }
        return JSONObject().put(
            "style", JSONObject().put("type", "choice").put(
                "instructions",
                "Which text-enhancement style suits a text with the shape in `chars`, `words`, `lines` and `has_link`? " +
                    "Each option's text is the style's name.",
            ).put("criteria", crit),
        )
    }

    /** Blocking up to [WAIT_MS]; call off the main thread (the enhance worker). */
    fun base(context: Context, text: String): AiRouter.Style? {
        val styles = AiRouter.styles
        if (styles.size < 2) return null
        val chose = context.prefs().contains(Settings.PREF_ENHANCE_STYLE)
        if (chose) return null
        val app = context.applicationContext
        val got = Advice.within(WAIT_MS) {
            val o = DecisionsLink.outcome(app, "keyboard_style", shape(text), questions(styles))
            Advice.Suggestion(o.verdictOrNull?.pick("style", styles.map { it.id }.toSet()).orEmpty(), o.callOrNull)
        } ?: return null
        val style = applies(chose, got.pick, styles) ?: return null
        Advice.remember(app, "keyboard_style", got, JSONObject().put("style", style.id))
        Advice.settleDue(app)
        return style
    }
}
