package com.diegonmarcos.cloudkgstore.ui

import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.colorResource
import com.diegonmarcos.cloudkgstore.R

/** Dark is the fleet default (#336): the Material scheme from res/values/colors.xml, the one place the palette is spelled. */
@Composable
fun KgTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = colorResource(R.color.kg_accent),
        onPrimary = colorResource(R.color.kg_on_accent),
        primaryContainer = colorResource(R.color.kg_user_bubble),
        onPrimaryContainer = colorResource(R.color.kg_text),
        background = colorResource(R.color.kg_background),
        onBackground = colorResource(R.color.kg_text),
        surface = colorResource(R.color.kg_surface),
        onSurface = colorResource(R.color.kg_text),
        surfaceVariant = colorResource(R.color.kg_surface_raised),
        onSurfaceVariant = colorResource(R.color.kg_text_secondary),
        outline = colorResource(R.color.kg_outline),
        outlineVariant = colorResource(R.color.kg_outline),
        error = colorResource(R.color.kg_error),
    )
    // Dense, like the fleet's kit: the stock scale keeps its sizes and weights but drops the fixed line height and tracking,
    // and a control's touch floor is 36dp, not Material's 48dp.
    MaterialTheme(colorScheme = scheme, typography = Typography().dense()) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 36.dp, content = content)
    }
}

private fun TextStyle.noFixedLine(): TextStyle =
    copy(lineHeight = TextUnit.Unspecified, letterSpacing = TextUnit.Unspecified, lineHeightStyle = null)

private fun Typography.dense(): Typography = copy(
    displayLarge = displayLarge.noFixedLine(), displayMedium = displayMedium.noFixedLine(), displaySmall = displaySmall.noFixedLine(),
    headlineLarge = headlineLarge.noFixedLine(), headlineMedium = headlineMedium.noFixedLine(), headlineSmall = headlineSmall.noFixedLine(),
    titleLarge = titleLarge.noFixedLine(), titleMedium = titleMedium.noFixedLine(), titleSmall = titleSmall.noFixedLine(),
    bodyLarge = bodyLarge.noFixedLine(), bodyMedium = bodyMedium.noFixedLine(), bodySmall = bodySmall.noFixedLine(),
    labelLarge = labelLarge.noFixedLine(), labelMedium = labelMedium.noFixedLine(), labelSmall = labelSmall.noFixedLine(),
)
