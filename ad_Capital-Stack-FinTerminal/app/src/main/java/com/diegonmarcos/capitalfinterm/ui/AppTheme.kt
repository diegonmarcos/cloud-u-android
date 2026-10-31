package com.diegonmarcos.capitalfinterm.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.colorResource
import com.diegonmarcos.capitalfinterm.R
import com.diegonmarcos.capitalfinterm.ui.fin.FinTheme
import com.diegonmarcos.capitalfinterm.ui.fin.FinUi
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitPalette

/** Dark is the fleet default (#336): libs:ui-kit's CloudKitTheme over the palette of res/values/colors.xml, the one place it is spelled. */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val palette = KitPalette(
        surface = colorResource(R.color.app_surface),
        surfaceSelected = colorResource(R.color.app_surface_raised),
        textPrimary = colorResource(R.color.app_text),
        textSecondary = colorResource(R.color.app_text_secondary),
        accent = colorResource(R.color.app_accent),
        hairline = colorResource(R.color.app_outline),
        tileInk = colorResource(R.color.app_on_accent),
    )
    val ctx = androidx.compose.ui.platform.LocalContext.current
    FinUi.load(ctx)
    // Reduce motion follows the system's animator scale (0 = animations off): no shimmer, no tape scroll, no flash.
    val still = runCatching { android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    CloudKitTheme(palette) { FinTheme(cvd = FinUi.cvd, reduceMotion = still, content = content) }
}
