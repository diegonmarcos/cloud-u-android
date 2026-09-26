package com.diegonmarcos.clouddrive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.superapp.bottomnav.bottomNavPillShape

/**
 * #579 THE component inventory of the chrome (cloud-drive-redesign.md §1). Every
 * screen is composed of these and nothing else; a screen that needs a new
 * primitive adds it HERE so the whole app keeps one visual family. Colours come
 * from the theme (DriveTheme ← colors.xml); radii and spacing are the constants
 * below, once.
 */
object DriveMetrics {
    val cardRadius = 16.dp
    val gutter = 12.dp
    val cardPadding = 14.dp
    val islandHeight = 56.dp
    val rowHeight = 56.dp
    val glyph = 40.dp
    val treeIndent = 16.dp
}

/** The test tags every layout-tree test (DriveShellTest, test-drive-*.sh) reads. One place, no drift. */
object DriveTags {
    const val SHELL = "drive_shell"
    const val CONTENT = "drive_content"
    const val ISLAND = "drive_toolbar_island"
    const val STATUS_LIGHT = "drive_status_light"
    const val CARD = "drive_card"
    const val PROGRESS = "drive_progress_card"
    const val CONFIGS_SIGN_IN_REPORT = "drive_configs_sign_in_report"
    const val EMPTY = "drive_empty_state"
    const val ERROR = "drive_error_state"
    const val LOADING = "drive_loading_state"
    fun tab(id: String) = "drive_tab_$id"

    const val FILES_PANE_A = "files_pane_a"
    const val FILES_PANE_B = "files_pane_b"
    const val FILES_PANE_HEADER = "files_pane_header"
    const val FILES_TAB_STRIP = "files_tab_strip"
    const val FILES_BREADCRUMBS = "files_breadcrumbs"
    const val FILES_TOOLBAR = "files_toolbar"
    const val FILES_STORAGE_BAR = "files_storage_bar"
    const val FILES_LIST = "files_list"
    const val FILES_ROW = "files_row"
    const val FILES_SELECTION_BAR = "files_selection_bar"
    const val FILES_PLACES_SHEET = "files_places_sheet"
    const val FILES_SEARCH_BAR = "files_search_bar"

    const val SYNC_STRIP = "sync_strip"
    const val SYNC_HERO = "sync_hero"
    const val SYNC_REPO_CARD = "sync_repo_card"
    const val SYNC_DECLARED_CARD = "sync_declared_card"
    const val SYNC_HISTORY = "sync_history"
    const val SYNC_REMOTE_CARD = "sync_remote_card"
    const val SYNC_JOB_ROW = "sync_job_row"
    const val SYNC_MOUNT_CARD = "sync_mount_card"
    const val SYNC_CONNECTION_ROW = "sync_connection_row"

    const val CONFIGS_STRIP = "configs_strip"
    const val VOLUMES_CARD = "volumes_card"
    const val HOME_CARD = "home_card"
    const val HOME_STORAGE_BAR = "home_storage_bar"
    const val APPS_GRID = "apps_grid"
    const val BACKUPS_MIRROR_CARD = "backups_mirror_card"
    const val CONFIGS_CARD = "configs_card"
}

/** The insets the top island clears: the status bar and the display cutout. */
@Composable
fun topIslandInsets(): WindowInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout)

/**
 * The top pill: raised surface, true semicircle ends (the island pill of libs:bottomnav),
 * title + optional monospace subtitle, optional leading control and trailing actions.
 */
@Composable
fun ToolbarIsland(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Box(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(topIslandInsets())
            .padding(horizontal = DriveMetrics.gutter, vertical = 6.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = DriveMetrics.islandHeight)
                .testTag(DriveTags.ISLAND)
                .clip(bottomNavPillShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) leading()
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            actions()
        }
    }
}

/** An icon action inside an island, a card or a bar. */
@Composable
fun IslandAction(icon: ImageVector, description: String, enabled: Boolean = true, tint: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) { Icon(icon, contentDescription = description, tint = if (enabled) tint else tint.copy(alpha = 0.4f)) }
}

/**
 * THE card. Header (bold), optional badge (declared / private / --delete), optional
 * StatusLight at the header's end, optional one-line summary, then the body.
 */
