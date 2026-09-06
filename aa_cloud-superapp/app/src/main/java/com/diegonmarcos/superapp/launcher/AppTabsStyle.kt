package com.diegonmarcos.superapp.launcher

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.tabs.TabLayout

/**
 * Single-call helper that re-styles a Material [TabLayout] as the
 * SuperApp's "liquid-glass pill tab" row — matching the browser-tab
 * chip strip below it in Home Apps and the broader glassmorphism +
 * pastel-lavender language the rest of the app uses.
 *
 * Visual contract:
 *   • Selected pill — translucent royal-purple fill (0x44 7C3AED) +
 *     1dp lavender border (0x66 E9D8FD) + white monospace caps text.
 *   • Unselected pill — 13% white fill + 20% white border + 67%
 *     white text.
 *   • TabLayout's default Material chrome (gray background, blue
 *     underline indicator, gray ripple) is stripped.
 *
 * Apply at the TabLayout's construction site, AFTER `addTab(...)`
 * calls have populated the tabs. The helper installs its OWN
 * OnTabSelectedListener (in ADDITION to whatever the caller wires up
 * for navigation) — it only updates pill visuals and never consumes
 * the event.
 */
object AppTabsStyle {

    fun apply(tabLayout: TabLayout) {
        val ctx = tabLayout.context

        // ── 1. Strip default Material chrome ─────────────────────
        tabLayout.setBackgroundColor(0x00000000)
        tabLayout.setSelectedTabIndicatorColor(0x00000000)
        tabLayout.setSelectedTabIndicatorHeight(0)
        tabLayout.tabRippleColor = null
        val pad = dp(ctx, 4)
        tabLayout.setPadding(pad, pad, pad, pad)

        // ── 2. Replace each tab's content with a pill custom view ─
        for (i in 0 until tabLayout.tabCount) {
            val tab = tabLayout.getTabAt(i) ?: continue
            val label = tab.text?.toString().orEmpty()
            tab.customView = makePill(ctx, label, selected = i == tabLayout.selectedTabPosition)
        }

        // ── 3. Add a state-only listener — purely visual, never
        //       consumes the event so navigation listeners installed
        //       by the caller continue to fire. ──────────────────
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab)   { repaint(tab.customView, true) }
            override fun onTabUnselected(tab: TabLayout.Tab) { repaint(tab.customView, false) }
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    /**
     * One width for every pill, sized to the longest label actually present —
     * then shrink the TEXT rather than the pill if that will not fit.
     *
     * THIS IS THE OTHER HALF OF [apply], and calling one without the other is
     * what "ragged pills" means. [apply] paints the chrome; this measures it.
     * [TabLayout.MODE_FIXED] alone is not enough — it divides the strip evenly
     * only while the content fits, and past that the tabs go back to sizing
     * themselves, so a strip reading CONNECT / WIREGUARD / AI / INFOS comes out
     * ragged. Ragged tabs also MOVE as you change section, which is the part
     * that actually reads as broken.
     *
     * So the slot is measured, not negotiated. The pills are MONOSPACE
     * ([makePill]), so one character width describes any string: the target is
     * the LONGEST label in the strip, with a floor of [MIN_CHARS]. When N slots
     * exceed the strip the FONT gives way, a step at a time down to [MIN_SP],
     * because a smaller word is still readable while a clipped one is not. Only
     * if even the floor overflows does the strip become scrollable — the honest
     * last resort: nothing is hidden, it just no longer fits at once.
     *
     * The slot is the pill's own padding and margin PLUS the padding of the
     * TabView that TabLayout wraps every customView in. Forgetting that last
     * term is what let the row run past the frame and get clipped: the pill was
     * sized to fill its share exactly, then ~24dp of invisible chrome was added
     * around it. It is zeroed and then re-measured.
     *
     * @param divider read LAZILY, because a caller that inserts a separator
     *   into the strip does so AFTER constructing the [TabLayout] — by the time
     *   the layout pass runs it is there, but at call time it is not. It lives
     *   inside the strip while not being a tab, so its width is not the tabs'
     *   to divide; it is also restyled here to match whatever size the labels
     *   ended up at. Defaults to none, which measures exactly as before.
     */
    @JvmOverloads
    fun equalise(tabLayout: TabLayout, divider: () -> View? = { null }) {
        // A one-shot LAYOUT listener, not post(): post() on a not-yet-attached
        // view runs on attach, which can still be before the first layout pass —
        // width would be 0 and the whole thing would silently do nothing. This
        // fires only once there is a real width to measure against.
        tabLayout.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View, l: Int, t: Int, r: Int, b: Int,
                ol: Int, ot: Int, or_: Int, ob: Int,
            ) {
                if (v.width <= 0) return
                v.removeOnLayoutChangeListener(this)
                applyEqual(tabLayout, divider)
            }
        })
    }

    private fun applyEqual(tabs: TabLayout, divider: () -> View?) {
        val n = tabs.tabCount
        if (n == 0) return
        val bar = divider()
        // The separator lives in the strip but is not a tab, so its width is
        // not the tabs' to divide. One glyph is small, but it is not free:
        // MODE_FIXED shares what is LEFT after it among the TabViews, while the
        // pills are sized from this figure — so skipping the subtraction sizes
        // every pill a few px wider than the TabView holding it and the text
        // clips.
        val avail = tabs.width - tabs.paddingStart - tabs.paddingEnd - (bar?.width ?: 0)
        if (avail <= 0) return

        // The pill custom view is what the eye sees; TabLayout's own TabView is
        // invisible and MODE_FIXED already makes those equal.
        val pills = (0 until n).mapNotNull { tabs.getTabAt(it)?.customView }
        if (pills.size != n) return
        val labels = pills.mapNotNull { findLabel(it) }
        if (labels.size != n) return

        val dm = tabs.resources.displayMetrics
        val padPx = PILL_PAD_DP * dm.density * 2       // makePill's 14dp per side
        val marginPx = PILL_MARGIN_DP * dm.density * 2 // and its 3dp per side
        val minPx = MIN_SP * dm.scaledDensity
        val perTab = avail.toFloat() / n               // MODE_FIXED's share

        // TabLayout wraps every customView in a TabView carrying its OWN
        // horizontal padding — Material's default tabPaddingStart/End, 12dp a
        // side. [apply] strips the background, the indicator and the ripple but
        // never that. The pill supplies every bit of padding the eye can see,
        // so the chrome is pure waste — zero it, then read back what is
        // actually left, because a re-applied style would otherwise put us
        // straight back into overflow.
        val tabViews = pills.map { it.parent as? View }
        tabViews.forEach { tv -> tv?.setPadding(0, tv.paddingTop, 0, tv.paddingBottom) }
        val chrome = (tabViews.maxOfOrNull { (it?.paddingStart ?: 0) + (it?.paddingEnd ?: 0) } ?: 0).toFloat()

        val budget = perTab - padPx - marginPx - chrome
        if (budget <= 0f) return

        val paint = android.text.TextPaint().apply {
            typeface = labels[0].typeface
            letterSpacing = labels[0].letterSpacing
        }
        fun charWidth(sizePx: Float): Float {
            paint.textSize = sizePx
            return paint.measureText("M")
        }

        val longest = labels.maxOf { it.text.length }
        var sizePx = labels[0].textSize
        var chars = longest
        while (true) {
            if (chars * charWidth(sizePx) <= budget) break
            if (sizePx > minPx) { sizePx -= dm.density; continue }   // font first
            // At the floor: keep the box and drop characters, but never below
            // the guarantee. +1 pays for the ellipsis so MIN_CHARS stay READABLE
            // rather than MIN_CHARS-1 plus a dot.
            chars = maxOf(MIN_CHARS + 1, (budget / charWidth(sizePx)).toInt())
            break
        }

        val pillPx = chars * charWidth(sizePx) + padPx
        val overflows = pillPx + marginPx + chrome > perTab
        tabs.tabMode = if (overflows) TabLayout.MODE_SCROLLABLE else TabLayout.MODE_FIXED

        // In FIXED mode the slot is hard: a pill wider than its share is not
        // "slightly too big", it is the row running past the frame and getting
        // cut. Clamp. Only the SCROLLABLE branch may exceed a slot, because
        // there the strip scrolls instead of clipping.
        val width = if (overflows) pillPx.toInt()
                    else minOf(pillPx, perTab - marginPx - chrome).toInt()
        for (i in 0 until n) {
            labels[i].setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sizePx)
            labels[i].isSingleLine = true
            labels[i].maxLines = 1
            labels[i].ellipsize = android.text.TextUtils.TruncateAt.END
            pills[i].layoutParams = pills[i].layoutParams.apply { this.width = width }
            pills[i].minimumWidth = width
        }

        // Set the separator in the type the labels ACTUALLY ended up in, not
        // the type they started in: the loop above may have resized them down
        // to MIN_SP, and it is exactly the crowded strips that have one. Colour
        // comes from an UNSELECTED label so it reads as punctuation rather than
        // as the active tab.
        (bar as? TextView)?.let { b ->
            val dim = (0 until n).firstOrNull { it != tabs.selectedTabPosition }
                ?.let { labels[it] } ?: labels[0]
            b.typeface = dim.typeface
            b.letterSpacing = dim.letterSpacing
            b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sizePx)
            b.setTextColor(dim.currentTextColor)
        }
        tabs.requestLayout()
    }

    /** First TextView inside a pill. [makePill] wraps the label in a
     *  LinearLayout, but this walks rather than assuming that shape stays. */
    private fun findLabel(v: View?): TextView? = when (v) {
        null -> null
        is TextView -> v
        is android.view.ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { findLabel(v.getChildAt(it)) }
        else -> null
    }

    /** Never show fewer than this many letters of a label. */
    private const val MIN_CHARS = 7
    /** [makePill]'s horizontal padding and margin, per side — the numbers now
     *  live beside the code that applies them. */
    private const val PILL_PAD_DP = 14f
    private const val PILL_MARGIN_DP = 3f
    private const val MIN_SP = 8f

    /** Build a single pill — outer LinearLayout with a pill bg
     *  drawable + inner monospace caps TextView. */
    private fun makePill(ctx: Context, label: String, selected: Boolean): View {
        val tile = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = makePillBg(ctx, selected)
            val px = dp(ctx, 14); val py = dp(ctx, 8)
            setPadding(px, py, px, py)
            val m = dp(ctx, 3)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(m, m, m, m) }
        }
        tile.addView(TextView(ctx).apply {
            text = label.uppercase()
            setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xAAFFFFFFL.toInt())
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = 12f
            letterSpacing = 0.08f
            isAllCaps = true
        })
        return tile
    }

    /** Rounded-rectangle pill background — different fill + stroke
     *  per selection state so the user can read the active tab at a
     *  glance even without the (stripped) underline indicator. */
    private fun makePillBg(ctx: Context, selected: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(ctx, 18).toFloat()
        if (selected) {
            setColor(0x447C3AED)
            setStroke(dp(ctx, 1), 0x66E9D8FD)
        } else {
            setColor(0x22FFFFFFL.toInt())
            setStroke(dp(ctx, 1), 0x33FFFFFF)
        }
    }

    /** Update an existing pill's background + text color in-place on
     *  selection-state change. Tolerant of nulls + unexpected view
     *  shapes (returns silently). */
    private fun repaint(view: View?, selected: Boolean) {
        val tile = view as? LinearLayout ?: return
        tile.background = makePillBg(tile.context, selected)
        (tile.getChildAt(0) as? TextView)?.setTextColor(
            if (selected) 0xFFFFFFFF.toInt() else 0xAAFFFFFFL.toInt(),
        )
    }

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
}
