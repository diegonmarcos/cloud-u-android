package com.diegonmarcos.cloudnav.maps

import android.content.Context
import android.os.Bundle
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.navbartop2.NavBarTopSecondRowView

/**
 * #868 the one place a Maps / Cloud-Nav page draws its tab strip: libs:ui-nav-bars's [NavBarTopSecondRowView]
 * (the fleet's second row, #1154), fed a section's declared `pages`. Replaces the Material TabLayouts the
 * Timeline host and Configs used to build by hand.
 *
 * The strip sits INSIDE a content host the shell already pads for the status bar, so it adds no
 * top inset of its own ([NavBarTopSecondRowView.underTopChrome] false).
 */
object MapsPageStrip {

    /** A strip over [pages] with [selectedId] lit. A tap moves the pill, then calls [onSelect]. */
    fun create(ctx: Context, pages: List<NavPage>, selectedId: String?, onSelect: (NavPage) -> Unit): NavBarTopSecondRowView =
        NavBarTopSecondRowView(ctx).also { v ->
            v.pages = pages
            v.selectedId = selectedId
            v.underTopChrome = false
            v.onSelect = { page -> v.selectedId = page.id; onSelect(page) }
        }

    /** Fragment arguments carrying [pages] to a host created by the shell (ids and labels, in order). */
    fun toArgs(pages: List<NavPage>): Bundle = Bundle().apply {
        putStringArray(ARG_IDS, pages.map { it.id }.toTypedArray())
        putStringArray(ARG_LABELS, pages.map { it.label }.toTypedArray())
        putStringArray(ARG_ICONS, pages.map { it.icon }.toTypedArray())
    }

    /** The pages [toArgs] stored, else [fallback] (a host the shell did not feed). */
    fun fromArgs(args: Bundle?, fallback: List<NavPage>): List<NavPage> {
        val ids = args?.getStringArray(ARG_IDS) ?: return fallback
        val labels = args.getStringArray(ARG_LABELS) ?: return fallback
        val icons = args.getStringArray(ARG_ICONS)
        return ids.mapIndexed { i, id -> NavPage(id, labels.getOrElse(i) { id }, icons?.getOrNull(i)?.takeIf { it.isNotBlank() } ?: fallback.firstOrNull { it.id == id }?.icon ?: "tab") }.ifEmpty { fallback }
    }

    private const val ARG_IDS = "page_ids"
    private const val ARG_ICONS = "page_icons"
    private const val ARG_LABELS = "page_labels"
}
