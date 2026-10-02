package com.diegonmarcos.superapp.launcher

import androidx.compose.runtime.Composable
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitEmptyState

/**
 * Empty state shown in the right-hand DETAIL pane of the tablet
 * master-detail layout before the user has opened a page from the master
 * (section) grid. It carries no chrome and just prompts the user toward the
 * master pane. Phones never instantiate this (they use single-pane push
 * navigation). Compose since #773; the prompt takes the palette's secondary
 * ink instead of the old hand-picked 40%-white literal, so it follows the theme.
 */
class DetailPlaceholderFragment : KitComposeFragment() {

    override fun palette() = LauncherPalette.kit(requireContext())

    @Composable
    override fun Content() = KitEmptyState(title = null, caption = "Select an item")

    companion object {
        fun newInstance() = DetailPlaceholderFragment()
    }
}
