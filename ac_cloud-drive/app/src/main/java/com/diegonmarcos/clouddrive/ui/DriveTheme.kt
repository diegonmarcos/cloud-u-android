package com.diegonmarcos.clouddrive.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.colorResource
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
 */
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
    MaterialTheme(colorScheme = scheme, content = content)
}
