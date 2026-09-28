package com.diegonmarcos.superapp.appstore

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * #627 — ONE TAB PER DECLARED STORE SOURCE, and nothing else.
 *
 * The tab strip is a RENDERING of [SourceResolver.Config.kinds], which is a
 * rendering of `resolver.kinds` in the install-source asset. There is no list of
 * stores in Kotlin: add `{"label": "…", "installer": "…", "fetches": false}`
 * under a new kind in that file, rank it in `order`, and the tab appears. Remove
 * it and the tab goes. That is the same by-construction rule #619 used for
 * Declared/Installed — the two COMPOSE here rather than replacing each other:
 * the source tab picks WHICH STORE, the filter picks declared-vs-installed.
 *
 * It lives in its own file, with [labels] separated from [render], so the
 * derivation can be asserted by a test that hands it a kind list it invented.
 * A hardcoded strip would pass a test that only ever sees today's five kinds;
 * one that is handed six has to draw six.
 */
object StoreSourceTabs {

    /** "All", then one label per declared kind, in declared order. The leading
     *  entry is the ABSENCE of a source filter, not a source — [ALL] is null
     *  everywhere a kind is expected. */
    fun labels(ctx: Context, kinds: List<SourceResolver.Kind>): List<String> =
        listOf(ctx.getString(R.string.store_phone_source_all)) + kinds.map { it.label }

    /**
     * The strip. [selected] is the chosen kind, or null for All; [onSelect] is
     * handed the same, and the caller re-renders.
     */
    fun render(
        ctx: Context,
        kinds: List<SourceResolver.Kind>,
        selected: SourceResolver.Kind?,
        onSelect: (SourceResolver.Kind?) -> Unit,
    ): View {
        val strip = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // The All pill and the per-kind pills are built by the SAME function
        // over the SAME derived list, so "All" cannot drift into a special case
        // that is styled or positioned differently from the rest.
        (listOf(null) + kinds).forEachIndexed { i, kind ->
            strip.addView(pill(ctx, labels(ctx, kinds)[i], kind?.id == selected?.id) { onSelect(kind) })
        }
        return HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(strip)
            tag = TAG_STRIP
        }
    }

    private fun pill(ctx: Context, label: String, on: Boolean, onClick: () -> Unit) = TextView(ctx).apply {
        tag = TAG_PREFIX + label
        text = label
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(0xFFFFFFFF.toInt())
        setBackgroundColor(if (on) SELECTED else IDLE)
        val h = (10 * ctx.resources.displayMetrics.density).toInt()
        val v = (7 * ctx.resources.displayMetrics.density).toInt()
        setPadding(h, v, h, v)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(v / 2, v / 4, v / 2, v) }
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** Same green the Declared/Installed pills wear — the two filters read as
     *  one control set because they are one control set. */
    private const val SELECTED = 0xFF48BB78.toInt()
    private const val IDLE = 0xFF2A2A33.toInt()

    /** Every pill is tagged with its own label, and the strip with [TAG_STRIP],
     *  so a test reads what was RENDERED rather than what this file says. */
    const val TAG_PREFIX = "store-source-tab:"
    const val TAG_STRIP = "store-source-tabs"
}
