package app.cloudnotes.pro.theme

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

@Composable
fun CloudNotesTheme(
    mode: ThemeMode = ThemeMode.System,
    content: @Composable () -> Unit
) {
    val colors = when (mode) {
        ThemeMode.Light -> cloudNotesLightScheme
        ThemeMode.Dark -> cloudNotesDarkScheme
        ThemeMode.System -> if (isSystemInDarkTheme()) cloudNotesDarkScheme else cloudNotesLightScheme
    }

    CompositionLocalProvider(LocalCloudNotesColors provides colors) {
        MaterialTheme {
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