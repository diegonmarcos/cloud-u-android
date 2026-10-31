package app.cloudnotes.pro.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class CloudNotesColorScheme(
    val textPrimary: Color,
    val textSecondary: Color,
    val textDisable: Color,
    val textEmphasis: Color,
    val backgroundPrimary: Color,
    val backgroundSecondary: Color,
    val backgroundOverlayPanel: Color,
    val backgroundTertiary: Color,
    val backgroundCodeBlock: Color,
    val backgroundModal: Color,
    val backgroundSuccess: Color,
    val backgroundError: Color,
    val backgroundWarning: Color,
    val backgroundProgressing: Color,
    val iconPrimary: Color,
    val iconSecondary: Color,
    val iconTertiary: Color,
    val iconDisable: Color,
    val iconActivated: Color,
    val divider: Color,
)

// #1369 the light and dark schemes are Obsidian's default theme, generated into ObsidianTokens.kt
// (scripts/gen-obsidian-theme.py from data/obsidian-theme.json); the old hand-made palette is gone.

val LocalCloudNotesColors = staticCompositionLocalOf { cloudNotesLightScheme }
