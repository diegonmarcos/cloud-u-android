package com.diegonmarcos.superapp.appstore

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * Store > Feed (#896) - the repo's Commits and CI-CD feeds on one page, its own bottom-nav
 * destination. They were two chips on the Cloud page's second line; they moved here whole.
 *
 * ONE PAGE, TWO TABS - the pattern the Cloud page wears: the declared feeds
 * (assets/appstore-feeds.json, in its order) are the page's top tabs, drawn by [StoreTabs] unless
 * the host draws them (build.json::ui feed.pages, via [StorePages]); the selected feed's rows
 * fill the page. A page with no declared feed draws nothing to pick, and says so.
 */
class StoreFeedFragment : Fragment() {

    private val feeds by lazy { FeedViewer.feeds(requireContext()) }
    private val controls by lazy { StoreControls.load(requireContext()) }
    private val buttons = ArrayList<TextView>()
    private lateinit var host: LinearLayout
    private var stopObserving: (() -> Unit)? = null

    private fun selected(): Int =
        feeds.indexOfFirst { it.id == StorePages.page(SECTION, feeds.firstOrNull()?.id.orEmpty()) }.coerceAtLeast(0)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext()
        host = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = StoreDensity.dp(ctx, StoreDensity.S12); setPadding(p, p, p, p)
        }
        val strip = if (StorePages.hostDrawsStrip || feeds.isEmpty()) null
            else StoreTabs.bar(ctx, listOf(feeds.map { StoreControls.Control(it.label, "", controls.groupTab) }), buttons) {
                StorePages.select(SECTION, feeds[it].id)
            }
        stopObserving = StorePages.observe(SECTION) { view?.post { show(ctx) } }
        show(ctx)
        return StorePages.frame(ctx, strip, ScrollView(ctx).apply { addView(host) }, null)
    }

    override fun onDestroyView() {
        stopObserving?.invoke(); stopObserving = null; buttons.clear(); super.onDestroyView()
    }

    private fun show(ctx: android.content.Context) {
        if (!isAdded || !::host.isInitialized) return
        host.removeAllViews()
        val feed = feeds.getOrNull(selected())
        if (feed == null) { host.addView(StorePages.caption(ctx, "No feed is declared.")); return }
        StoreTabs.paint(buttons, selected())
        FeedViewer.render(ctx, host, feed, FeedViewer.opener(ctx))
    }

    companion object {
        /** The build.json::ui section id this page is. */
        const val SECTION = "feed"
    }
}
