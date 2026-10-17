package com.diegonmarcos.superapp.launcher

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.diegonmarcos.superapp.ui.Haptics
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * The popup behind a FOLDER tile on an aggregator page (Cloud ▸ Apps today).
 *
 * A folder is a tile whose [Sections.AggTile.children] is non-empty. Tapping it
 * opens this instead of navigating, and the entries inside dispatch through the
 * caller's [onPick] — the same TileClickListener the row itself uses, so a
 * destination behaves identically whether it sits in the row or in a folder.
 *
 * Two layouts, chosen by [Sections.AggTile.childrenUi]:
 *   - `""`      icon grid, for a handful of entries an icon can distinguish.
 *   - `"table"` one row per entry, for lists where the TEXT is the identity.
 *     Sixteen task models all drawn as the same chat glyph would be sixteen
 *     identical icons, so that folder asks for the table.
 *
 * Compose since #773: one dim rounded card with dismiss-on-scrim (a Compose Dialog) — the same folder
 * look as the Phone ▸ Apps folders ([com.diegonmarcos.superapp.apps.PhoneFolderSheet]).
 */
object TileFolderDialog {
    /** Icons per row in the grid layout — matches the tile row's ~6 across. */
    const val GRID_COLUMNS = 4
    const val TAG_SHEET = "tilefolder:sheet"
    fun childTag(id: String) = "tilefolder:child:$id"
}

@Composable
internal fun TileFolderSheet(folder: Sections.AggTile, onDismiss: () -> Unit, onPick: (Sections.AggTile) -> Unit) {
    val p = LocalKitPalette.current
    val view = LocalView.current
    val ctx = LocalContext.current
    val children = folder.children.filterNot { it.separator }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.padding(20.dp).clip(RoundedCornerShape(28.dp)).background(com.diegonmarcos.superapp.apps.FolderSheetFill).padding(16.dp)
            .testTag(TileFolderDialog.TAG_SHEET)) {
            Text(folder.label, color = p.textPrimary, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                if (folder.childrenUi == "table") {
                    // One row per entry, four columns of information in two lines: the category above, then
                    // the task name with the model right-aligned beside it. Dropping any would leave rows that
                    // cannot be told apart: two share a model, two share a category.
                    for (child in children) {
                        Column(Modifier.fillMaxWidth().clickable { Haptics.tap(view); onDismiss(); onPick(child) }
                            .padding(horizontal = 6.dp, vertical = 8.dp).testTag(TileFolderDialog.childTag(child.id))) {
                            if (child.caption.isNotEmpty()) {
                                Text(child.caption, color = p.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            Text(child.label, color = p.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium)
                            Row(Modifier.fillMaxWidth()) {
                                Text(child.id, color = p.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                if (child.note.isNotEmpty()) {
                                    Text(child.note, color = p.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.End, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                } else {
                    for (line in children.chunked(TileFolderDialog.GRID_COLUMNS)) {
                        Row(Modifier.fillMaxWidth()) {
                            for (child in line) {
                                Column(Modifier.weight(1f).clickable { Haptics.tap(view); onDismiss(); onPick(child) }
                                    .padding(8.dp).testTag(TileFolderDialog.childTag(child.id)),
                                    horizontalAlignment = Alignment.CenterHorizontally) {
                                    val iconRes = Sections.iconResFor(ctx, child.iconName)
                                    if (iconRes != 0) {
                                        Icon(painterResource(iconRes), contentDescription = null, tint = p.textPrimary,
                                            modifier = Modifier.size(36.dp))
                                    }
                                    Text(child.label, color = p.textPrimary.copy(alpha = 0.8f), maxLines = 2,
                                        overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                                }
                            }
                            // The last line keeps its cells the width of the rest.
                            for (k in line.size until TileFolderDialog.GRID_COLUMNS) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}
