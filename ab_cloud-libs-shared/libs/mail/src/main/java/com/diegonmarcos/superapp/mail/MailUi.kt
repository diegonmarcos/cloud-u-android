package com.diegonmarcos.superapp.mail

import android.content.Context
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as MR

/**
 * The palette the mail pages paint with (libs:ui-kit's CloudKitTheme): the roles are the host
 * app's Material theme colours, resolved once, so the pages keep following whichever theme the
 * app wears exactly as the inflated Views did through ?attr/colorOnSurface and friends.
 */
fun Context.mailKitPalette(): KitPalette {
    fun c(attr: Int, fallback: Int) = MaterialColors.getColor(this, attr, fallback)
    return KitPalette.fromArgb(
        surface = c(MR.attr.colorSurface, AndroidColor.BLACK),
        surfaceSelected = c(MR.attr.colorSurfaceVariant, AndroidColor.DKGRAY),
        textPrimary = c(MR.attr.colorOnSurface, AndroidColor.WHITE),
        textSecondary = c(MR.attr.colorOnSurfaceVariant, AndroidColor.LTGRAY),
        accent = c(MR.attr.colorPrimary, AndroidColor.WHITE),
        hairline = c(MR.attr.colorOutlineVariant, AndroidColor.GRAY),
        tileInk = c(MR.attr.colorOnPrimary, AndroidColor.BLACK),
    )
}

/** The 1dp rule the pages separate their header from their list with. */
@Composable
internal fun MailDivider(vertical: androidx.compose.ui.unit.Dp = 8.dp) {
    Box(Modifier.fillMaxWidth().padding(vertical = vertical).height(1.dp).background(LocalKitPalette.current.hairline))
}

/** A status line, with the small spinner in front of it while [loading]. */
@Composable
internal fun MailStatus(
    loading: Boolean,
    text: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.labelMedium,
) {
    val p = LocalKitPalette.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(16.dp), color = p.accent, strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = p.textPrimary, style = style)
    }
}

/** Dimmed label text (the old android:alpha rows). */
@Composable
internal fun MailDim(text: String, style: androidx.compose.ui.text.TextStyle, modifier: Modifier = Modifier, alpha: Float = 0.7f, mono: Boolean = false) {
    Text(
        text, color = LocalKitPalette.current.textPrimary, style = style,
        fontFamily = if (mono) androidx.compose.ui.text.font.FontFamily.Monospace else null,
        modifier = modifier.alpha(alpha),
    )
}
