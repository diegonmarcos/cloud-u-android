package com.diegonmarcos.superapp.profile

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.ui.StatusLight

/**
 * Configs ▸ Profile ▸ Fleet — the COCKPIT CHROME (#570, reopened).
 *
 * The first delivery of #570 wired the vault bundle into a fleet configurator
 * and left the page looking exactly as before: a headline per section and
 * monospace lines under it. This object is the re-skin. It draws two things
 * and nothing else:
 *
 *  • [hero] — one card for the device this phone IS: a round orb with the
 *    device's icon (the homescreen's circle-icon language, the same OVAL
 *    badge Configs ▸ Panel ▸ Control draws), its name, its mesh identity, the
 *    overall [StatusLight] and a slot for the device chooser or the connect
 *    call-to-action;
 *  • [card] — one bold card per cockpit section: round badge, label, the
 *    section's [StatusLight] and a summary line, then the body the fragment
 *    fills with the comparison rows and that section's Apply.
 *
 * ── What it deliberately does NOT do ──────────────────────────────────────
 * No colour is written here: every fill and every ink comes from
 * [LauncherPalette] (so the three themes restyle it) and every light from the
 * shared [StatusLight] (so "configured" is the same green as everywhere else
 * in this app — a private copy would be one of them being wrong). No
 * animation, no ticker, no handler: the page is drawn once and repainted only
 * when a reading lands, which is what keeps it Power-Saving-safe. And no
 * reading: what a light shows is decided by the fragment from the rows the
 * model computed; this file only knows how to draw a state it is handed.
 *
 * The ids in res/values/ids.xml exist so [FleetCockpitViewTest] can find the
 * hero, each card and each card's light in the tree without knowing how the
 * views are built — a layout test that read this file's helpers would only
 * prove the file agrees with itself.
 */
object FleetCockpitView {

    class Hero(
        val root: LinearLayout,
        val title: TextView,
        val subtitle: TextView,
        val light: TextView,
        val summary: TextView,
        /** Where the fragment puts the device chooser, or the connect button. */
        val slot: LinearLayout,
    )

    class Card(
        val root: LinearLayout,
        /** The cockpit section id; also the root's tag. */
        val tag: String,
        val label: String,
        val light: TextView,
        val summary: TextView,
        /** The fragment renders the section's rows and Apply into this. */
        val body: LinearLayout,
    )

    fun hero(ctx: Context, title: String, subtitle: String, iconRes: Int): Hero {
        val p = LauncherPalette.of(ctx)
        val titleView = TextView(ctx).apply {
            text = title
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(p.textPrimary)
        }
        val subtitleView = TextView(ctx).apply {
            text = subtitle
            textSize = 12f
            setTextColor(p.textSecondary)
        }
        val light = lightView(ctx).apply { id = R.id.cockpit_hero_light }
        val summary = TextView(ctx).apply {
            textSize = 12f
            setTextColor(p.textSecondary)
        }
        val slot = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val orb = badge(ctx, iconRes, HERO_ORB_DP, p.surface, p.textPrimary).apply { id = R.id.cockpit_device_orb }
        val text = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(ctx, 14) }
            addView(titleView); addView(subtitleView); addView(light); addView(summary)
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(orb); addView(text)
        }
        val root = LinearLayout(ctx).apply {
            id = R.id.cockpit_hero
            orientation = LinearLayout.VERTICAL
            background = surface(ctx, p.surfaceSelected, p.hairline)
            val pad = dp(ctx, 16); setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(ctx, 6) }
            addView(row); addView(slot)
        }
        return Hero(root, titleView, subtitleView, light, summary, slot)
    }

    /**
     * One section card. The header (badge, label, light, summary) is a tap
     * target that shows or hides [Card.body]; the body starts VISIBLE, because
     * the Apply inside it is the reason the card exists and a page of six
     * collapsed rows would hide every one of them. [toggleAction] names the
     * tap for TalkBack, which otherwise offers an anonymous click.
     */
    fun card(ctx: Context, label: String, tag: String, iconRes: Int, toggleAction: String): Card {
        val p = LauncherPalette.of(ctx)
        val badgeView = badge(ctx, iconRes, CARD_BADGE_DP, p.surfaceSelected, p.textPrimary).apply { id = R.id.cockpit_card_badge }
        val labelView = TextView(ctx).apply {
            text = label
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(p.textPrimary)
        }
        val light = lightView(ctx).apply { id = R.id.cockpit_card_light }
        val summary = TextView(ctx).apply {
            textSize = 11f
            setTextColor(p.textSecondary)
        }
        val text = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(ctx, 12) }
            addView(labelView); addView(light); addView(summary)
        }
        val body = LinearLayout(ctx).apply {
            id = R.id.cockpit_card_body
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(ctx, 8), 0, 0)
        }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            addView(badgeView); addView(text)
            setOnClickListener {
                body.visibility = if (body.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
            ViewCompat.replaceAccessibilityAction(
                this,
                AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
                toggleAction,
                null,
            )
        }
        // One announcement for the header: the light's description already
        // carries the label and the state, so the badge and label are decoration.
        badgeView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        labelView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val root = LinearLayout(ctx).apply {
            id = R.id.cockpit_card
            this.tag = tag
            orientation = LinearLayout.VERTICAL
            background = surface(ctx, p.surface, p.hairline)
            val pad = dp(ctx, 14); setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(ctx, 10) }
            addView(header); addView(body)
        }
        return Card(root, tag, label, light, summary, body)
    }

    /** Paint one light — glyph, word, colour and the spoken sentence — from the shared component. */
    fun paint(light: TextView, state: StatusLight.State, rowLabel: String) {
        val ctx = light.context
        light.text = StatusLight.text(ctx, state)
        light.setTextColor(StatusLight.colour(ctx, state))
        light.contentDescription = StatusLight.description(ctx, rowLabel, state)
    }

    /** A pill button in the palette's accent, for the actions inside a card. */
    fun pill(ctx: Context, label: String, onClick: () -> Unit): TextView {
        val p = LauncherPalette.of(ctx)
        return TextView(ctx).apply {
            text = label
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(p.tileInk)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(ctx, PILL_RADIUS_DP).toFloat()
                setColor(p.accent)
            }
            gravity = Gravity.CENTER
            setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 16), dp(ctx, 10))
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(ctx, 6) }
        }
    }

    private fun lightView(ctx: Context) = TextView(ctx).apply {
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(ctx, 2), 0, 0)
    }

    /** The round badge — a filled OVAL with a tinted icon centred in it. */
    private fun badge(ctx: Context, iconRes: Int, sizeDp: Int, fill: Int, ink: Int): LinearLayout {
        val icon = ImageView(ctx).apply {
            setImageResource(iconRes)
            scaleType = ImageView.ScaleType.FIT_CENTER
            imageTintList = android.content.res.ColorStateList.valueOf(ink)
            val side = dp(ctx, sizeDp / 2)
            layoutParams = LinearLayout.LayoutParams(side, side)
        }
        return LinearLayout(ctx).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(fill)
            }
            layoutParams = LinearLayout.LayoutParams(dp(ctx, sizeDp), dp(ctx, sizeDp))
            addView(icon)
        }
    }

    private fun surface(ctx: Context, fill: Int, hairline: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(ctx, CARD_RADIUS_DP).toFloat()
        setColor(fill)
        setStroke(dp(ctx, 1), hairline)
    }

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    private const val HERO_ORB_DP = 64
    private const val CARD_BADGE_DP = 44
    private const val CARD_RADIUS_DP = 18
    private const val PILL_RADIUS_DP = 999
}
