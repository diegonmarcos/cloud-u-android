package com.diegonmarcos.superapp.ai

import android.content.Context
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.R as MaterialR

/**
 * The few widgets the three AI tabs are built from, and the ONE place any of them gets a colour.
 *
 * EVERY COLOUR HERE IS A THEME ATTRIBUTE, never a literal and never a fixed colour resource. The
 * app theme is Theme.Material3.DayNight, so an attribute is already the right colour in light, in
 * dark and under the Samsung-black power-saving theme, and it stays right when a theme is added
 * that nobody has written yet. A literal is correct exactly once, on whichever background its
 * author happened to be looking at — the page this file replaced held sixteen of them, tuned for a
 * purple surface, and they were the reason it could not be read in any other theme.
 *
 * Elevation comes the same way: a surface tint attribute rather than a dp shadow, because a
 * shadow drawn on black costs power on an OLED panel to render something invisible.
 */
object AiViews {

    /** Resolve a theme colour attribute. Every colour on these pages comes through here. */
    fun colour(context: Context, attribute: Int): Int {
        val resolved = TypedValue()
        // resolveAttribute answers false for an attribute the theme does not define. That is a
        // theme bug rather than a user's problem, so it falls back to the primary text colour —
        // legible on every surface — instead of leaving the view its default transparent black,
        // which renders as an invisible label on a dark theme and looks like missing text.
        if (!context.theme.resolveAttribute(attribute, resolved, true)) {
            return ContextCompat.getColor(context, android.R.color.primary_text_dark)
        }
        return resolved.data
    }

    fun onSurface(context: Context): Int = colour(context, MaterialR.attr.colorOnSurface)

    /** Secondary text — captions, notes, the quiet half of a status line. */
    fun onSurfaceVariant(context: Context): Int = colour(context, MaterialR.attr.colorOnSurfaceVariant)

    /** Section headings and anything the eye should reach first. */
    fun accent(context: Context): Int = colour(context, MaterialR.attr.colorPrimary)

    /** A state that needs attention: a peer that cannot be reached, a save that failed. */
    fun warning(context: Context): Int = colour(context, MaterialR.attr.colorError)

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    /** A scrolling column with page padding — the outer shell every AI tab uses. */
    fun page(context: Context): Pair<ScrollView, LinearLayout> {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = dp(context, 18)
            setPadding(padding, padding, padding, padding)
        }
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            addView(column)
        }
        return scroll to column
    }

    fun heading(context: Context, text: CharSequence): TextView = TextView(context).apply {
        this.text = text
        textSize = 16f
        setTextColor(accent(context))
        setPadding(0, dp(context, 18), 0, dp(context, 6))
    }

    fun body(context: Context, text: CharSequence): TextView = TextView(context).apply {
        this.text = text
        textSize = 14f
        setTextColor(onSurface(context))
        setPadding(0, dp(context, 2), 0, dp(context, 2))
    }

    fun caption(context: Context, text: CharSequence): TextView = TextView(context).apply {
        this.text = text
        textSize = 12f
        setTextColor(onSurfaceVariant(context))
        setPadding(0, dp(context, 2), 0, dp(context, 8))
    }

    /**
     * A line stating how something stands. [needsAttention] picks the error colour rather than a
     * red literal, so it stays a warning rather than becoming unreadable, on every theme.
     */
    fun status(context: Context, text: CharSequence, needsAttention: Boolean): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 13f
            setTextColor(if (needsAttention) warning(context) else onSurfaceVariant(context))
            setPadding(0, dp(context, 2), 0, dp(context, 6))
        }
}
