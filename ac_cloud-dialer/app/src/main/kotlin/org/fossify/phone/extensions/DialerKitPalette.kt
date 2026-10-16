package org.fossify.phone.extensions

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.diegonmarcos.superapp.uikit.KitPalette
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor

/**
 * The palette Cloud Dialer's own Compose screens paint with (libs:ui-kit's CloudKitTheme): the roles are
 * derived from the Fossify theme the user picked, so a theme switch recolours them like the upstream Views.
 * Surfaces are opaque (text over background) so dialogs on top of them stay readable.
 */
fun Context.dialerKitPalette(): KitPalette {
    val bg = Color(getProperBackgroundColor())
    val text = Color(getProperTextColor())
    val primary = Color(getProperPrimaryColor())
    return KitPalette(
        surface = text.copy(alpha = 0.08f).compositeOver(bg),
        surfaceSelected = text.copy(alpha = 0.16f).compositeOver(bg),
        textPrimary = text,
        textSecondary = text.copy(alpha = 0.6f),
        accent = primary,
        hairline = text.copy(alpha = 0.2f),
        tileInk = bg,
    )
}
