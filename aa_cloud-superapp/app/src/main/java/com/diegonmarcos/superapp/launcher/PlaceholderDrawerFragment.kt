package com.diegonmarcos.superapp.launcher

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.os.bundleOf
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * Generic per-section drawer placeholder shown for sections that haven't yet
 * supplied their own drawer fragment in their libs:<x>/ module. Section
 * label injected via arguments. Compose since #773 (was fragment_placeholder_drawer.xml).
 */
class PlaceholderDrawerFragment : KitComposeFragment() {

    override fun palette() = LauncherPalette.kit(requireContext())

    @Composable
    override fun Content() {
        val p = LocalKitPalette.current
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text(requireArguments().getString(ARG_LABEL) ?: "", color = p.textPrimary,
                style = MaterialTheme.typography.titleMedium)
            Text(
                "No section-specific drawer yet — the module's full sidebar UI lands here as it grows. " +
                    "Use the Home tab for navigation in the meantime.",
                color = p.textSecondary, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }

    companion object {
        private const val ARG_LABEL = "label"
        fun newInstance(label: String) = PlaceholderDrawerFragment().apply {
            arguments = bundleOf(ARG_LABEL to label)
        }
    }
}
