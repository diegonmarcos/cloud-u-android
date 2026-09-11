package com.diegonmarcos.superapp.browser

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import java.io.File

/**
 * GRID mode: the tab switcher.
 *
 * This replaced a GridLayout that had cards added to it in a loop. That
 * shape could not support item 1 at all — drag-to-reorder on Android is
 * [ItemTouchHelper], [ItemTouchHelper] drives a [RecyclerView], and a
 * GridLayout is not one. The cards look the same; what changed under
 * them is that there is now a position model to move things around in.
 *
 * Long-press picks a card up, which is what the owner asked for, and is
 * also why every per-tab action lives behind the card's ⋮ instead of
 * behind a long-press menu: the gesture is spoken for.
 */
class BrowserTabGrid(
    context: Context,
    private val onOpen: (BrowserTab) -> Unit,
    private val onClose: (BrowserTab) -> Unit,
    private val onMenu: (BrowserTab, View) -> Unit,
    private val onToggleGroup: (String) -> Unit,
    private val onReorder: (List<String>) -> Unit,
) : RecyclerView(context) {

    private val adapter0 = Adapter()

    init {
        layoutManager = GridLayoutManager(context, SPANS).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                // A group header is a divider, not a card — it owns the row.
                override fun getSpanSize(position: Int): Int =
                    if (adapter0.rowAt(position) is BrowserGridRow.GroupHeader) SPANS else 1
            }
        }
        adapter = adapter0
        clipToPadding = false
        val pad = dp(context, 8)
        setPadding(pad, pad, pad, dp(context, 24))
        ItemTouchHelper(DragCallback()).attachToRecyclerView(this)
    }

    fun submit(rows: List<BrowserGridRow>) = adapter0.submit(rows)

    // ── drag ─────────────────────────────────────────────────────────

    private inner class DragCallback : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN or
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
        0, // no swipe: a swipe-to-dismiss would close pinned tabs by accident
    ) {
        override fun isLongPressDragEnabled(): Boolean = true

        override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int =
            // Headers do not move; dragging one would reorder nothing.
            if (adapter0.rowAt(vh.bindingAdapterPosition) is BrowserGridRow.GroupHeader) {
                makeMovementFlags(0, 0)
            } else {
                super.getMovementFlags(rv, vh)
            }

        override fun canDropOver(
            rv: RecyclerView,
            cur: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ): Boolean =
            adapter0.canMove(cur.bindingAdapterPosition, target.bindingAdapterPosition)

        override fun onMove(
            rv: RecyclerView,
            vh: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ): Boolean =
            adapter0.move(vh.bindingAdapterPosition, target.bindingAdapterPosition)

        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            // Persist on drop, not on every intermediate onMove — the
            // order that has to survive a restart is the one he let go of.
            onReorder(adapter0.visibleTabUrls())
        }
    }

    // ── adapter ──────────────────────────────────────────────────────

    private inner class Adapter : RecyclerView.Adapter<Holder>() {

        private val rows = ArrayList<BrowserGridRow>()

        fun rowAt(pos: Int): BrowserGridRow? = rows.getOrNull(pos)

        fun submit(next: List<BrowserGridRow>) {
            rows.clear(); rows.addAll(next); notifyDataSetChanged()
        }

        fun visibleTabUrls(): List<String> =
            BrowserGridRows.visibleTabs(rows).map { it.url }

        /** Adapter position → index within the drawn tab list. */
        private fun tabIndex(pos: Int): Int {
            if (rows.getOrNull(pos) !is BrowserGridRow.TabCard) return -1
            var n = 0
            for (i in 0 until pos) if (rows[i] is BrowserGridRow.TabCard) n++
            return n
        }

        fun canMove(from: Int, to: Int): Boolean {
            val fi = tabIndex(from); val ti = tabIndex(to)
            if (fi < 0 || ti < 0) return false
            return BrowserTabOrder.canMove(BrowserGridRows.visibleTabs(rows), fi, ti)
        }

        fun move(from: Int, to: Int): Boolean {
            if (!canMove(from, to)) return false
            rows.add(to, rows.removeAt(from))
            notifyItemMoved(from, to)
            return true
        }

        override fun getItemCount(): Int = rows.size

        override fun getItemViewType(position: Int): Int =
            if (rows[position] is BrowserGridRow.GroupHeader) TYPE_HEADER else TYPE_CARD

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val ctx = parent.context
            return if (viewType == TYPE_HEADER) Holder(headerView(ctx))
            else Holder(cardView(ctx))
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            when (val row = rows[position]) {
                is BrowserGridRow.GroupHeader -> bindHeader(holder.itemView, row)
                is BrowserGridRow.TabCard     -> bindCard(holder.itemView, row.tab)
            }
        }
    }

    private class Holder(v: View) : RecyclerView.ViewHolder(v)

    // ── header ───────────────────────────────────────────────────────

    private fun headerView(ctx: Context): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setPadding(dp(ctx, 10), dp(ctx, 14), dp(ctx, 10), dp(ctx, 6))
        addView(TextView(ctx).apply {
            id = ID_HEADER_TEXT
            setTextColor(0xFFE9D8FD.toInt())
            typeface = Typeface.DEFAULT_BOLD
        })
    }

    private fun bindHeader(v: View, row: BrowserGridRow.GroupHeader) {
        val label = v.findViewById<TextView>(ID_HEADER_TEXT)
        val chevron = if (row.collapsed) "▸" else "▾"
        label.text = "$chevron  ${row.group}  (${row.count})"
        v.setOnClickListener { onToggleGroup(row.group) }
    }

    // ── card ─────────────────────────────────────────────────────────

    private fun cardView(ctx: Context): View {
        val cardW = (ctx.resources.displayMetrics.widthPixels / SPANS) - dp(ctx, 20)
        return FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(ctx, 14).toFloat()
                setColor(0xFF1A0033.toInt())
                setStroke(1, 0x55B794F4)
            }
            layoutParams = RecyclerView.LayoutParams(cardW, dp(ctx, 220)).apply {
                val m = dp(ctx, 6); setMargins(m, m, m, m)
            }
            clipToOutline = true

            addView(ImageView(ctx).apply {
                id = ID_PREVIEW
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT)
            })

            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(0xCC000000.toInt())
                setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 4), dp(ctx, 6))
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM)
                addView(TextView(ctx).apply {
                    id = ID_PIN
                    setTextColor(0xFFFFD166.toInt())
                })
                addView(TextView(ctx).apply {
                    id = ID_TITLE
                    setTextColor(Color.WHITE)
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(ctx).apply {
                    id = ID_CLOSE
                    text = " ✕ "
                    setTextColor(0xCCFFFFFF.toInt())
                })
                addView(TextView(ctx).apply {
                    id = ID_MENU
                    text = " ⋮ "
                    setTextColor(Color.WHITE)
                    typeface = Typeface.DEFAULT_BOLD
                })
            })
        }
    }

    private fun bindCard(v: View, tab: BrowserTab) {
        val preview = v.findViewById<ImageView>(ID_PREVIEW)
        val file = tab.previewPath.takeIf { it.isNotBlank() }?.let { File(it) }
        val bmp = if (file != null && file.exists()) {
            runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
        } else null
        if (bmp != null) {
            preview.setImageBitmap(bmp)
        } else {
            preview.setImageDrawable(
                GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(0xFF2D1B69.toInt(), 0xFF1A0033.toInt()),
                )
            )
        }

        v.findViewById<TextView>(ID_PIN).apply {
            text = if (tab.pinned) "📌 " else ""
            visibility = if (tab.pinned) View.VISIBLE else View.GONE
        }
        v.findViewById<TextView>(ID_TITLE).text = tab.title.ifBlank { tab.url }

        // A pinned tab has NO close affordance. BrowserTabPrefs.remove
        // would refuse it anyway, but drawing a ✕ that does nothing is
        // worse than drawing none — unpin from ⋮ is the way out.
        v.findViewById<TextView>(ID_CLOSE).apply {
            visibility = if (tab.pinned) View.GONE else View.VISIBLE
            setOnClickListener { if (!tab.pinned) onClose(tab) }
        }
        v.findViewById<TextView>(ID_MENU).setOnClickListener { anchor -> onMenu(tab, anchor) }
        v.setOnClickListener { onOpen(tab) }
    }

    private companion object {
        const val SPANS = 2
        const val TYPE_HEADER = 0
        const val TYPE_CARD = 1
        val ID_PREVIEW     = View.generateViewId()
        val ID_TITLE       = View.generateViewId()
        val ID_CLOSE       = View.generateViewId()
        val ID_MENU        = View.generateViewId()
        val ID_PIN         = View.generateViewId()
        val ID_HEADER_TEXT = View.generateViewId()

        fun dp(ctx: Context, v: Int): Int =
            (v * ctx.resources.displayMetrics.density).toInt()
    }
}
