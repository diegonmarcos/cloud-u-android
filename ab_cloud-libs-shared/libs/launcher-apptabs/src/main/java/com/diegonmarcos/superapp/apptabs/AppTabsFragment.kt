package com.diegonmarcos.superapp.apptabs

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * App Tabs — 2-column GRID of the last [AppTabPrefs.cap] entries the
 * user has opened. Mirrors libs:browser's GRID mode but renders
 * SuperApp sections / pages / external apps instead of URL tabs.
 *
 * Tap a card → host activity handles re-navigation via the
 * [AppTabsHost] callback (so this module doesn't reach back into
 * app/'s MainActivity by name). MainActivity implements AppTabsHost
 * and routes the tap to goSection / openSectionPage / startActivity
 * as appropriate.
 *
 * No drill / no DETAIL mode — App Tabs is purely an LRU launcher
 * shelf; tapping a card switches; long-press could drop in a future
 * patch for explicit eviction.
 *
 * 2026-10-16 owner decision: all apps must be Compose. The shelf is a ComposeView under
 * CloudKitTheme (libs:ui-kit) with its palette in the apptabs_* colour resources; the host
 * contract (newInstance, AppTabsHost) is unchanged.
 */
class AppTabsFragment : Fragment() {

    private lateinit var prefs: AppTabPrefs

    /** The LRU shelf as last read; [onResume] re-reads it. */
    private var entries by mutableStateOf<List<AppTabPrefs.Entry>>(emptyList())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        prefs = AppTabPrefs(ctx)
        entries = prefs.all()
        return ComposeView(ctx).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val palette = KitPalette(
                    surface = colorResource(R.color.apptabs_card),
                    surfaceSelected = colorResource(R.color.apptabs_card),
                    textPrimary = colorResource(R.color.apptabs_label),
                    textSecondary = colorResource(R.color.apptabs_text_dim),
                    accent = colorResource(R.color.apptabs_title),
                    hairline = colorResource(R.color.apptabs_stroke),
                    tileInk = colorResource(R.color.apptabs_bg),
                )
                CloudKitTheme(palette) { Shelf() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-render on resume — the LRU contents typically just got
        // updated by whatever navigation brought the user back here.
        entries = prefs.all()
    }

    @Composable
    private fun Shelf() {
        val p = LocalKitPalette.current
        Column(Modifier.fillMaxSize().background(colorResource(R.color.apptabs_bg)).padding(12.dp)) {
            Text("Tabs", color = p.accent, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp, top = 4.dp, end = 8.dp, bottom = 12.dp))
            if (entries.isEmpty()) {
                Text("No recent entries yet. Open a SuperApp section, a page, or an external app from the Phone tab — the last ${AppTabPrefs.cap} land here.",
                    color = p.textSecondary.copy(alpha = 0.7f), fontSize = 16.sp,
                    modifier = Modifier.padding(20.dp))
            } else {
                LazyVerticalGrid(GridCells.Fixed(2), Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(entries, key = { it.key }) { EntryCard(it) }
                }
            }
        }
    }

    @Composable
    private fun EntryCard(entry: AppTabPrefs.Entry) {
        val ctx = LocalContext.current
        val p = LocalKitPalette.current
        // Resolve icon. SuperApp-side iconName is a drawable resource
        // name owned by app/'s R; external apps use PackageManager.
        val icon = remember(entry.key) { resolveIcon(ctx, entry) }
        Column(
            Modifier.fillMaxWidth().height(160.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(p.surface)
                .border(1.dp, p.hairline, RoundedCornerShape(14.dp))
                .clickable { (requireActivity() as? AppTabsHost)?.onAppTabPicked(entry) }
                .padding(12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(40.dp))
            else androidx.compose.foundation.layout.Spacer(Modifier.size(40.dp))
            Text(displayLabel(entry), color = p.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp))
            Text(displaySubtitle(entry), color = p.textSecondary.copy(alpha = 0.7f), fontSize = 12.sp,
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    private fun resolveIcon(ctx: Context, entry: AppTabPrefs.Entry): androidx.compose.ui.graphics.ImageBitmap? {
        val drawable = when (entry) {
            is AppTabPrefs.Entry.ExternalAppEntry ->
                runCatching { ctx.packageManager.getApplicationIcon(entry.packageName) }.getOrNull()
            else -> {
                val iconName = when (entry) {
                    is AppTabPrefs.Entry.SectionEntry -> entry.iconName
                    is AppTabPrefs.Entry.PageEntry    -> entry.iconName
                    is AppTabPrefs.Entry.TargetEntry  -> entry.iconName
                    else                              -> ""
                }
                val id = ctx.resources.getIdentifier(iconName, "drawable", ctx.packageName)
                if (id != 0) ContextCompat.getDrawable(ctx, id) else null
            }
        }
        return runCatching { drawable?.toBitmap(96, 96)?.asImageBitmap() }.getOrNull()
    }

    private fun displayLabel(e: AppTabPrefs.Entry): String = when (e) {
        is AppTabPrefs.Entry.SectionEntry     -> e.label.ifBlank { e.sectionId }
        is AppTabPrefs.Entry.PageEntry        -> e.label.ifBlank { "${e.sectionId}/${e.pageId}" }
        is AppTabPrefs.Entry.ExternalAppEntry -> e.label.ifBlank { e.packageName }
        is AppTabPrefs.Entry.TargetEntry      -> e.label.ifBlank { e.target }
    }

    private fun displaySubtitle(e: AppTabPrefs.Entry): String = when (e) {
        is AppTabPrefs.Entry.SectionEntry     -> "section"
        is AppTabPrefs.Entry.PageEntry        -> "page · ${e.sectionId}"
        is AppTabPrefs.Entry.ExternalAppEntry -> "app · ${e.packageName.take(28)}"
        is AppTabPrefs.Entry.TargetEntry      -> "page"
    }

    companion object {
        fun newInstance(): AppTabsFragment = AppTabsFragment()
    }
}

/** Activity callback contract — implemented by MainActivity so the
 *  fragment can hand a recorded entry back for re-navigation without
 *  reaching back into app/'s class names. */
interface AppTabsHost {
    fun onAppTabPicked(entry: AppTabPrefs.Entry)
}
