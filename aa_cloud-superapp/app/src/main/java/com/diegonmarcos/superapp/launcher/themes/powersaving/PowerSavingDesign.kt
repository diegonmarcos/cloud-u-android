package com.diegonmarcos.superapp.launcher.themes.powersaving

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The COMPLETE design vocabulary of the Cloud Power Saving mode.
 *
 * ── Why this file exists ─────────────────────────────────────────────────
 * The owner's complaint, three times over: "this theme must have all its own
 * designs, all different, nothing can be equal". Power Saving kept looking
 * like the default launcher because it kept BORROWING — the default tile
 * builder, the default card, the default chrome, the default Material3 style
 * that every XML layout resolves `?attr/` through. A mode that borrows its
 * components cannot look different from the mode it borrows them from, no
 * matter what colours it passes in.
 *
 * So this file is the whole vocabulary, owned outright: colour, type, metric,
 * shape, and every component built from them. No import from `ui/`, none from
 * `launcher/` — if Power Saving needs a row, a card, a header or a button, it
 * is defined HERE and nowhere else. That is the rule that makes "nothing can
 * be equal" structurally true rather than a thing to remember.
 *
 * ── Why the design looks the way it does ─────────────────────────────────
 * Every choice below is a power choice first and an aesthetic second, because
 * on the OLED panel this mode exists for, the design IS the power draw:
 *
 *   · PURE BLACK ground. #000000 switches an OLED pixel off — it costs zero.
 *     Any lift at all (the default mode's #121212-ish surfaces) lights every
 *     pixel of every panel on screen. So surfaces here are not "dark grey",
 *     they are black, and separation is carried by hairlines instead.
 *
 *   · OUTLINES, NOT FILLS. A 1px stroke lights the perimeter; a filled card
 *     lights its whole area. Every container in this mode is an outline.
 *
 *   · MONOCHROME. A coloured accent drives two or three OLED subpixels at
 *     different duty cycles; a grey drives them evenly and lower. There is no
 *     hue anywhere in this palette, deliberately — the mode reads as "the
 *     phone is conserving" precisely because the colour is gone.
 *
 *   · DIM INK. Text is #C8C8C8, not #FFFFFF. Pure white is the single most
 *     expensive colour an OLED can draw, and at these sizes it buys nothing
 *     legibility already has: #C8C8C8 on #000000 is 11.6:1, far past the
 *     4.5:1 floor, at roughly three-quarters the luminance.
 *
 *   · SPARSE. Large type, few elements, wide voids. Fewer lit pixels per
 *     screen, and a one-glance layout means a shorter screen-on time — which
 *     saves more than every other item on this list put together.
 */
internal object PowerSavingDesign {

    // ── Colour ───────────────────────────────────────────────────────────
    // Held as constants rather than colour resources on purpose: this mode's
    // palette must not be reachable from any other mode's code path, and a
    // res/values token is reachable by definition.

    /** The ground. An OLED pixel at #000000 is an OFF pixel. */
    const val INK_BLACK = 0xFF000000.toInt()

    /** Primary text. 11.6:1 on black at ~78% of white's luminance. */
    const val INK_PRIMARY = 0xFFC8C8C8.toInt()

    /** Secondary text — labels, units, captions. 6.4:1 on black. */
    const val INK_SECONDARY = 0xFF8C8C8C.toInt()

    /** Tertiary — the quietest legible step. 3.4:1; large text only. */
    const val INK_TERTIARY = 0xFF5E5E5E.toInt()

    /** Every container's edge. Visible, barely lit. */
    const val HAIRLINE = 0xFF2E2E2E.toInt()

    /** A lit / selected state. The one step brighter than INK_PRIMARY. */
    const val INK_LIT = 0xFFEDEDED.toInt()

    /** What sits ON a lit surface, when a fill is genuinely unavoidable. */
    const val INK_ON_LIT = INK_BLACK

    // ── Metric ───────────────────────────────────────────────────────────
    // One scale, powers of the same 4dp step. No ad-hoc numbers below.

    const val STEP = 4
    const val GAP_TIGHT = STEP * 2       // 8
    const val GAP = STEP * 4             // 16
    const val GAP_WIDE = STEP * 6        // 24
    const val GAP_SECTION = STEP * 10    // 40

    /** Containers are square. A corner radius is extra lit pixels for nothing. */
    const val RADIUS = 0f

    const val STROKE = 1

    // ── Type ─────────────────────────────────────────────────────────────
    // Four sizes, two weights. The default mode has 76 Typeface calls and 24
    // ad-hoc text sizes; this mode has these and nothing else.

    const val TYPE_DISPLAY = 64f   // the clock, and only the clock
    const val TYPE_TITLE = 22f
    const val TYPE_BODY = 15f
    const val TYPE_LABEL = 11f

    /** Condensed sans throughout — narrower glyphs light fewer pixels. */
    val FACE_REGULAR: Typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    val FACE_LIGHT: Typeface = Typeface.create("sans-serif-condensed-light", Typeface.NORMAL)

    // ── Primitives ───────────────────────────────────────────────────────

    fun dp(ctx: Context, value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), ctx.resources.displayMetrics,
    ).toInt()

    /** The mode's one container: an outline, never a fill. */
    fun outline(ctx: Context, stroke: Int = HAIRLINE): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = RADIUS
        setColor(Color.TRANSPARENT)
        setStroke(dp(ctx, STROKE), stroke)
    }

    /** The rare inverted container — a lit tile. Used for ON states only. */
    fun filled(ctx: Context, fill: Int = INK_LIT): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = RADIUS
        setColor(fill)
    }

    /** A one-pixel separator. Cheaper than a bordered container when all that
     *  is wanted is "these two things are not the same thing". */
    fun rule(ctx: Context): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, STROKE),
        )
        setBackgroundColor(HAIRLINE)
    }

    fun space(ctx: Context, height: Int): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, height),
        )
    }

    // ── Text ─────────────────────────────────────────────────────────────

    fun display(ctx: Context, value: String): TextView = TextView(ctx).apply {
        text = value
        setTextColor(INK_PRIMARY)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, TYPE_DISPLAY)
        typeface = FACE_LIGHT
        includeFontPadding = false
        letterSpacing = -0.02f
    }

    fun title(ctx: Context, value: String): TextView = TextView(ctx).apply {
        text = value
        setTextColor(INK_PRIMARY)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, TYPE_TITLE)
        typeface = FACE_LIGHT
        includeFontPadding = false
    }

    fun body(ctx: Context, value: String, colour: Int = INK_PRIMARY): TextView = TextView(ctx).apply {
        text = value
        setTextColor(colour)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, TYPE_BODY)
        typeface = FACE_REGULAR
    }

    /** Sparse tracked capitals — this mode's only decorative device, and it
     *  costs nothing because it is the smallest text on screen. */
    fun label(ctx: Context, value: String, colour: Int = INK_SECONDARY): TextView = TextView(ctx).apply {
        text = value.uppercase()
        setTextColor(colour)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, TYPE_LABEL)
        typeface = FACE_REGULAR
        letterSpacing = 0.18f
    }

    // ── Components ───────────────────────────────────────────────────────

    /** A section heading: tracked label over a rule. Used by every page of
     *  this mode so that a Power Saving subpage is recognisably one screen
     *  with the Power Saving home. */
    fun sectionHeader(ctx: Context, heading: String): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(ctx, GAP_SECTION) }
            addView(label(ctx, heading))
            addView(space(ctx, GAP_TIGHT))
            addView(rule(ctx))
        }

    /** The mode's list row: label left, value right, hairline beneath.
     *  Replaces every card, tile and list item the default mode would use. */
    fun row(
        ctx: Context,
        heading: String,
        value: String = "",
        valueColour: Int = INK_SECONDARY,
        onTap: (() -> Unit)? = null,
    ): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        setPadding(0, dp(ctx, GAP_TIGHT + STEP), 0, dp(ctx, GAP_TIGHT + STEP))
        addView(
            body(ctx, heading).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        if (value.isNotEmpty()) addView(label(ctx, value, valueColour))
        onTap?.let { handler ->
            isClickable = true
            setOnClickListener { handler() }
        }
    }

    /** An outlined panel — the mode's only grouping container. */
    fun panel(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = outline(ctx)
        setPadding(dp(ctx, GAP), dp(ctx, GAP), dp(ctx, GAP), dp(ctx, GAP))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    /** A status chip — outlined when off, filled when lit. The inversion is
     *  the mode's entire state language; there is no colour to signal with. */
    fun chip(ctx: Context, value: String, lit: Boolean = false): TextView =
        TextView(ctx).apply {
            text = value
            setTextColor(if (lit) INK_ON_LIT else INK_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TYPE_LABEL)
            typeface = FACE_REGULAR
            letterSpacing = 0.12f
            background = if (lit) filled(ctx) else outline(ctx)
            setPadding(dp(ctx, GAP_TIGHT + STEP), dp(ctx, STEP + 2), dp(ctx, GAP_TIGHT + STEP), dp(ctx, STEP + 2))
        }

    /** The page scaffold every Power Saving screen is built into: black to
     *  the edges, generous side gutters, vertical flow. */
    fun page(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(INK_BLACK)
        setPadding(dp(ctx, GAP_WIDE), 0, dp(ctx, GAP_WIDE), 0)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }
}
