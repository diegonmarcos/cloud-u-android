package com.diegonmarcos.superapp.launcher

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.card.MaterialCardView

/** The card grid an INDEX row is drawn with.
 *
 *  Extracted from AggregatorStackFragment.renderTileRow so the identical
 *  look is reachable from a page that is NOT a stack — Configs ▸ About is a
 *  hand-built fragment with no build.json panels at all, and its index has
 *  to be the same object as C3 ▸ Topology's or the two drift apart.
 *
 *  Icons come either as a drawable res (build.json tiles name one) or as a
 *  short glyph (Configs ▸ About's macro headers are already prefixed with
 *  one). Neither is invented here: a tile with no icon of either kind draws
 *  its label alone rather than borrowing a picture that means something
 *  else.
 */
object IndexTiles {

    /** Standard index-tile height, also the height of the padding cells that
     *  keep a short last row tile-sized instead of stretched. */
    private const val TILE_HEIGHT_DP = 96

    data class Cell(
        val label: String,
        /** Drawable resource, or 0 when there is none. */
        val iconRes: Int = 0,
        /** Glyph shown when [iconRes] is 0 — an emoji is a perfectly good
         *  icon and every About macro header already carries one. */
        val glyph: String = "",
        val onClick: () -> Unit,
    )

    /** [cells] laid out `cols` per row, wrapping, last row padded. A single
     *  row that already fits within `cols` comes out exactly as the old flat
     *  horizontal row did. */
    fun grid(ctx: Context, cols: Int, cells: List<Cell>): View {
        val columns = cols.coerceAtLeast(1)
        val rows = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        var row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        var inRow = 0
        for (cell in cells) {
            if (inRow == columns) {
                rows.addView(row)
                row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                inRow = 0
            }
            inRow++
            row.addView(card(ctx, cell))
        }
        if (inRow > 0) {
            repeat(columns - inRow) { row.addView(spacer(ctx)) }
            rows.addView(row)
        }
        return rows
    }

    private fun card(ctx: Context, cell: Cell): View {
        val card = MaterialCardView(ctx).apply {
            radius        = dp(ctx, 12).toFloat()
            cardElevation = 0f
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, TILE_HEIGHT_DP), 1f).apply {
                val m = dp(ctx, 4); setMargins(m, m, m, m)
            }
            isClickable = true; isFocusable = true
        }
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val pad = dp(ctx, 8); setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        when {
            cell.iconRes != 0 -> inner.addView(ImageView(ctx).apply {
                setImageResource(cell.iconRes)
                val sz = dp(ctx, 28); layoutParams = LinearLayout.LayoutParams(sz, sz)
            })
            cell.glyph.isNotBlank() -> inner.addView(TextView(ctx).apply {
                text = cell.glyph
                textSize = 20f
                gravity = Gravity.CENTER
            })
        }
        inner.addView(TextView(ctx).apply {
            text = cell.label
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            gravity = Gravity.CENTER
            maxLines = 2
        })
        card.addView(inner)
        card.setOnClickListener { cell.onClick() }
        return card
    }

    private fun spacer(ctx: Context): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(0, dp(ctx, TILE_HEIGHT_DP), 1f)
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()
}
