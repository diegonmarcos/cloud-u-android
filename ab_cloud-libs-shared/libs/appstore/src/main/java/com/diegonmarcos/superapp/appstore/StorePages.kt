package com.diegonmarcos.superapp.appstore

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * #896 Which child page of a Store section is showing, and who draws the strip for it.
 *
 * Cloud Store declares child pages in build.json::ui (Phone: installed | declared, Feed:
 * commits | cicd) and its host draws them with libs:bottomnav's PageTabs, then calls [select].
 * A host that does NOT draw them (SuperApp's embedded Store) leaves [hostDrawsStrip] false and
 * the fragment draws the same pages itself with [StoreTabs], the strip Cloud already wears.
 * Either way the fragment reads the page from here, so there is one state and one meaning.
 */
object StorePages {
    /** Set once by a host that draws the child-page strip itself (Cloud Store's MainActivity). */
    @Volatile var hostDrawsStrip = false

    private val current = HashMap<String, String>()
    private val observers = HashMap<String, MutableList<(String) -> Unit>>()

    /** The child page a section opens on when nothing was chosen: Phone keeps its long-standing
     *  Declared default (the mode Profile > Store deep-links into); any other section opens on its first. */
    fun defaultPage(section: String, pages: List<String>): String =
        (if (section == StorePhoneFragment.SECTION) StorePhoneFragment.PAGE_DECLARED else null)
            ?.takeIf { it in pages } ?: pages.first()

    fun page(section: String, default: String): String = synchronized(this) { current[section] } ?: default

    fun select(section: String, page: String) {
        val listeners = synchronized(this) {
            if (current[section] == page) return
            current[section] = page
            observers[section]?.toList().orEmpty()
        }
        listeners.forEach { it(page) }
    }

    /** Calls [fn] on every later change of [section]'s page; the returned lambda stops it. */
    fun observe(section: String, fn: (String) -> Unit): () -> Unit {
        synchronized(this) { observers.getOrPut(section) { ArrayList() }.add(fn) }
        return { synchronized(this) { observers[section]?.remove(fn) } }
    }

    /** The shared Store page frame: the tab strip on top (when drawn here), the page's content filling
     *  the middle, and its action bar at the bottom - the pattern the Cloud page wears. */
    fun frame(ctx: Context, strip: View?, content: View, actions: View?): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = StoreDensity.dp(ctx, StoreDensity.S8)
            if (strip != null) {
                strip.setPadding(p, p, p, 0); addView(strip)
            }
            addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            if (actions != null) addView(Capped(ctx, actions), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

    /** The bottom action bar: scrolls inside at most [MAX_BAR_PERCENT] of the screen, so a long bar
     *  never eats the list. */
    private class Capped(ctx: Context, bar: View) : ScrollView(ctx) {
        init {
            val p = StoreDensity.dp(ctx, StoreDensity.S8)
            setBackgroundColor(0xFF14141A.toInt())
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; setPadding(p, p, p, p); addView(bar)
            })
        }
        override fun onMeasure(w: Int, h: Int) {
            val cap = resources.displayMetrics.heightPixels * MAX_BAR_PERCENT / 100
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST))
        }
    }

    private const val MAX_BAR_PERCENT = 38

    /** A plain caption row shared by the new pages. */
    fun caption(ctx: Context, t: String) = TextView(ctx).apply {
        text = t; textSize = StoreDensity.T_META; setTextColor(0x99FFFFFF.toInt())
        setPadding(0, 0, 0, StoreDensity.dp(ctx, StoreDensity.S6))
    }
}
