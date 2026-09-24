package com.diegonmarcos.superapp.configs

import android.app.Notification
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.launcher.Sections
import com.diegonmarcos.superapp.notificationcenter.BadgeCustomization
import com.diegonmarcos.superapp.notificationcenter.BadgeDeclaration
import com.diegonmarcos.superapp.notificationcenter.BadgeServices
import com.diegonmarcos.superapp.ui.LauncherPalette

/**
 * Configs ▸ Panel ▸ Push — #515 / #518.
 *
 * TWO SECTIONS, in this order, both DERIVED from
 * `build.json::ui.notification_center`:
 *
 *   1. the badge boxes themselves — one per badge, as it appears in the
 *      notification centre;
 *   2. a rule;
 *   3. the customization menus — one per badge, same order.
 *
 * What this page is FOR. The #497 version of it listed all eight producers,
 * which was a channel inventory and not a badge list, and it drew every one of
 * them from the declared `enabled` flag — a build-time constant. So on the day
 * three of Diego's badges were missing from his shade, this page said all eight
 * were "Declared on", which was true and useless. A settings page that cannot
 * tell you a badge is dead is a page that reads its own JSON back to you.
 *
 * Hence section 1 shows LIVE STATE, from [BadgeServices.status]: is the owning
 * service running, is a declared grant missing, has the owner switched it off.
 * A dead badge reads dead HERE, with the reason, which is the one thing the
 * previous pane could not do.
 *
 * Nothing on this page is named in Kotlin. `badge=true` in the declaration is
 * what puts a producer in both sections; the customization rows are whatever
 * that producer's `customization` array declares. Adding a badge is a
 * build.json edit and no edit here.
 */
class PushFragment : Fragment() {

    private lateinit var root: LinearLayout

    override fun onCreateView(inf: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext()
        val pad = dp(16)
        root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        rebuild()
        return ScrollView(ctx).apply { addView(root) }
    }

    /** Live state goes stale the moment a service dies, so the page is rebuilt
     *  on every return to it rather than only on create. */
    override fun onResume() {
        super.onResume()
        if (this::root.isInitialized) rebuild()
    }

    private fun rebuild() {
        val ctx = requireContext()
        root.removeAllViews()
        root.addView(title(ctx, getString(R.string.push_title)))
        root.addView(caption(ctx, getString(R.string.push_caption)))

        val badges = BadgeDeclaration.badges(BadgeServices.declared)
        if (badges.isEmpty()) {
            root.addView(caption(ctx, getString(R.string.push_none_badges)))
            return
        }

        // ── Section 1 — the badge boxes themselves ──────────────────────
        root.addView(sectionHead(ctx, getString(R.string.push_section_badges),
            getString(R.string.push_section_badges_sub), rule = false))
        for (b in badges) root.addView(badgeBox(ctx, b))

        // ── The division ────────────────────────────────────────────────
        // ── Section 2 — one customization menu per badge, same order ────
        root.addView(sectionHead(ctx, getString(R.string.push_section_custom),
            getString(R.string.push_section_custom_sub), rule = true))
        for (b in badges) root.addView(customMenu(ctx, b))
    }

    // ───────────────────────── Section 1: the badges ─────────────────────

    /**
     * One badge, drawn from the notification ACTUALLY POSTED for it
     * ([BadgeServices.live]) — its sub-text, title and text as the shade shows
     * them, and an Open button that fires that notification's own tap. #535:
     * this used to print the declaration's `shows` sentence, a description of
     * the badge, which read the same whether the badge was in the shade or had
     * been swiped away an hour ago. When nothing is posted, the state line
     * says why, and a dead owner gets a Start button.
     */
    private fun badgeBox(ctx: Context, b: BadgeDeclaration.Badge): View {
        val p = LauncherPalette.of(ctx)
        val live = BadgeServices.live(ctx, b)
        // A running owner with nothing in the shade is NOT live — that is
        // exactly the swiped-away badge the previous pane called green.
        val st = BadgeServices.status(ctx, b).let {
            if (it.state == BadgeServices.State.LIVE)
                it.copy(state = BadgeServices.State.DEAD, reason = getString(R.string.push_not_posted, b.channel))
            else it
        }
        val ex = live?.extras
        val shown = listOfNotNull(
            ex?.getCharSequence(Notification.EXTRA_SUB_TEXT),
            ex?.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex?.getCharSequence(Notification.EXTRA_TEXT),
        ).joinToString("\n").takeIf { it.isNotBlank() }
        val shownTitle = ex?.getCharSequence(Notification.EXTRA_TITLE)?.toString()

        val icon = ImageView(ctx).apply {
            setImageResource(Sections.iconResFor(ctx, b.icon))
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(ICON_DP), dp(ICON_DP)).apply { marginEnd = dp(12) }
            imageTintList = android.content.res.ColorStateList.valueOf(p.textPrimary)
        }

