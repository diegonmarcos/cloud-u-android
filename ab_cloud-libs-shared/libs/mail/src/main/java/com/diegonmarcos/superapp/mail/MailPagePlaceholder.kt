package com.diegonmarcos.superapp.mail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.os.bundleOf
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * Generic placeholder for any Mail page. Title + upstream-class hint shown at
 * the top; optional children list is filled in for Settings (11 sub-pages)
 * and More (8 overflow items). Each child row swaps the host Fragment's
 * content to the corresponding child placeholder.
 *
 * One Fragment class serves all 28 pages (1 main + 11 settings + 8 more + …)
 * — the actual FairEmail UI cherry-picks replace these slot-by-slot.
 *
 * 2026-10-16 owner decision: all apps must be Compose. A KitComposeFragment over the host theme's colours.
 */
class MailPagePlaceholder : KitComposeFragment() {

    override fun palette(): KitPalette = requireContext().mailKitPalette()

    @Composable
    override fun Content() {
        val p = LocalKitPalette.current
        val args = requireArguments()
        val children = args.getStringArray(ARG_CHILDREN) ?: emptyArray()
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(args.getString(ARG_TITLE).orEmpty(), color = p.textPrimary, style = MaterialTheme.typography.titleMedium)
            MailDim("upstream: ${args.getString(ARG_UPSTREAM) ?: "—"}", MaterialTheme.typography.labelSmall,
                Modifier.padding(top = 2.dp), alpha = 0.6f, mono = true)
            MailDivider()
            for (child in children) {
                // Each child string is "label|upstream"
                val (label, upstream) = child.split('|', limit = 2).let { it[0] to (it.getOrNull(1) ?: "") }
                Column(
                    Modifier.fillMaxWidth().clickable {
                        parentFragmentManager.beginTransaction()
                            .replace(id, newInstance(label, upstream, emptyArray()))
                            .addToBackStack(null)
                            .commit()
                    }.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(label, color = p.textPrimary, style = MaterialTheme.typography.bodyLarge)
                    MailDim(upstream, MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }

    companion object {
        private const val ARG_TITLE    = "title"
        private const val ARG_UPSTREAM = "upstream"
        private const val ARG_CHILDREN = "children"   // "label|upstream" strings

        fun newInstance(title: String, upstream: String, children: Array<String> = emptyArray()) =
            MailPagePlaceholder().apply {
                arguments = bundleOf(
                    ARG_TITLE to title,
                    ARG_UPSTREAM to upstream,
                    ARG_CHILDREN to children,
                )
            }
    }
}
