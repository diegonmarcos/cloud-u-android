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
import androidx.compose.ui.unit.sp
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
    /**
     * #621 THE DENSITY DECLARATION — the whole app's one scale. "this app UI should fully
     * scale down we are dense data here! this fucking huge buttons!!!": a file/repo manager
     * shows rows of facts, and the old numbers were a phone launcher's (56dp rows, 14dp card
     * padding, 48dp Material touch boxes, the default type scale). Every dp in this app is a
     * member BELOW — no screen holds a dp literal of its own (test/test-drive-density.sh fails
     * the build on one), so re-tuning the density is an edit HERE and all five tabs, the Git
     * rows of #608, the Volumes sections of #613 and every dialog follow together.
     *
     * The step set is deliberately six spacings, not thirty: hairline/tight/gap/gapWide/pad/padWide.
     * Anything that used to be 10/14/16/20/24/32dp of padding collapses onto pad or padWide —
     * that collapse IS the scale-down. [tap] is the floor for a real touch target (36dp, not the
     * Material 48) and [tapSmall] the in-row secondary control; nothing goes below that.
     * [textScale] shrinks the type scale once, in DriveTheme, for the same reason.
     */
    val none = 0.dp
    val hairline = 1.dp
    val tight = 2.dp
    val gap = 4.dp
    val gapWide = 6.dp
    val pad = 8.dp
    val padWide = 12.dp
    val cardRadius = padWide
    val gutter = pad
    val cardPadding = pad
    val sectionInset = gutter + tight
    val islandHeight = 44.dp
    val rowHeight = 40.dp
    val tap = 36.dp
    val tapSmall = 24.dp
    val glyph = 28.dp
    val glyphIcon = 20.dp
    val iconLarge = 32.dp
    val icon = 16.dp
    val iconSmall = 12.dp
    val treeIndent = padWide
    val listBottom = 16.dp
    val tabLabel = 84.dp
    val appTile = 72.dp
    val appGlyph = 40.dp
    val dialogList = 220.dp
    val wide = 600.dp
    val textScale = 0.85f
    /** The two glyph sizes drawn as TEXT (the Apps tile emoji and its status dot) — declared here for the same reason. */
    val tileGlyphText = 20.sp
    val statusGlyphText = 10.sp
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
    /** #608 the Sync ▸ Git page: a dense repository row, its collapsed operations rail, the login control. */
    const val SYNC_GIT_SECTION = "sync_git_section"
    const val SYNC_GIT_ROW = "sync_git_row"
    const val SYNC_GIT_OPS = "sync_git_ops"
    const val SYNC_GIT_LOGIN = "sync_git_login"
    /** #642 the one line that reports what the cloud-terminal git handoff resolved to. */
    const val SYNC_GIT_HANDOFF = "sync_git_handoff"
    /** #629 the line that says where the git credential came from: the vault, or nowhere yet. */
    const val SYNC_GIT_VAULT_NOTE = "sync_git_vault_note"
    /**
     * #646 the declared git-auth chain's own account of itself: the order it will
     * try (before anything runs) and afterwards which provider answered and why the
     * ones before it were skipped. A chain that degrades silently is the #639/#452
     * shape; this tag is where it has to say so out loud.
     *
     * #653 "the short code the GitHub rung asks for" used to be listed here too. It
     * is gone with the device grant: no rung asks the owner to type anything.
     */
    const val SYNC_GIT_CHAIN = "sync_git_chain"

    /**
     * #653 the fleet's own browser login, on the Git page — the ONE sign-in way this
     * surface offers, scoped to the declared `session_provider` of the fleet rung
     * (authelia_web). Tagged separately from the chain so a test can assert WHICH
     * provider is offered here, not merely that something is.
     */
    const val SYNC_GIT_FLEET_LOGIN = "sync_git_fleet_login"
    const val SYNC_HISTORY = "sync_history"
    const val SYNC_REMOTE_CARD = "sync_remote_card"
    const val SYNC_JOB_ROW = "sync_job_row"
    const val SYNC_MOUNT_CARD = "sync_mount_card"
    const val SYNC_CONNECTION_ROW = "sync_connection_row"

    const val CONFIGS_STRIP = "configs_strip"
    const val VOLUMES_CARD = "volumes_card"
    const val VOLUMES_STRIP = "volumes_strip"
    /** #630 one declared section of the Volumes tab: its own header, its own strip, its own rows. */
    const val VOLUMES_SECTION = "volumes_section"
    const val VOLUMES_ENTRY_ROW = "volumes_entry_row"
    /** #630 the line a Fleet-Volumes row prints instead of offering an Open that cannot work. */
    const val VOLUMES_OWNER_ONLY = "volumes_owner_only"
    const val RSYNC_RULE_CARD = "rsync_rule_card"
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
            .padding(horizontal = DriveMetrics.gutter, vertical = DriveMetrics.gapWide),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = DriveMetrics.islandHeight)
                .testTag(DriveTags.ISLAND)
                .clip(bottomNavPillShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = DriveMetrics.pad),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) leading()
            Column(Modifier.weight(1f).padding(horizontal = DriveMetrics.pad)) {
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
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(DriveMetrics.tap)) { Icon(icon, contentDescription = description, tint = if (enabled) tint else tint.copy(alpha = 0.4f), modifier = Modifier.size(DriveMetrics.icon)) }
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
        .padding(horizontal = DriveMetrics.gutter, vertical = DriveMetrics.gapWide)
        .testTag(tag)
        .clip(shape)
        .background(MaterialTheme.colorScheme.surface)
    if (hero) m = m.border(DriveMetrics.hairline, MaterialTheme.colorScheme.primary, shape) else m = m.border(DriveMetrics.hairline, MaterialTheme.colorScheme.outline, shape)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Column(m.padding(DriveMetrics.cardPadding)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(header, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (badge != null) CapsuleBadge(badge)
            if (light != null) { Spacer(Modifier.width(DriveMetrics.pad)); StatusLightRow(light, header) }
        }
        if (!summary.isNullOrBlank()) {
            Text(
                summary, Modifier.padding(top = DriveMetrics.gap), style = MaterialTheme.typography.bodySmall,
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
            .padding(start = DriveMetrics.gapWide)
            .clip(bottomNavPillShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = DriveMetrics.pad, vertical = DriveMetrics.tight),
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
            .padding(horizontal = DriveMetrics.padWide, vertical = DriveMetrics.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(DriveMetrics.icon)); Spacer(Modifier.width(DriveMetrics.gap)) }
        Text(text, color = ink, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** A row of pills that wraps onto the next line when the card is narrow. */
@Composable
fun PillRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth().padding(top = DriveMetrics.pad), horizontalArrangement = Arrangement.spacedBy(DriveMetrics.pad), verticalAlignment = Alignment.CenterVertically, content = content)
}

@Composable
fun SectionHeader(title: String, count: Int? = null, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = DriveMetrics.sectionInset, vertical = DriveMetrics.gap), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (count != null) CapsuleBadge(count.toString())
        Spacer(Modifier.weight(1f))
        if (action != null && onAction != null) Pill(action, onAction)
    }
}

