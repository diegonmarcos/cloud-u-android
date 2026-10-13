package app.sterna.ui.theme

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import app.sterna.core.data.settings.AppStyle

/**
 * The app style in force, provided by [SternaTheme]: a screen that draws something style-specific
 * (the reader's E-ink reading face, a thicker divider) reads it here, never the setting.
 */
val LocalAppStyle = staticCompositionLocalOf { AppStyle.CLOUD_DARK }

/** Whether [style] draws dark: Gmail Dark always, Cloud E-ink never, Cloud Dark per the theme mode. */
internal fun appStyleIsDark(style: AppStyle, cloudDark: Boolean): Boolean = when (style) {
    AppStyle.CLOUD_DARK -> cloudDark
    AppStyle.GMAIL_DARK -> true
    AppStyle.CLOUD_EINK -> false
}

/** Whether [style] keeps the motion layer (ripples, overscroll stretch). E-paper does not: every frame is a refresh. */
internal fun appStyleAnimates(style: AppStyle): Boolean = style != AppStyle.CLOUD_EINK

/**
 * Gmail's dark design, our own values: the near-black #131314 ground, #1E1F20 raised surfaces, the
 * light-blue #A8C7FA accent and the compose FAB's deep-blue #004A77 container with #C2E7FF ink,
 * #E3E3E3 primary text and #C4C7C5 secondary text, #444746 outlines.
 */
val GmailDarkColorScheme = darkColorScheme(
    primary = Color(0xFFA8C7FA),
    onPrimary = Color(0xFF062E6F),
    primaryContainer = Color(0xFF004A77),
    onPrimaryContainer = Color(0xFFC2E7FF),
    inversePrimary = Color(0xFF0B57D0),
    secondary = Color(0xFFC2E7FF),
    onSecondary = Color(0xFF00344F),
    secondaryContainer = Color(0xFF004A77),
    onSecondaryContainer = Color(0xFFC2E7FF),
    tertiary = Color(0xFF6DD58C),
    onTertiary = Color(0xFF00391A),
    tertiaryContainer = Color(0xFF0F5223),
    onTertiaryContainer = Color(0xFFC4EED0),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    background = Color(0xFF131314),
    onBackground = Color(0xFFE3E3E3),
    surface = Color(0xFF131314),
    onSurface = Color(0xFFE3E3E3),
    surfaceVariant = Color(0xFF444746),
    onSurfaceVariant = Color(0xFFC4C7C5),
    surfaceTint = Color(0xFFA8C7FA),
    inverseSurface = Color(0xFFE3E3E3),
    inverseOnSurface = Color(0xFF303030),
    outline = Color(0xFF8E918F),
    outlineVariant = Color(0xFF444746),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF37393B),
    surfaceDim = Color(0xFF131314),
    surfaceContainerLowest = Color(0xFF0E0E0E),
    surfaceContainerLow = Color(0xFF1B1B1B),
    surfaceContainer = Color(0xFF1E1F20),
    surfaceContainerHigh = Color(0xFF282A2C),
    surfaceContainerHighest = Color(0xFF333537),
)

/** E-paper: pure black on pure white, every role. No accent colour exists, so nothing can be coloured. */
val EinkColorScheme = lightColorScheme(
    primary = Color.Black,
    onPrimary = Color.White,
    primaryContainer = Color.White,
    onPrimaryContainer = Color.Black,
    inversePrimary = Color.White,
    secondary = Color.Black,
    onSecondary = Color.White,
    secondaryContainer = Color.White,
    onSecondaryContainer = Color.Black,
    tertiary = Color.Black,
    onTertiary = Color.White,
    tertiaryContainer = Color.White,
    onTertiaryContainer = Color.Black,
    error = Color.Black,
    onError = Color.White,
    errorContainer = Color.White,
    onErrorContainer = Color.Black,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = Color.White,
    onSurfaceVariant = Color.Black,
    surfaceTint = Color.White,
    inverseSurface = Color.Black,
    inverseOnSurface = Color.White,
    outline = Color.Black,
    outlineVariant = Color.Black,
    scrim = Color.Black,
    surfaceBright = Color.White,
    surfaceDim = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color.White,
)

/** Gmail-dark's conversation list: flat rows on the page itself, no card ground. */
internal val GmailDarkMailListPalette = MailListPalette(
    pane = Color(0xFF131314),
    card = Color(0xFF131314),
    unreadText = Color(0xFFE3E3E3),
    readText = Color(0xFFC4C7C5),
)

/** E-paper list: white on white, black ink; unread is told by WEIGHT only (see [mailListTextInk]). */
internal val EinkMailListPalette = MailListPalette(
    pane = Color.White,
    card = Color.White,
    unreadText = Color.Black,
    readText = Color.Black,
)

/** The serif reading face of E-ink on the body styles; the rest of the scale is untouched. */
internal fun einkTypography(base: Typography): Typography {
    fun TextStyle.serif() = copy(fontFamily = FontFamily.Serif)
    return base.copy(
        bodyLarge = base.bodyLarge.serif(),
        bodyMedium = base.bodyMedium.serif(),
        bodySmall = base.bodySmall.serif(),
        titleLarge = base.titleLarge.serif(),
        titleMedium = base.titleMedium.serif(),
    )
}

/**
 * The style layer laid INSIDE the Material theme: [LocalAppStyle] for every screen, and for E-ink the
 * serif body face, no ripples and no overscroll stretch.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun ProvideAppStyle(style: AppStyle, content: @Composable () -> Unit) {
    if (appStyleAnimates(style)) {
        CompositionLocalProvider(LocalAppStyle provides style, content = content)
        return
    }
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme,
        typography = einkTypography(MaterialTheme.typography),
    ) {
        CompositionLocalProvider(
            LocalAppStyle provides style,
            LocalRippleConfiguration provides null,
            LocalOverscrollConfiguration provides null,
            content = content,
        )
    }
}
