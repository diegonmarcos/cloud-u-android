package com.diegonmarcos.superapp.launcher
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.system.Trace
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitEmptyState

import androidx.compose.runtime.Composable
import androidx.core.os.bundleOf

/**
 * Generic placeholder Fragment for any of the 6 sections. Created with
 * [forSection] using a sectionId string ("mail", "feed", "chat", "cal",
 * "vault", "wg"). Each instance shows the section label + the drawer's
 * currently-selected child label (set later from MainActivity).
 *
 * Real per-section Fragments will eventually live in libs:<x>/ (e.g.
 * MailFragment in libs:mail) — this stub stays around as the default
 * for any section not yet backed by a real module. Compose since #773 (was
 * fragment_section_placeholder.xml). The placeholder's search/refresh/settings toast menu left with the ActionBar.
 */
class SectionFragment : KitComposeFragment() {

    private val sectionId: String get() = requireArguments().getString(ARG_SECTION_ID) ?: ""
    private val sectionLabel: String get() = requireArguments().getString(ARG_SECTION_LABEL) ?: ""

    override fun palette() = LauncherPalette.kit(requireContext())

    @Composable
    override fun Content() {
        Trace.d("SectionFragment", "compose section=$sectionId label=$sectionLabel")
        KitEmptyState(sectionLabel, "Placeholder for libs:$sectionId — real UI lands when the module grows code.")
    }

    companion object {
        private const val ARG_SECTION_ID    = "section_id"
        private const val ARG_SECTION_LABEL = "section_label"

        fun forSection(id: String, label: String) = SectionFragment().apply {
            arguments = bundleOf(ARG_SECTION_ID to id, ARG_SECTION_LABEL to label)
        }
    }
}
