package com.diegonmarcos.cloudc3.pages

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.diegonmarcos.cloudc3.Declarations
import com.diegonmarcos.cloudc3.R

/**
 * #648 a content tab: a strip over that tab's DECLARED pages, and one container the
 * selected page's fragment is swapped into.
 *
 * ONE implementation for every paged tab, so adding a page is a build.json edit. The strip
 * is built from the declaration at runtime — the labels and icons are never spelled here.
 *
 * THE RULE THIS CLASS ENFORCES AT RUNTIME, and the tester at build time: a declared page
 * MUST resolve to a real fragment. [pageFragment] returning null is treated as a
 * programming error and says so on screen, rather than drawing an empty pane that looks
 * like a page with nothing in it. There is deliberately no "not built yet" state to fall
 * back into: a tab the declaration lists is a tab that works, and the only way to keep
 * that true is to declare a page when it is implemented and not before.
 */
abstract class PagedFragment : Fragment(R.layout.fragment_paged) {

    /** That tab's declared pages, from build.json::ui.<tab>.pages. */
    abstract fun pages(): List<Declarations.PageDecl>

    /** The real fragment for a declared page id. Null means the declaration is a lie. */
    abstract fun pageFragment(pageId: String): Fragment?

    private var selected: String? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val strip = view.findViewById<LinearLayout>(R.id.page_strip)
        val declared = pages()
        val restored = savedInstanceState?.getString(STATE_PAGE)
        val initial = restored?.takeIf { id -> declared.any { it.id == id } }
            ?: declared.firstOrNull()?.id

        // A single declared page needs no strip: the tab IS that page, and a one-item
        // picker is chrome that cannot be used.
        strip.visibility = if (declared.size > 1) View.VISIBLE else View.GONE
        strip.removeAllViews()
        for (page in declared) {
            strip.addView(chip(page) { open(it, strip) })
        }
        initial?.let { open(it, strip) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PAGE, selected)
    }

    private fun open(pageId: String, strip: LinearLayout) {
        if (pageId == selected) return
        val fragment = pageFragment(pageId)
        if (fragment == null) {
            // A declared page with no fragment. Stated, not drawn blank.
            childFragmentManager.commit {
                setReorderingAllowed(true)
                replace(R.id.page_container, UndeclaredPageFragment.newInstance(pageId))
            }
            selected = pageId
            paint(strip)
            return
        }
        selected = pageId
        paint(strip)
        childFragmentManager.commit {
            setReorderingAllowed(true)
            replace(R.id.page_container, fragment, pageId)
        }
    }

    /** The lit chip is the selected page. Colours come from colors.xml, never a literal. */
    private fun paint(strip: LinearLayout) {
        for (i in 0 until strip.childCount) {
            val chip = strip.getChildAt(i) as? TextView ?: continue
            val on = chip.tag == selected
            val bg = ContextCompat.getColor(
                requireContext(),
                if (on) R.color.c3_accent else R.color.c3_surface_raised,
            )
            val ink = ContextCompat.getColor(
                requireContext(),
                if (on) R.color.c3_on_accent else R.color.c3_text_secondary,
            )
            (chip.background as? GradientDrawable)?.setColor(bg)
            chip.setTextColor(ink)
        }
    }

    private fun chip(page: Declarations.PageDecl, onTap: (String) -> Unit): TextView {
        val ctx = requireContext()
        val d = ctx.resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        return TextView(ctx).apply {
            tag = page.id
            text = page.label
            gravity = Gravity.CENTER
            setPadding(px(12), px(6), px(12), px(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = px(50).toFloat()
                setColor(Color.TRANSPARENT)
            }
            @Suppress("DiscouragedApi")
            val icon = ctx.resources.getIdentifier(page.icon, "drawable", ctx.packageName)
            if (icon != 0) {
                setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
                compoundDrawablePadding = px(6)
            }
            setOnClickListener { onTap(page.id) }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.marginEnd = px(8)
            layoutParams = lp
        }
    }

    private companion object {
        const val STATE_PAGE = "c3_selected_page"
    }
}
