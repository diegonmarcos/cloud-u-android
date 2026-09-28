package com.diegonmarcos.clouddrive.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.isSpecified
import com.diegonmarcos.clouddrive.R

/**
 * #579 the Compose half of "dark is the default" (#336/#337; the View half is
 * Theme.CloudDrive). Every colour is a resource of res/values/colors.xml, read here
 * ONCE into the Material scheme, so a palette change is a resource edit and no
 * Kotlin file holds a colour literal (test-drive-shell.sh).
 *
 * The roles the fleet island (libs:bottomnav) reads are set on purpose: the
 * selected pill is `inverseSurface` with `inverseOnSurface` ink — here the accent
 * and the page's ink — and the unselected items are `onSurfaceVariant`.
 *
 * #603 THE DARK-THEME BLACK-FONT FIX, at the declaration. `MaterialTheme` provides the
 * colour SCHEME but NOT `LocalContentColor`, whose material3 default is `Color.Black`;
 * only a `Surface` (or a Card, which is one) provides it. This chrome's cards are
 * `Modifier.background(...)` on a Column, not Surfaces — chosen for the hairline border
 * and the hero ring — so every `Text()` that did not name a colour inherited that BLACK
 * default and printed black ink on a #07040F page: the DriveCard header, the EmptyState
 * and ErrorState titles, the ProgressCard title, the file rows. Providing it once here
 * fixes all of them and every future one, which is why the fix is not per widget: a
 * widget-by-widget `color =` would have to be remembered on every new Text ever added.
 * The View half of the same promise is Theme.CloudDrive's android:textColorPrimary.
 *
 * #621 THE TEXT half of the density declaration. The Material type scale is sized for
 * launcher-class chrome; this app is rows of facts. Rather than a `fontSize =` on every
 * Text — the per-widget fix that has to be remembered forever — the WHOLE scale is
 * multiplied ONCE by DriveMetrics.textScale, so `MaterialTheme.typography.*` is already
 * dense everywhere and re-tuning the app's type is one number in DriveMetrics.
 */
/** One style, scaled: the size and its line height together, so leading never outgrows the glyph. */
private fun TextStyle.dense(scale: Float = DriveMetrics.textScale) = copy(
    fontSize = if (fontSize.isSpecified) fontSize * scale else fontSize,
    lineHeight = if (lineHeight.isSpecified) lineHeight * scale else lineHeight,
)

/** The whole Material scale at DriveMetrics.textScale — the app's ONE type declaration. */
private fun denseTypography(): Typography = Typography().let { t ->
    Typography(
        displayLarge = t.displayLarge.dense(), displayMedium = t.displayMedium.dense(), displaySmall = t.displaySmall.dense(),
        headlineLarge = t.headlineLarge.dense(), headlineMedium = t.headlineMedium.dense(), headlineSmall = t.headlineSmall.dense(),
        titleLarge = t.titleLarge.dense(), titleMedium = t.titleMedium.dense(), titleSmall = t.titleSmall.dense(),
        bodyLarge = t.bodyLarge.dense(), bodyMedium = t.bodyMedium.dense(), bodySmall = t.bodySmall.dense(),
        labelLarge = t.labelLarge.dense(), labelMedium = t.labelMedium.dense(), labelSmall = t.labelSmall.dense(),
    )
}

@Composable
fun DriveTheme(content: @Composable () -> Unit) {
    val background = colorResource(R.color.drive_background)
    val surface = colorResource(R.color.drive_surface)
    val raised = colorResource(R.color.drive_surface_raised)
    val accent = colorResource(R.color.drive_accent)
    val accentOnCard = colorResource(R.color.drive_accent_on_card)
    val onAccent = colorResource(R.color.drive_on_accent)
    val text = colorResource(R.color.drive_text)
    val textSecondary = colorResource(R.color.drive_text_secondary)
    val outline = colorResource(R.color.drive_outline)
    val off = colorResource(R.color.status_light_off)
    val scheme = darkColorScheme(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = raised,
        onPrimaryContainer = accentOnCard,
        secondary = accentOnCard,
        onSecondary = onAccent,
        secondaryContainer = raised,
        onSecondaryContainer = text,
        tertiary = accentOnCard,
        background = background,
        onBackground = text,
        surface = surface,
        onSurface = text,
        surfaceVariant = raised,
        onSurfaceVariant = textSecondary,
        surfaceContainer = surface,
        surfaceContainerHigh = raised,
        surfaceContainerHighest = raised,
        surfaceContainerLow = surface,
        surfaceContainerLowest = background,
        inverseSurface = accent,
        inverseOnSurface = onAccent,
        outline = outline,
        outlineVariant = outline,
        error = off,
        onError = onAccent,
        errorContainer = raised,
        onErrorContainer = off,
    )
    MaterialTheme(colorScheme = scheme, typography = denseTypography()) {
        CompositionLocalProvider(LocalContentColor provides text, content = content)
    }
}
