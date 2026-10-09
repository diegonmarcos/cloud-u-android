package com.diegonmarcos.superapp.uikit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The colour ROLES a fleet page paints with — the same seven the superapp's LauncherPalette
 * resolves from build.json::ui.launcher_themes. An app hands its resolved palette in; the kit
 * names no colour of its own, so a theme switch recolours a Compose page exactly as it
 * recolours a View page.
 */
@Immutable
data class KitPalette(
    val surface: Color,
    val surfaceSelected: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color,
    val hairline: Color,
    /** The ink that sits ON a lit (textPrimary-filled) tile. */
    val tileInk: Color,
    /**
     * The three STATE tokens (cloud-account-ui spec 0.3): a state pill, a banner and a stat tile
     * colour their dot by these and by nothing else. Each palette builder declares its own; the
     * defaults here only keep a builder that predates them (the wasm Store page, Writer) compiling.
     */
    val ok: Color = DEFAULT_OK,
    val warn: Color = DEFAULT_WARN,
    val bad: Color = DEFAULT_BAD,
) {
    companion object {
        val DEFAULT_OK: Color = Color(0xFF7FC98F)
        val DEFAULT_WARN: Color = Color(0xFFE2B85C)
        val DEFAULT_BAD: Color = Color(0xFFE5737A)

        /** From resolved ARGB ints, which is what a View-era palette already holds. */
        fun fromArgb(surface: Int, surfaceSelected: Int, textPrimary: Int, textSecondary: Int,
                     accent: Int, hairline: Int, tileInk: Int,
                     ok: Int = DEFAULT_OK.toArgb(), warn: Int = DEFAULT_WARN.toArgb(),
                     bad: Int = DEFAULT_BAD.toArgb()): KitPalette = KitPalette(
            Color(surface), Color(surfaceSelected), Color(textPrimary), Color(textSecondary),
            Color(accent), Color(hairline), Color(tileInk), Color(ok), Color(warn), Color(bad),
        )
    }
}

/** No default on purpose: a kit composable outside CloudKitTheme is a bug, not a grey page. */
val LocalKitPalette = staticCompositionLocalOf<KitPalette> {
    error("libs:ui-kit composable used outside CloudKitTheme — wrap the screen in CloudKitTheme(palette)")
}

/** The theme every kit screen sits in: Material3 roles mapped from [palette], plus the palette itself. */
@Composable
fun CloudKitTheme(palette: KitPalette, content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = palette.accent,
        onPrimary = palette.tileInk,
        surface = palette.surface,
        onSurface = palette.textPrimary,
        onSurfaceVariant = palette.textSecondary,
        outline = palette.hairline,
        background = Color.Transparent,
        onBackground = palette.textPrimary,
    )
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(
            LocalKitPalette provides palette,
            LocalContentColor provides palette.textPrimary,
            content = content,
        )
    }
}

/** Test tags of the kit's own parts, for a consumer's compose tests. */
object KitTags {
    fun tile(id: String): String = "kit:tile:$id"
    fun row(id: String): String = "kit:row:$id"
    fun header(id: String): String = "kit:header:$id"
    const val DIALOG_CONFIRM: String = "kit:dialog:confirm"
    const val DIALOG_DISMISS: String = "kit:dialog:dismiss"
}

/**
 * Section title + caption, the heading over every group of a settings page. [eyebrow] draws the
 * small upper-case label the Account pages put over a group (THIS PHONE, ABOUT) instead.
 */
@Composable
fun KitSectionHeader(title: String, subtitle: String, modifier: Modifier = Modifier, eyebrow: Boolean = false) {
    val p = LocalKitPalette.current
    if (eyebrow) {
        Text(title.uppercase(), color = p.textSecondary, fontSize = KitDensity.caption,
            letterSpacing = KitDensity.eyebrowTracking,
            modifier = modifier.fillMaxWidth().padding(top = KitDensity.medium, bottom = KitDensity.small))
        return
    }
    Column(modifier.fillMaxWidth()) {
        Text(title, color = p.textPrimary, style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 8.dp))
        if (subtitle.isNotBlank()) {
            Text(subtitle, color = p.textSecondary, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 16.dp))
        }
    }
}

/**
 * A one-tap pick: label, optional subtitle, filled with surfaceSelected when [selected].
 * Announced as a radio button, so TalkBack reads the choice and its state, not a bare label.
 */
@Composable
fun KitSelectableTile(
    label: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalKitPalette.current
    Column(
        modifier
            .fillMaxWidth()
            .background(if (selected) p.surfaceSelected else p.surface)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(14.dp),
    ) {
        Text((if (selected) "● " else "○ ") + label, color = p.textPrimary,
            style = MaterialTheme.typography.bodyLarge)
        if (subtitle.isNotBlank()) {
            Text(subtitle, color = p.textSecondary, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** A page with nothing to show yet: an optional title over a caption, centred in the pane. */
@Composable
fun KitEmptyState(title: String?, caption: String, modifier: Modifier = Modifier) {
    val p = LocalKitPalette.current
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!title.isNullOrBlank()) {
            Text(title, color = p.textPrimary, style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center)
        }
        Text(caption, color = p.textSecondary, style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = if (title.isNullOrBlank()) 0.dp else 8.dp))
    }
}

/** A grouped block on the palette's surface. */
@Composable
fun KitCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalKitPalette.current
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.surface).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** A settings line: title, optional subtitle, optional trailing slot; tappable when [onClick] is set. */
@Composable
fun KitSettingsRow(
    title: String,
    subtitle: String = "",
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val p = LocalKitPalette.current
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = p.textPrimary, style = MaterialTheme.typography.bodyLarge)
            if (subtitle.isNotBlank()) {
                Text(subtitle, color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
        trailing?.invoke()
    }
}

/** A settings line that IS a switch: the whole row toggles, and it is announced as one switch. */
@Composable
fun KitSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    KitSettingsRow(
        title, subtitle,
        modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
    ) {
        // onCheckedChange = null: the row owns the toggle, so the switch is not a second target.
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A yes/no question. Dismissing (back, outside tap, the dismiss button) never confirms. */
@Composable
fun KitConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag(KitTags.DIALOG_CONFIRM)) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(KitTags.DIALOG_DISMISS)) { Text(dismissLabel) }
        },
    )
}
