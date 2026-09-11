package com.diegonmarcos.cloudwriter.ui

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * cloud-writer's Material 3 theme — the design system this application did not have.
 *
 * WHY THIS FILE EXISTS. Every screen here used to be assembled at run time out of LinearLayout and
 * addView, against four hardcoded hex constants copy-pasted into two files, with no theme, no type
 * scale and no layout resource anywhere in the module. That is not a style choice, it is an
 * inheritance: these screens were copied from cloud-keyboard under task 272, and cloud-keyboard is
 * an IME. An IME draws inside a window it does not own and cannot apply an application theme, so it
 * has no layout files and no MaterialTheme by necessity. cloud-writer is a standalone application
 * and never had that constraint — it was only wearing the constraint's clothes.
 *
 * MATCHES ac_cloud-mail, DELIBERATELY, RATHER THAN INVENTING A THIRD FLEET PATTERN. cloud-mail is
 * the fleet's Compose + Material 3 reference and the most heavily developed application here; the
 * structure below — dynamic colour above S, a hand-built scheme below it, the OLED pull kept in a
 * separate pure function, and the system bars told which way the theme went — is its structure, in
 * cloud-writer's own colours. Conventions reused; no file cloned.
 *
 * THE OLED PULL IS NOT A DETAIL HERE. The owner runs a Samsung black Power Saving theme (tasks 186
 * and 229), so "dark" on his phone means true black, and a dark grey surface next to it reads as a
 * lit rectangle rather than as depth. [pulledToBlack] takes background and surface to #000 and pulls
 * the container tiers most of the way there, which keeps the Material elevation ORDER intact — a
 * card still separates from the page — while removing the grey halo. Kept out of the composable so
 * it is an ordinary function that can be reasoned about and called from a test, exactly as
 * cloud-mail keeps `applyPureBlack` out of `SternaTheme`.
 */

/**
 * How far the raised surfaces move towards black when the OLED pull is on.
 *
 * NOT 1.0, and that is the whole point. At 1.0 every container tier collapses onto the background
 * and the interface becomes one flat black sheet in which a card, a text field and the page behind
 * them are indistinguishable — which is a worse screen than the grey one, not a blacker one. At
 * 0.8 the page is true black and the tiers keep about a fifth of their separation, which is enough
 * for an edge to read on an OLED panel and not enough to glow.
 */
private const val PureBlackPull = 0.8f

/**
 * cloud-writer's own accent, carried forward from the palette the hand-drawn screens used
 * (#78c8ff) so the application still looks like itself after the migration. It is used as the
 * seed-equivalent primary on Android 11 and below, where there is no wallpaper palette to read.
 */
private val WriterBlue = Color(0xFF78C8FF)
private val WriterBlueDeep = Color(0xFF0B4A75)
private val WriterInk = Color(0xFF0B0E14)

private val WriterDarkScheme = darkColorScheme(
    primary = WriterBlue,
    onPrimary = Color(0xFF00344F),
    primaryContainer = WriterBlueDeep,
    onPrimaryContainer = Color(0xFFCDE7FF),
    secondary = Color(0xFFB4CAD9),
    onSecondary = Color(0xFF1E333F),
    secondaryContainer = Color(0xFF344956),
    onSecondaryContainer = Color(0xFFD0E6F5),
    background = WriterInk,
    onBackground = Color(0xFFE6EDF3),
    surface = WriterInk,
    onSurface = Color(0xFFE6EDF3),
    onSurfaceVariant = Color(0xFFC8D4E0),
    outline = Color(0xFF8A95A1),
)

private val WriterLightScheme = lightColorScheme(
    primary = Color(0xFF1A6590),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDE7FF),
    onPrimaryContainer = Color(0xFF001E2F),
    secondary = Color(0xFF4D616D),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD0E6F5),
    onSecondaryContainer = Color(0xFF091E28),
)

/**
 * The OLED decision, kept out of the composable so it can be executed and reasoned about on its own.
 *
 * Pulls ONLY when the theme is actually dark: applying it in light mode would produce a black page
 * under black-on-white text, which is the kind of defect a palette swap ships when nobody looks at
 * the other mode.
 */
internal fun applyPureBlack(scheme: ColorScheme, darkTheme: Boolean, pureBlack: Boolean): ColorScheme =
    if (darkTheme && pureBlack) scheme.pulledToBlack() else scheme

internal fun ColorScheme.pulledToBlack(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = lerp(surfaceContainerLowest, Color.Black, PureBlackPull),
    surfaceContainerLow = lerp(surfaceContainerLow, Color.Black, PureBlackPull),
    surfaceContainer = lerp(surfaceContainer, Color.Black, PureBlackPull),
    surfaceContainerHigh = lerp(surfaceContainerHigh, Color.Black, PureBlackPull),
    surfaceContainerHighest = lerp(surfaceContainerHighest, Color.Black, PureBlackPull),
    surfaceBright = lerp(surfaceBright, Color.Black, PureBlackPull),
)

/**
 * @param dynamicColor Material You. ON by default and only consulted on Android 12+, where the
 *   platform derives a full tonal palette from the wallpaper. That is the natural fit for a phone
 *   whose owner themes it heavily, and it is also the honest default: below S the call does not
 *   exist and the hand-built schemes above are used instead.
 * @param pureBlack the OLED pull described above. ON by default because the owner's phone is a
 *   Samsung on a black Power Saving theme; it changes nothing in light mode.
 */
@Composable
fun CloudWriterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    pureBlack: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> WriterDarkScheme
        else -> WriterLightScheme
    }.let { applyPureBlack(it, darkTheme, pureBlack) }

    // The status and navigation bar ICONS. Compose drives light/dark here, not the XML theme, so
    // the platform has to be told explicitly which way it went — otherwise the light theme draws
    // white status icons onto a white surface and the clock disappears.
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
        // The stock Material 3 type scale, unmodified and on purpose. The screens it replaces set
        // 20f / 16f / 15f / 14f / 13f / 12f by hand with no relationship between them; the default
        // scale is a designed set of relationships, and taking it is the enhancement.
        typography = Typography(),
        content = content,
    )
}
