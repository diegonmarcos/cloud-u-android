package com.diegonmarcos.cloudtoolssmallllms.ui

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
import com.diegonmarcos.cloudtoolssmallllms.R

/** Dark by default (#336), from res/values/colors.xml: no Kotlin file names a colour literal. */
@Composable
fun ToolsTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    fun col(id: Int) = Color(ContextCompat.getColor(ctx, id))
    val scheme = darkColorScheme(
        primary = col(R.color.tools_accent),
        onPrimary = col(R.color.tools_on_accent),
        secondary = col(R.color.tools_ok),
        background = col(R.color.tools_background),
        onBackground = col(R.color.tools_text),
        surface = col(R.color.tools_surface),
        onSurface = col(R.color.tools_text),
        surfaceVariant = col(R.color.tools_surface_raised),
        onSurfaceVariant = col(R.color.tools_text_secondary),
        primaryContainer = col(R.color.tools_user_bubble),
        outline = col(R.color.tools_outline),
        error = col(R.color.tools_error),
    )
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalContentColor provides scheme.onBackground, content = content)
    }
}

/** The app's metrics, declared once (the fleet's data-dense style): no screen holds a dp literal. */
object ToolsMetrics {
    val hairline: Dp = 1.dp
    val tiny: Dp = 2.dp
    val small: Dp = 4.dp
    val gap: Dp = 8.dp
    val gutter: Dp = 12.dp
    val corner: Dp = 10.dp
    val icon: Dp = 16.dp
    val chipHeight: Dp = 26.dp
    val bubbleMax: Dp = 340.dp
}
