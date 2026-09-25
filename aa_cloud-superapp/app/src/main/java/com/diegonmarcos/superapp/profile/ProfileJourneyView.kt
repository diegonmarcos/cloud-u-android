package com.diegonmarcos.superapp.profile

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.ui.StatusLight

/**
 * The journey's CHROME (#573) — the cockpit's own, arranged as four steps.
 *
 * It draws a hero ([FleetCockpitView.hero]) with a one-line rail of the four
 * step glyphs, then one [FleetCockpitView.card] per [ProfileJourney.Step], in
 * order, each tagged [ProfileJourney.tag]. [paint] is the only thing that
 * touches a light or opens a body, and it does so from a [ProfileJourney.State]
 * through [ProfileJourney] alone — so the screen cannot disagree with the state
 * machine the JVM test exercises. No colour, no radius and no font is declared
 * here; a theme change restyles Connect and Fleet together.
 */
object ProfileJourneyView {

    class Journey(
        val root: LinearLayout,
        val hero: FleetCockpitView.Hero,
        /** "① Sign in ● · ② Who ? · …" — the whole state in one line under the hero. */
        val rail: TextView,
        val cards: Map<ProfileJourney.Step, FleetCockpitView.Card>,
    )

    /**
     * @param labels the card label per step ("1 · Sign in", …), from strings.
     * @param icons the badge drawable per step.
     * @param toggleAction the spoken name of the header tap, from strings.
     */
    fun build(
        ctx: Context,
        heroTitle: String,
        heroSubtitle: String,
        heroIcon: Int,
        labels: Map<ProfileJourney.Step, String>,
        icons: Map<ProfileJourney.Step, Int>,
        toggleAction: String,
    ): Journey {
        val p = LauncherPalette.of(ctx)
        val hero = FleetCockpitView.hero(ctx, heroTitle, heroSubtitle, heroIcon)
        val rail = TextView(ctx).apply {
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(p.textSecondary)
            setPadding(0, dp(ctx, 8), 0, 0)
        }
        hero.slot.addView(rail)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            addView(hero.root)
        }
        val cards = linkedMapOf<ProfileJourney.Step, FleetCockpitView.Card>()
        for (step in ProfileJourney.Step.values()) {
            val card = FleetCockpitView.card(
                ctx, labels[step].orEmpty(), ProfileJourney.tag(step), icons[step] ?: 0, toggleAction,
            )
            cards[step] = card
            root.addView(card.root)
        }
        return Journey(root, hero, rail, cards)
    }

    /**
     * Lights, summaries and open bodies, all from [state] through [ProfileJourney].
     *
     * @param summaries one line per step (the fragment words them).
     * @param heroSummary "Step N of 4 · …", worded by the fragment.
     */
    fun paint(
        j: Journey,
        state: ProfileJourney.State,
        summaries: Map<ProfileJourney.Step, String>,
        heroSummary: String,
    ) {
        FleetCockpitView.paint(j.hero.light, ProfileJourney.overall(state), j.hero.title.text.toString())
        j.hero.summary.text = heroSummary
        val rail = StringBuilder()
        for ((step, card) in j.cards) {
            val phase = ProfileJourney.phase(state, step)
            val light = ProfileJourney.light(phase)
            FleetCockpitView.paint(card.light, light, card.label)
            card.summary.text = summaries[step].orEmpty()
            card.body.visibility = if (ProfileJourney.bodyOpen(phase)) View.VISIBLE else View.GONE
            if (rail.isNotEmpty()) rail.append("  ·  ")
            rail.append(card.label).append(' ').append(StatusLight.glyph(light))
        }
        j.rail.text = rail.toString()
    }

    /**
     * One selectable row for steps 2 and 3: a filled or hollow glyph and the
     * text. The glyph carries the choice for anyone who cannot see the ink —
     * the same shape-first rule the shared light follows.
     */
    fun choice(ctx: Context, text: String, selected: Boolean, onClick: () -> Unit): TextView {
        val p = LauncherPalette.of(ctx)
        return TextView(ctx).apply {
            this.text = (if (selected) "● " else "○ ") + text
            textSize = 14f
            if (selected) setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (selected) p.textPrimary else p.textSecondary)
            setPadding(dp(ctx, 4), dp(ctx, 10), dp(ctx, 4), dp(ctx, 10))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            contentDescription = text
        }
    }

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
}
