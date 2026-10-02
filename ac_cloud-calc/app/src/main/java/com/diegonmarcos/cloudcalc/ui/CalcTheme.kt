package com.diegonmarcos.cloudcalc.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.diegonmarcos.cloudcalc.R

/**
 * Dark by default (#336), from res/values/colors.xml — the fleet palette (the cloud-c3 values),
 * one declaration the View theme and this Compose scheme both read. No Kotlin file names a
 * colour literal.
 */
@Composable
fun CalcTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    fun col(id: Int) = Color(ContextCompat.getColor(ctx, id))
    val scheme = darkColorScheme(
        primary = col(R.color.calc_accent),
        onPrimary = col(R.color.calc_on_accent),
        secondary = col(R.color.calc_accent_on_card),
        onSecondary = col(R.color.calc_on_accent),
        background = col(R.color.calc_background),
        onBackground = col(R.color.calc_text),
        surface = col(R.color.calc_surface),
        onSurface = col(R.color.calc_text),
        surfaceVariant = col(R.color.calc_surface_raised),
        onSurfaceVariant = col(R.color.calc_text_secondary),
        outline = col(R.color.calc_outline),
        error = col(R.color.calc_error),
    )
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalContentColor provides scheme.onBackground, content = content)
    }
}

/** The app's metrics, declared once: no screen holds a dp literal. */
object CalcMetrics {
    val hairline: Dp = 1.dp
    val small: Dp = 4.dp
    val gap: Dp = 8.dp
    val gutter: Dp = 12.dp
    val keyHeight: Dp = 52.dp
    val corner: Dp = 12.dp
    val plotHeight: Dp = 280.dp
    val spectrumHeight: Dp = 180.dp
    val stroke: Dp = 2.dp
}