/** A card with a linear indicator (determinate when a fraction is known) and a monospace detail line. */
@Composable
fun ProgressCard(title: String, detail: String, fraction: Float? = null, onCancel: (() -> Unit)? = null, cancelLabel: String = stringResource(R.string.chrome_cancel)) {
    val shape = RoundedCornerShape(DriveMetrics.cardRadius)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = DriveMetrics.gutter, vertical = DriveMetrics.gapWide).testTag(DriveTags.PROGRESS)
            .clip(shape).background(MaterialTheme.colorScheme.surfaceVariant).padding(DriveMetrics.cardPadding),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (onCancel != null) Pill(cancelLabel, onCancel)
        }
        if (fraction != null) LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(vertical = DriveMetrics.gapWide))
        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = DriveMetrics.gapWide))
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
    Column(modifier.fillMaxWidth().testTag(tag).padding(horizontal = DriveMetrics.padWide, vertical = DriveMetrics.gap)) {
        LinearProgressIndicator(progress = { used }, modifier = Modifier.fillMaxWidth().height(DriveMetrics.gapWide).clip(bottomNavPillShape))
        Text(label, Modifier.padding(top = DriveMetrics.tight), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, hint: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(DriveMetrics.padWide + DriveMetrics.pad).testTag(DriveTags.EMPTY), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(DriveMetrics.iconLarge))
        Spacer(Modifier.height(DriveMetrics.padWide))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (hint.isNotBlank()) Text(hint, Modifier.padding(top = DriveMetrics.gap), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (actionLabel != null && onAction != null) { Spacer(Modifier.height(DriveMetrics.padWide)); Pill(actionLabel, onAction, filled = true) }
    }
}

@Composable
fun ErrorState(message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(DriveMetrics.padWide).testTag(DriveTags.ERROR), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, color = colorResource(R.color.status_light_off), style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (onRetry != null) { Spacer(Modifier.height(DriveMetrics.padWide)); Pill(stringResource(R.string.chrome_retry), onRetry) }
    }
}

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(DriveMetrics.padWide).testTag(DriveTags.LOADING), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

/** A hairline between rows. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(DriveMetrics.hairline).background(MaterialTheme.colorScheme.outline))
}
