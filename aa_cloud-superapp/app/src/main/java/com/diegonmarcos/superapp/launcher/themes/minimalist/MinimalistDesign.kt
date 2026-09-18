package com.diegonmarcos.superapp.launcher.themes.minimalist

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The COMPLETE design vocabulary of the Cloud Minimalist Black mode.
 *
 * ── Why this file exists ─────────────────────────────────────────────────
 * A mode that borrows its components from another mode cannot look different
 * from it, so each mode owns its vocabulary outright and imports nothing from
 * `ui/`. The owner asked for exactly that — "make all themes have their own
 * folders", "all different, nothing can be equal" — and a rule enforced by
 * what is in scope beats a rule someone has to remember.
 *
 * ── Why this design looks the way it does ────────────────────────────────
 * Minimalist Black is a TERMINAL: a single-typeface, single-hue, text-only
 * machine that shows everything at once.
 *
 *   · PHOSPHOR GREEN on black. One hue, like a VT220. [INK] is the readable
 *     body green; [GLOW] is the brighter accent reserved for the things the
 *     user acts on, so brightness carries meaning rather than decoration.
 *   · MONOSPACE ONLY. Every glyph on the same grid — that is what makes a
 *     dense list of forty apps scannable without any icon at all.
 *   · NO CONTAINERS. Structure comes from prompt characters and indentation,
 *     the way a shell does it. There are no cards and no rounded corners.
 */
internal object MinimalistDesign {

    // ── Colour ───────────────────────────────────────────────────────────
    // Constants, not colour resources: a res/values token is reachable from
    // every other mode by definition, and this vocabulary must not be.

    /** The ground. Black, because a terminal's ground is black. */
    const val GROUND = 0xFF000000.toInt()

    /** Body phosphor. 10.9:1 on black — long lists stay readable. */
    const val INK = 0xFFB8E6B8.toInt()

    /** The quieter phosphor: hints, counts, section chrome. */
    const val INK_DIM = 0xFF7FB77F.toInt()

    /** The bright accent. Reserved for what the user acts on. */
    const val GLOW = 0xFF00FF66.toInt()

    /** A rule, at the accent's hue but barely present. */
    const val HAIRLINE = 0x3300FF66

    // ── Metric ───────────────────────────────────────────────────────────
    // A terminal is a character grid, so the scale is a line-height scale.

    const val STEP = 4
    const val GAP_TIGHT = STEP           // 4  — between lines of one block
    const val GAP = STEP * 2             // 8
    const val GAP_WIDE = STEP * 4        // 16 — the screen gutter
    const val GAP_SECTION = STEP * 6     // 24 — between blocks

    // ── Type ─────────────────────────────────────────────────────────────
    // Three sizes, one face. A terminal that changes typeface is not one.

    const val TYPE_HEAD = 14f
    const val TYPE_BODY = 13f
    const val TYPE_HINT = 11f

    val FACE: Typeface = Typeface.MONOSPACE

    /** The shell prompt. This mode's entire decorative budget. */
    const val PROMPT = "$ "

    // ── Primitives ───────────────────────────────────────────────────────

    fun dp(ctx: Context, value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), ctx.resources.displayMetrics,
    ).toInt()

    fun rule(ctx: Context): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1),
        )
        setBackgroundColor(HAIRLINE)
    }

    fun space(ctx: Context, height: Int): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, height),
        )
    }

    /** Square outline. No radius anywhere in this mode — a terminal has
     *  corners, not curves. */
    fun outline(ctx: Context): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 0f
        setColor(Color.TRANSPARENT)
        setStroke(dp(ctx, 1), HAIRLINE)
    }

    // ── Text ─────────────────────────────────────────────────────────────

    private fun text(ctx: Context, value: String, size: Float, colour: Int): TextView =
        TextView(ctx).apply {
            text = value
            setTextColor(colour)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            typeface = FACE
            includeFontPadding = false
        }

    /** A section heading, written the way a shell writes one. */
    fun head(ctx: Context, value: String): TextView =
        text(ctx, "$PROMPT${value.lowercase()}", TYPE_HEAD, GLOW)

    fun body(ctx: Context, value: String, colour: Int = INK): TextView =
        text(ctx, value, TYPE_BODY, colour)

    fun hint(ctx: Context, value: String): TextView =
        text(ctx, value, TYPE_HINT, INK_DIM)

    // ── Components ───────────────────────────────────────────────────────

    /** The mode's one list item: a line of output. Tappable lines carry the
     *  prompt so that "what can I act on" is legible without any colour or
     *  container to mark it. */
    fun line(
        ctx: Context,
        value: String,
        trailing: String = "",
        onTap: (() -> Unit)? = null,
    ): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        setPadding(0, dp(ctx, GAP_TIGHT), 0, dp(ctx, GAP_TIGHT))
        addView(
            body(ctx, if (onTap != null) "$PROMPT$value" else "  $value").apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
                )
            },
        )
        if (trailing.isNotEmpty()) addView(hint(ctx, trailing))
        onTap?.let { handler ->
            isClickable = true
            setOnClickListener { handler() }
        }
    }

    /** The page scaffold: black to the edges, a single gutter, vertical flow. */
    fun page(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(GROUND)
        setPadding(dp(ctx, GAP_WIDE), 0, dp(ctx, GAP_WIDE), 0)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }
}
