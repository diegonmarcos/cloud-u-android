package com.diegonmarcos.cloudsearch.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.diegonmarcos.cloudsearch.R
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitPalette

/**
 * Dark by default (#336), light on the toggle; both palettes are res/values/colors.xml, read here
 * into the Material scheme AND handed to the fleet kit (libs:ui-kit) as its palette, so kit cards
 * and Material controls recolour together. No Kotlin file names a colour literal.
 */
@Composable
fun SearchTheme(dark: Boolean, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    fun col(d: Int, l: Int) = Color(ContextCompat.getColor(ctx, if (dark) d else l))
    val background = col(R.color.search_background, R.color.search_light_background)
    val surface = col(R.color.search_surface, R.color.search_light_surface)
    val raised = col(R.color.search_surface_raised, R.color.search_light_surface_raised)
    val outline = col(R.color.search_outline, R.color.search_light_outline)
    val accent = col(R.color.search_accent, R.color.search_light_accent)
    val onAccent = col(R.color.search_on_accent, R.color.search_light_on_accent)
    val text = col(R.color.search_text, R.color.search_light_text)
    val text2 = col(R.color.search_text_secondary, R.color.search_light_text_secondary)
    val error = col(R.color.search_error, R.color.search_light_error)
    val scheme = if (dark) darkColorScheme(
        primary = accent, onPrimary = onAccent, secondary = accent, onSecondary = onAccent,
        background = background, onBackground = text, surface = surface, onSurface = text,
        surfaceVariant = raised, onSurfaceVariant = text2, outline = outline, error = error,
        surfaceContainer = surface, surfaceContainerHigh = raised, surfaceContainerLow = surface,
    ) else lightColorScheme(
        primary = accent, onPrimary = onAccent, secondary = accent, onSecondary = onAccent,
        background = background, onBackground = text, surface = surface, onSurface = text,
        surfaceVariant = raised, onSurfaceVariant = text2, outline = outline, error = error,
        surfaceContainer = surface, surfaceContainerHigh = raised, surfaceContainerLow = surface,
    )
    val palette = KitPalette(surface, raised, text, text2, accent, outline, onAccent)
    CloudKitTheme(palette) {
        MaterialTheme(colorScheme = scheme) {
            CompositionLocalProvider(LocalContentColor provides text, content = content)
        }
    }
}

/** The app's metrics, declared once: no screen holds a dp literal. */
object Metrics {
    val small: Dp = 4.dp
    val gap: Dp = 8.dp
    val gutter: Dp = 16.dp
    val corner: Dp = 20.dp
    val thumb: Dp = 56.dp
    val bar: Dp = 18.dp
    val chartHeight: Dp = 140.dp
    val islandHeight: Dp = 40.dp
}
