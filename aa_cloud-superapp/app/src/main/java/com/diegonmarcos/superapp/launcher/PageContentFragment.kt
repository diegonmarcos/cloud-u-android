package com.diegonmarcos.superapp.launcher
import com.diegonmarcos.superapp.R

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.os.bundleOf
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlin.math.abs

/**
 * Universal page-content view. Replaces the legacy "Placeholder for libs:X"
 * stub with an actually useful surface backed by build.json sample data.
 *
 *  • Reads samples for the key "<section>/<page>" via [Sections.pageSamples]
 *  • Renders a header (title + subtitle) and a vertical list of cards
 *  • Empty state if no samples are declared yet
 *
 * Each row uses an accent stripe, picked per row from the palette's four state/accent roles
 * ([stripeSlot]) so a theme recolours it — keeps the visual language consistent across the app.
 * Compose since #773 (was fragment_page_content.xml + item_page_row.xml).
 */
class PageContentFragment : KitComposeFragment() {

    override fun palette() = LauncherPalette.kit(requireContext())

    @Composable
    override fun Content() {
        val args = requireArguments()
        val sectionId = args.getString(ARG_SECTION_ID).orEmpty()
        val pageId    = args.getString(ARG_PAGE_ID).orEmpty()
        val title     = args.getString(ARG_TITLE).orEmpty()
        val subtitle  = args.getString(ARG_SUBTITLE).orEmpty()
        val ctx = LocalContext.current
        val p = LocalKitPalette.current

        // Header icon: prefer the page's icon, fall back to the section's.
        val section = Sections.byId(sectionId)
        val pageIconName = section?.pages?.firstOrNull { it.id == pageId }?.iconName
        val iconName = pageIconName ?: section?.iconName ?: "ic_settings"
        val iconRes = Sections.iconResFor(ctx, iconName).takeIf { it != 0 } ?: R.drawable.ic_settings
        val key = "$sectionId/$pageId"
        val samples = Sections.pageSamples(key)

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(p.surfaceSelected).testTag(TAG_ICON),
                    contentAlignment = Alignment.Center) {
                    Icon(painterResource(iconRes), contentDescription = null,
                        tint = stripeColor(p, stripeSlot(key, -1)), modifier = Modifier.size(24.dp))
                }
                Column(Modifier.padding(start = 14.dp)) {
                    Text(title, color = p.textPrimary, style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.testTag(TAG_TITLE))
                    Text(subtitle.ifBlank { stringResource(R.string.page_default_subtitle, sectionId, pageId) },
                        color = p.textSecondary, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 2.dp).testTag(TAG_SUBTITLE))
                }
            }
            if (samples.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(top = 24.dp).clip(RoundedCornerShape(20.dp))
                    .background(p.surface).padding(20.dp).testTag(TAG_EMPTY),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.page_empty_title), color = p.textPrimary,
                        style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.page_empty_hint, key), color = p.textSecondary,
                        textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 6.dp).alpha(0.9f))
                }
            } else {
                Column(Modifier.padding(top = 14.dp)) {
                    samples.forEachIndexed { idx, sample ->
                        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(14.dp))
                            .background(p.surface).clickable { }
                            .padding(horizontal = 14.dp, vertical = 12.dp).testTag(rowTag(idx)),
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(4.dp).height(36.dp).background(stripeColor(p, stripeSlot(key, idx))))
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(sample.title, color = p.textPrimary, style = MaterialTheme.typography.titleSmall)
                                Text(sample.subtitle, color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val ARG_SECTION_ID = "section_id"
        private const val ARG_PAGE_ID    = "page_id"
        private const val ARG_TITLE      = "title"
        private const val ARG_SUBTITLE   = "subtitle"

        const val TAG_TITLE = "pagecontent:title"
        const val TAG_SUBTITLE = "pagecontent:subtitle"
        const val TAG_ICON = "pagecontent:icon"
        const val TAG_EMPTY = "pagecontent:empty"
        fun rowTag(i: Int) = "pagecontent:row:$i"

        /** Which of the four stripe roles a row (or, at -1, the header icon) takes: stable per page+row. */
        internal fun stripeSlot(key: String, idx: Int): Int = abs((if (idx < 0) key else key + idx).hashCode()) % 4

        /** accent, ok, warn, bad — the palette's own hues, so a theme switch recolours the stripes. */
        internal fun stripeColor(p: KitPalette, slot: Int): Color = when (slot) {
            0 -> p.accent
            1 -> p.ok
            2 -> p.warn
            else -> p.bad
        }

        fun newInstance(
            sectionId: String,
            pageId: String,
            title: String,
            subtitle: String = "",
        ) = PageContentFragment().apply {
            arguments = bundleOf(
                ARG_SECTION_ID to sectionId,
                ARG_PAGE_ID    to pageId,
                ARG_TITLE      to title,
                ARG_SUBTITLE   to subtitle,
            )
        }
    }
}
