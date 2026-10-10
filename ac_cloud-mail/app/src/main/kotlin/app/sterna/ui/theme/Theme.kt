package app.sterna.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import app.sterna.core.data.settings.ThemeMode

/**
 * Whether [SternaTheme] went dark: its ONE light/dark decision (the theme setting, else the
 * system), for the fleet page-tab strip, whose palette is libs:bottomnav's and is picked through
 * the declaration (build.json ui.sections[].background "theme" -> NavDecl.stripSurface). Dark
 * outside a SternaTheme, which is the strip every screen drew before.
 */
internal val LocalSternaDarkTheme = staticCompositionLocalOf { true }

/**
 * The bottom-navigation island's geometry (#465) is now declared ONCE in the shared
 * bottom-navigation module (com.diegonmarcos.superapp.bottomnav.bottomNavIslandShape /
 * bottomNavIslandInset, #493). This app's nav no longer carries its own copy.
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

    // The message list's own palette follows the same theme decision, and the same OLED pull: in a
    // pure-black device the pane comes down toward black with the rest, while the card and the two
    // text inks keep their read/unread difference (the card is already black; the inks must not be).
    val mailListPalette = (if (darkTheme) PelagicMailListPalette else ArcticMailListPalette)
        .let { if (darkTheme && pureBlack) it.pulledToBlack() else it }

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

    CompositionLocalProvider(LocalMailListPalette provides mailListPalette, LocalSternaDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = SternaTypography,
            content = { ProvideDenseTouchTargets(content) },
        )
    }
}
