package com.diegonmarcos.superapp.configs

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.Toast
import android.widget.TextView
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.notificationcenter.BadgeCustomization
import com.diegonmarcos.superapp.notificationcenter.BadgeDeclaration
import com.diegonmarcos.superapp.notificationcenter.BadgeServices
import com.diegonmarcos.superapp.ui.LauncherPalette

/**
 * The persistent-badge views of Configs ▸ Launcher ▸ Notify — #515 / #518,
 * merged into the ntfy page by #580 (it was the Push tab, a page of its own).
 *
 * TWO VIEWS, both DERIVED from `build.json::ui.notification_center`, drawn by
 * AggregatorStackFragment for the panel kinds that name them:
 *
 *   1. [badges] — the badge boxes themselves, one per badge, as it appears in
 *      the notification centre, at the TOP of the page;
 *   2. [configs] — the customization menus, one per badge, same order, under
 *      the page's Filters.
 *
 * Neither is a producer. The one notification centre is the platform's shade
 * and the ntfy cards below; these are windows onto the badges that live in it,
 * so nothing here posts, cancels or keeps a store (#497 / #515).
 *
 * What these views are FOR. The #497 version listed all eight producers,
 * which was a channel inventory and not a badge list, and it drew every one of
 * them from the declared `enabled` flag — a build-time constant. So on the day
 * three of Diego's badges were missing from his shade, this page said all eight
 * were "Declared on", which was true and useless.
 *
 * Hence [badges] shows LIVE STATE, from [BadgeServices.status]: is the owning
 * service running, is a declared grant missing, has the owner switched it off.
 * A dead badge reads dead HERE, with the reason, which is the one thing the
 * previous pane could not do.
 *
 * Nothing here is named in Kotlin. `badge=true` in the declaration is what
 * puts a producer in both views; the customization rows are whatever that
 * producer's `customization` array declares. Adding a badge is a build.json
 * edit and no edit here.
 *
 * Live state goes stale the moment a service dies, so the host redraws on
 * every return to the page; [redraw] is how a view asks the host to repaint
 * BOTH of them, because a switch moved in [configs] changes a state line in
 * [badges].
 */
object BadgePanes {

    private const val REBUILD_DELAY_MS = 1200L

    /** View 1 — Launch-all, then one box per declared badge. */
    fun badges(ctx: Context, redraw: () -> Unit): View {
        val root = column(ctx)
        val badges = BadgeDeclaration.badges(BadgeServices.declared)
        if (badges.isEmpty()) {
            root.addView(caption(ctx, ctx.getString(R.string.push_none_badges)))
            return root
        }
        root.addView(caption(ctx, ctx.getString(R.string.push_section_badges_sub)))
        root.addView(Button(ctx).apply {
            text = ctx.getString(R.string.push_launch_all)
            setOnClickListener { launched(ctx, root, redraw, BadgeServices.launchAll(ctx)) }
        })
        for (b in badges) root.addView(badgeBox(ctx, root, redraw, b))
        return root
    }

    /** View 2 — one customization menu per declared badge, same order. */
    fun configs(ctx: Context, redraw: () -> Unit): View {
        val root = column(ctx)
        val badges = BadgeDeclaration.badges(BadgeServices.declared)
        if (badges.isEmpty()) {
            root.addView(caption(ctx, ctx.getString(R.string.push_none_badges)))
            return root
        }
        root.addView(caption(ctx, ctx.getString(R.string.push_section_custom_sub)))
        for (b in badges) root.addView(customMenu(ctx, redraw, b))
        return root
    }

    private fun column(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
    }

    // ───────────────────────── View 1: the badges ────────────────────────

    /**
     * One badge, rendered from the notification ACTUALLY POSTED for it
     * ([BadgeServices.live]) by [BadgeServices.view] — the platform's own
     * template for that very Notification, the one the shade inflates. There is
     * no renderer in this file: a badge added or restyled in its producer shows
     * up here the same way it shows in the shade. #535: this used to print the
     * declaration's `shows` sentence, then hand-drawn title/text lines.
     *
     * Under it: the state line (why a badge is NOT in the shade), Open (the
     * badge's own tap) and Launch (put it back — a swiped or cleared badge
     * has nothing to render, so a dead badge is just its name and this button).
     */
    private fun badgeBox(ctx: Context, host: View, redraw: () -> Unit, b: BadgeDeclaration.Badge): View {
        val p = LauncherPalette.of(ctx)
        val live = BadgeServices.live(ctx, b)
        // A running owner with nothing in the shade is NOT live — that is
        // exactly the swiped-away badge the previous pane called green.
        val st = BadgeServices.status(ctx, b).let {
            if (it.state == BadgeServices.State.LIVE && live == null)
                it.copy(state = BadgeServices.State.DEAD, reason = ctx.getString(R.string.push_not_posted, b.channel))
            else it
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(ctx, 10), 0, dp(ctx, 10))
        }
        val real = live?.let { BadgeServices.view(ctx, it, col) }
        if (real != null) col.addView(real) else col.addView(TextView(ctx).apply {
            text = b.label; textSize = 16f; setTextColor(p.textPrimary)
        })

