package com.diegonmarcos.superapp.wallet

import androidx.compose.runtime.Composable
import com.diegonmarcos.superapp.bottomnav.NavDecl
import com.diegonmarcos.superapp.bottomnav.NavPage

/**
 * Contract the host activity implements so the wallet lib can ask
 * for cross-surface navigation without depending on app/.
 *
 * Today: only [onOpenVcard] (handled by MainActivity by pushing the
 * existing BusinessCardFragment onto the back-stack). Future
 * cross-surface entries — open the Virtual Business Card, open a
 * Maps activity, share a pass — land here too.
 */
interface WalletHost {
    /** User tapped the pinned vCard at the top of the deck — host
     *  should open the canonical Virtual Business Card surface. */
    fun onOpenVcard()
    /** User tapped "Check for updates" in Wallet Config — host triggers
     *  the self-update pipeline (Updater.checkNow). */
    fun onCheckForUpdates()
    /** #868 build.json::ui as the host baked it (NavDecl.fromBuildConfig): the bottom bar's items
     *  and the Events strip's pages. A host that declares none gets a wallet with no bar. */
    val nav: NavDecl get() = NavDecl.EMPTY
    /** #868 Draws [pages] (the Events section's) as the fleet's page-tab strip, [selectedId] lit. The HOST
     *  draws it - with libs:bottomnav's PageTabs - so the strip is in the source the nav-shape guard (N5)
     *  reads for the app; the wallet only says which pages and what a tap does. */
    @Composable
    fun PageStrip(pages: List<NavPage>, selectedId: String?, onSelect: (NavPage) -> Unit)
}
