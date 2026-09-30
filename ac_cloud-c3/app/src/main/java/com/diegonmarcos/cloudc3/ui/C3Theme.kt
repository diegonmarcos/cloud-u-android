package com.diegonmarcos.cloudc3.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.diegonmarcos.cloudc3.R

/**
 * #648 the Compose half of "dark is the default" (#336/#337). Forced dark and
 * deliberately NOT driven by the system dark-mode flag: themes.xml declares the View half
 * as Theme.Material3.Dark, so following the system here would leave the two halves
 * disagreeing in light mode.
 *
 * Every colour comes from res/values/colors.xml through [ContextCompat], so this file
 * names no literal and the palette has ONE declaration that the View theme and the
 * Compose scheme both read. test/test-c3-shell.sh fails the build on a colour literal
 * in any Kotlin file.
 */
@Composable
fun C3Theme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    fun col(id: Int) = Color(ContextCompat.getColor(ctx, id))

    val scheme = darkColorScheme(
        primary = col(R.color.c3_accent),
        onPrimary = col(R.color.c3_on_accent),
        secondary = col(R.color.c3_accent_on_card),
        onSecondary = col(R.color.c3_on_accent),
        background = col(R.color.c3_background),
        onBackground = col(R.color.c3_text),
        surface = col(R.color.c3_surface),
        onSurface = col(R.color.c3_text),
        surfaceVariant = col(R.color.c3_surface_raised),
        onSurfaceVariant = col(R.color.c3_text_secondary),
        outline = col(R.color.c3_outline),
    )

    MaterialTheme(colorScheme = scheme, typography = C3Typography) {
        // The ink default for anything that does not set its own: without this a
        // Text() inside a plain Box inherits a framework default, which on this page
        // is the black-font-on-dark defect.
        CompositionLocalProvider(LocalContentColor provides scheme.onBackground, content = content)
    }
}

/**
 * The type scale, declared ONCE. This is an ops surface: rows are dense and numerous,
 * so the scale is a step below the platform default rather than each screen picking its
 * own sp. No screen holds an sp literal.
 */
internal val C3Typography = Typography().let { base ->
    base.copy(
        titleMedium = base.titleMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
        bodyMedium = base.bodyMedium.copy(fontSize = 13.sp),
        bodySmall = base.bodySmall.copy(fontSize = 11.sp),
        labelSmall = base.labelSmall.copy(fontSize = 10.sp),
    )
}

/**
 * The metrics of the chrome, declared once beside the type scale for the same reason: no
 * screen holds a dp literal, so scaling this ops surface denser is ONE edit here and every
 * tab follows (the ac_cloud-drive DriveMetrics precedent, #621).
 */
internal object C3Metrics {
    /** The spacing steps, monotone so the scale reads as one decision. */
    val hairline: Dp = 1.dp
    val tight: Dp = 2.dp
    val small: Dp = 4.dp
    val inner: Dp = 6.dp
    val gap: Dp = 8.dp
    val gutter: Dp = 12.dp

    val rowHeight: Dp = 44.dp
    val cardPadding: Dp = 12.dp
    val corner: Dp = 12.dp
    val glyphSmall: Dp = 14.dp
    val iconSize: Dp = 20.dp
    val tileSize: Dp = 72.dp
}