        val stateColour = when (st.state) {
            BadgeServices.State.LIVE -> p.accent
            // Everything that is not LIVE is the same colour on purpose:
            // "blocked" and "dead" are equally not-in-the-shade, and giving
            // one of them a softer colour is how a missing badge reads as fine.
            else -> 0xFFE05252.toInt()
        }
        val stateLabel = ctx.getString(
            when (st.state) {
                BadgeServices.State.LIVE -> R.string.push_state_live
                BadgeServices.State.DEAD -> R.string.push_state_dead
                BadgeServices.State.BLOCKED -> R.string.push_state_blocked
                BadgeServices.State.DISABLED -> R.string.push_state_disabled
                BadgeServices.State.NO_SERVICE -> R.string.push_state_no_service
            },
        )
        col.addView(TextView(ctx).apply {
            text = "$stateLabel · ${st.reason}"
            textSize = 12f
            setTextColor(stateColour)
        })

        val tap = live?.contentIntent
        col.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            if (tap != null) addView(Button(ctx).apply {
                text = ctx.getString(R.string.push_open)
                setOnClickListener { runCatching { tap.send() } }
            })
            addView(Button(ctx).apply {
                text = ctx.getString(R.string.push_launch)
                setOnClickListener { launched(ctx, host, redraw, mapOf(b to BadgeServices.launch(ctx, b))) }
            })
        })
        return col
    }

    /** Launch result → one toast (a refusal is never silent), then redraw once
     *  the owner has had a moment to post. */
    private fun launched(ctx: Context, host: View, redraw: () -> Unit, results: Map<BadgeDeclaration.Badge, String?>) {
        val refused = results.filterValues { it != null }
        Toast.makeText(
            ctx,
            if (refused.isEmpty()) ctx.getString(R.string.push_launched, results.size)
            else refused.entries.joinToString("\n") { "${it.key.label}: ${it.value}" },
            Toast.LENGTH_LONG,
        ).show()
        host.postDelayed({ if (host.isAttachedToWindow) redraw() }, REBUILD_DELAY_MS)
    }

    // ──────────────────── View 2: the customization ──────────────────────

    private fun customMenu(ctx: Context, redraw: () -> Unit, b: BadgeDeclaration.Badge): View {
        val p = LauncherPalette.of(ctx)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(ctx, 10), 0, dp(ctx, 10))
            addView(TextView(ctx).apply {
                text = b.label; textSize = 15f; setTextColor(p.textPrimary)
            })
        }
        if (b.customization.isEmpty()) {
            col.addView(TextView(ctx).apply {
                text = ctx.getString(R.string.push_producer_off)
                textSize = 12f; setTextColor(p.textSecondary)
            })
            return col
        }
        for (opt in b.customization) col.addView(control(ctx, redraw, b, opt))
        return col
    }

    /** The declared `type` picks the widget. This method knows about option
     *  TYPES, never about option keys — so a new option in build.json gets a
     *  control here without a Kotlin edit. */
    private fun control(ctx: Context, redraw: () -> Unit, b: BadgeDeclaration.Badge, opt: BadgeDeclaration.Option): View {
        val p = LauncherPalette.of(ctx)
        return when (opt.type) {
            "choice" -> {
                val values = opt.options
                val row = TextView(ctx).apply {
                    textSize = 13f
                    setTextColor(p.textPrimary)
                    setPadding(0, dp(ctx, 8), 0, dp(ctx, 8))
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
                    applyLive(ctx, redraw)
                }
                row
            }
            else -> {
                val sw = Switch(ctx).apply {
                    text = opt.label
                    textSize = 13f
                    setTextColor(p.textPrimary)
                    setPadding(0, dp(ctx, 8), 0, dp(ctx, 8))
                    isChecked = BadgeCustomization.bool(ctx, b, opt)
                    setOnCheckedChangeListener { _, on ->
                        BadgeCustomization.set(ctx, b, opt.key, on)
                        applyLive(ctx, redraw)
                    }
                }
                sw
            }
        }
    }

    /**
     * Take effect on the LIVE badge, which is the half of "declaration AND
     * customization" a settings page usually forgets: re-ensure the services
     * (a badge just switched back on needs its owner started) and redraw the
     * boxes so their state line agrees with the switch that was just moved.
     */
    private fun applyLive(ctx: Context, redraw: () -> Unit) {
        runCatching { BadgeServices.ensureAll(ctx) }
        redraw()
    }

    // ────────────────────────────── Chrome ───────────────────────────────

    private fun caption(ctx: Context, s: String) = TextView(ctx).apply {
        text = s
        textSize = 12f
        setTextColor(LauncherPalette.of(ctx).textSecondary)
        setPadding(0, 0, 0, dp(ctx, 8))
    }

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
}