@Composable
fun DriveCard(
    header: String,
    modifier: Modifier = Modifier,
    badge: String? = null,
    light: StatusLight.State? = null,
    summary: String? = null,
    summaryMonospace: Boolean = false,
    hero: Boolean = false,
    onClick: (() -> Unit)? = null,
    tag: String = DriveTags.CARD,
    body: @Composable ColumnScope.() -> Unit = {},
) {
    val shape = RoundedCornerShape(DriveMetrics.cardRadius)
    var m = modifier
        .fillMaxWidth()
        .padding(horizontal = DriveMetrics.gutter, vertical = 6.dp)
        .testTag(tag)
        .clip(shape)
        .background(MaterialTheme.colorScheme.surface)
    if (hero) m = m.border(1.dp, MaterialTheme.colorScheme.primary, shape) else m = m.border(1.dp, MaterialTheme.colorScheme.outline, shape)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Column(m.padding(DriveMetrics.cardPadding)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(header, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (badge != null) CapsuleBadge(badge)
            if (light != null) { Spacer(Modifier.width(8.dp)); StatusLightRow(light, header) }
        }
        if (!summary.isNullOrBlank()) {
            Text(
                summary, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall,
                fontFamily = if (summaryMonospace) FontFamily.Monospace else FontFamily.Default,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        body()
    }
}

/** A small capsule label: declared · private · --delete · fleet-side. */
@Composable
fun CapsuleBadge(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier
            .padding(start = 6.dp)
            .clip(bottomNavPillShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        maxLines = 1,
    )
}

/** The pill button: accent when [filled], raised surface otherwise. */
@Composable
fun Pill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, filled: Boolean = false, enabled: Boolean = true) {
    val bg = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        filled -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val ink = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
        filled -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier
            .clip(bottomNavPillShape)
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, color = ink, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** A row of pills that wraps onto the next line when the card is narrow. */
@Composable
fun PillRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}

@Composable
fun SectionHeader(title: String, count: Int? = null, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = DriveMetrics.gutter + 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (count != null) CapsuleBadge(count.toString())
        Spacer(Modifier.weight(1f))
        if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
    }
}

/** A card with a linear indicator (determinate when a fraction is known) and a monospace detail line. */
@Composable
fun ProgressCard(title: String, detail: String, fraction: Float? = null, onCancel: (() -> Unit)? = null, cancelLabel: String = stringResource(R.string.chrome_cancel)) {
    val shape = RoundedCornerShape(DriveMetrics.cardRadius)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = DriveMetrics.gutter, vertical = 6.dp).testTag(DriveTags.PROGRESS)
            .clip(shape).background(MaterialTheme.colorScheme.surfaceVariant).padding(DriveMetrics.cardPadding),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (onCancel != null) TextButton(onClick = onCancel) { Text(cancelLabel) }
        }
        if (fraction != null) LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
        Text(detail, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * #603 THE storage bar of the chrome, one declaration: the used fraction as a pill-shaped
 * indicator over "<free> free of <total>". Was private to the Files pane until Home needed
 * the same bar for the same two stores; two bars drawn from one number is how they drift.
 *
 * @param usage available bytes to total bytes, as StatFs reports them.
 */
@Composable
fun StorageBar(usage: Pair<Long, Long>, label: String, modifier: Modifier = Modifier, tag: String = DriveTags.FILES_STORAGE_BAR) {
    val (free, total) = usage
    val used = if (total > 0) ((total - free).toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f
    Column(modifier.fillMaxWidth().testTag(tag).padding(horizontal = 12.dp, vertical = 4.dp)) {
        LinearProgressIndicator(progress = { used }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(bottomNavPillShape))
        Text(label, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, hint: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(32.dp).testTag(DriveTags.EMPTY), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (hint.isNotBlank()) Text(hint, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (actionLabel != null && onAction != null) { Spacer(Modifier.height(16.dp)); Pill(actionLabel, onAction, filled = true) }
    }
}

@Composable
fun ErrorState(message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(24.dp).testTag(DriveTags.ERROR), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, color = colorResource(R.color.status_light_off), style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (onRetry != null) { Spacer(Modifier.height(12.dp)); Pill(stringResource(R.string.chrome_retry), onRetry) }
    }
}

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(24.dp).testTag(DriveTags.LOADING), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

/** A hairline between rows. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline))
}
