package app.cloudnotes.pro.theme

import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.ColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

object CloudNotesTheme {
    val colors: CloudNotesColorScheme
        @ReadOnlyComposable
        @Composable
        get() = LocalCloudNotesColors.current

    val typography: CloudNotesTypography
        @ReadOnlyComposable
        @Composable
        get() = LocalCloudNotesTypography.current
}

/** #1369 the scheme for a resolved theme: Obsidian's light or dark default theme (ObsidianTokens, generated). */
fun cloudNotesScheme(dark: Boolean): CloudNotesColorScheme = if (dark) cloudNotesDarkScheme else cloudNotesLightScheme

/**
 * #1369 Material colours of the native shell (drawer, dialogs, AI panel) from Obsidian's tokens: the accent is
 * --interactive-accent, surfaces are --background-primary / --background-secondary, ink is --text-normal / --text-muted.
 */
fun obsidianMaterialColors(dark: Boolean): ColorScheme {
    val c = cloudNotesScheme(dark)
    val accent = if (dark) ObsidianTokens.Dark.interactiveAccent else ObsidianTokens.Light.interactiveAccent
    val onAccent = if (dark) ObsidianTokens.Dark.textOnAccent else ObsidianTokens.Light.textOnAccent
    val activeHover = if (dark) ObsidianTokens.Dark.backgroundModifierActiveHover else ObsidianTokens.Light.backgroundModifierActiveHover
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent, onPrimary = onAccent, secondaryContainer = activeHover, onSecondaryContainer = c.textPrimary,
        background = c.backgroundPrimary, onBackground = c.textPrimary,
        surface = c.backgroundSecondary, onSurface = c.textPrimary, surfaceVariant = c.backgroundTertiary, onSurfaceVariant = c.textSecondary,
        surfaceContainer = c.backgroundSecondary, surfaceContainerHigh = c.backgroundPrimary, surfaceContainerLow = c.backgroundSecondary,
        outline = c.divider, outlineVariant = c.divider, error = if (dark) ObsidianTokens.Dark.textError else ObsidianTokens.Light.textError,
    )
}

@Composable
fun CloudNotesTheme(
    mode: ThemeMode = ThemeMode.System,
    content: @Composable () -> Unit
) {
    val colors = when (mode) {
        ThemeMode.Light -> cloudNotesScheme(false)
        ThemeMode.Dark -> cloudNotesScheme(true)
        ThemeMode.System -> cloudNotesScheme(isSystemInDarkTheme())
    }

    CompositionLocalProvider(LocalCloudNotesColors provides colors) {
        MaterialTheme(colorScheme = obsidianMaterialColors(colors === cloudNotesDarkScheme)) {
            content()
        }
    }
}

enum class ThemeMode(name: String) {
    Light("light"),
    Dark("dark"),
    System("system");

    fun of(name: String) = when (name) {
        "light" -> Light
        "dark" -> Dark
        else -> System
    }
}