        val stateColour = when (st.state) {
            BadgeServices.State.LIVE -> p.accent
            // Everything that is not LIVE is the same colour on purpose:
            // "blocked" and "dead" are equally not-in-the-shade, and giving
            // one of them a softer colour is how a missing badge reads as fine.
            else -> 0xFFE05252.toInt()
        }
        val stateLabel = getString(
            when (st.state) {
                BadgeServices.State.LIVE -> R.string.push_state_live
                BadgeServices.State.DEAD -> R.string.push_state_dead
                BadgeServices.State.BLOCKED -> R.string.push_state_blocked
                BadgeServices.State.DISABLED -> R.string.push_state_disabled
                BadgeServices.State.NO_SERVICE -> R.string.push_state_no_service
            },
        )

        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(ctx).apply {
                text = shownTitle ?: b.label; textSize = 16f; setTextColor(p.textPrimary)
            })
            if (shown != null) addView(TextView(ctx).apply {
                text = shown; textSize = 12f; setTextColor(p.textSecondary)
            })
            addView(TextView(ctx).apply {
                text = "$stateLabel · ${st.reason}"
                textSize = 12f
                setTextColor(stateColour)
            })
        }

        val tap = live?.contentIntent
        val launch = when {
            tap != null -> Button(ctx).apply {
                text = getString(R.string.push_open)
                setOnClickListener { runCatching { tap.send() } }
            }
            st.state == BadgeServices.State.DEAD -> Button(ctx).apply {
                text = getString(R.string.push_start)
                setOnClickListener { applyLive(ctx) }
            }
            else -> null
        }

        // ONE announcement per box — the icon and the three lines are one fact
        // about one badge, not four things to tab through.
        for (child in listOf<View>(icon, texts)) {
            child.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(10))
            isFocusable = true
            contentDescription = "${shownTitle ?: b.label}. ${shown.orEmpty()} $stateLabel. ${st.reason}"
            addView(icon)
            addView(texts)
            launch?.let { addView(it) }
        }
    }

    // ──────────────────── Section 2: the customization ───────────────────

    private fun customMenu(ctx: Context, b: BadgeDeclaration.Badge): View {
        val p = LauncherPalette.of(ctx)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            addView(TextView(ctx).apply {
                text = b.label; textSize = 15f; setTextColor(p.textPrimary)
            })
        }
        if (b.customization.isEmpty()) {
            col.addView(TextView(ctx).apply {
                text = getString(R.string.push_producer_off)
                textSize = 12f; setTextColor(p.textSecondary)
            })
            return col
        }
        for (opt in b.customization) col.addView(control(ctx, b, opt))
        return col
    }

    /** The declared `type` picks the widget. This method knows about option
     *  TYPES, never about option keys — so a new option in build.json gets a
     *  control here without a Kotlin edit. */
    private fun control(ctx: Context, b: BadgeDeclaration.Badge, opt: BadgeDeclaration.Option): View {
        val p = LauncherPalette.of(ctx)
        return when (opt.type) {
            "choice" -> {
                val values = opt.options
                val row = TextView(ctx).apply {
                    textSize = 13f
                    setTextColor(p.textPrimary)
                    setPadding(0, dp(8), 0, dp(8))
                }
                fun render() { row.text = "${opt.label}: ${BadgeCustomization.text(ctx, b, opt.key)}" }
                render()
                // Tap cycles. A declared choice is two or three values; a
                // dialog to pick between three is more chrome than choice.
                row.setOnClickListener {
                    if (values.isEmpty()) return@setOnClickListener
                    val cur = BadgeCustomization.text(ctx, b, opt.key)
                    val next = values[(values.indexOf(cur).takeIf { it >= 0 }?.plus(1) ?: 0) % values.size]
                    BadgeCustomization.set(ctx, b, opt.key, next)
                    render()
                    applyLive(ctx)
                }
                row
            }
            else -> {
                val sw = Switch(ctx).apply {
                    text = opt.label
                    textSize = 13f
                    setTextColor(p.textPrimary)
                    setPadding(0, dp(8), 0, dp(8))
                    isChecked = BadgeCustomization.bool(ctx, b, opt)
                    setOnCheckedChangeListener { _, on ->
                        BadgeCustomization.set(ctx, b, opt.key, on)
                        applyLive(ctx)
                    }
                }
                sw
            }
        }
    }

    /**
     * Take effect on the LIVE badge, which is the half of "declaration AND
     * customization" a settings page usually forgets: re-ensure the services
     * (a badge just switched back on needs its owner started) and redraw
     * section 1 so its state line agrees with the switch that was just moved.
     */
    private fun applyLive(ctx: Context) {
        runCatching { BadgeServices.ensureAll(ctx) }
        rebuild()
    }

    // ────────────────────────────── Chrome ───────────────────────────────

    /** Section rule + heading, matching the convention OneHandFragment uses
     *  for the top-level parts of a Configs page. The rule is what #518 asked
     *  for as "a division" between the boxes and the menus. */
    private fun sectionHead(ctx: Context, label: String, sub: String, rule: Boolean) =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            if (rule) addView(View(ctx).apply {
                setBackgroundColor(0x33FFFFFF)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1),
                ).apply { topMargin = dp(20); bottomMargin = dp(12) }
            })
            addView(TextView(ctx).apply {
                text = label.uppercase()
                textSize = 12f
                setTextColor(LauncherPalette.of(ctx).accent)
                setPadding(0, if (rule) 0 else dp(16), 0, 0)
            })
            addView(TextView(ctx).apply {
                text = sub; textSize = 11f
                setTextColor(LauncherPalette.of(ctx).textSecondary)
                setPadding(0, 0, 0, dp(4))
            })
        }

    private fun title(ctx: Context, s: String) = TextView(ctx).apply {
        text = s
        textSize = 22f
        setTextColor(LauncherPalette.of(ctx).textPrimary)
        setPadding(0, 0, 0, dp(4))
    }

    private fun caption(ctx: Context, s: String) = TextView(ctx).apply {
        text = s
        textSize = 12f
        setTextColor(LauncherPalette.of(ctx).textSecondary)
        setPadding(0, 0, 0, dp(8))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val ICON_DP = 28

        fun newInstance(): PushFragment = PushFragment()
    }
}
