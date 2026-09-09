package app.sterna.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import app.sterna.core.data.settings.ThemeMode

/**
 * The OLED decision, kept out of the composable so it can be executed in a test.
 */
internal fun applyPureBlack(
    scheme: ColorScheme,
    darkTheme: Boolean,
    pureBlack: Boolean,
): ColorScheme = if (darkTheme && pureBlack) scheme.pulledToBlack() else scheme

/**
 * Material 3 theme.
 */
@Composable
fun SternaTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    pureBlack: Boolean = false,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> PelagicColorScheme
        else -> ArcticColorScheme
    }.let { applyPureBlack(it, darkTheme, pureBlack) }

    // Match the system bar icons to the app theme (it drives light/dark via Compose,
    // not the system, so the edge-to-edge bars must be told explicitly). In light
    // theme the status-bar icons go dark so they stay legible on the light surface.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = SternaTypography,
        content = content,
    )
}
