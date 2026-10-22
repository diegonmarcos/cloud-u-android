// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.panel

import android.graphics.drawable.Drawable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas

/**
 * What the clipboard panel and the Autocomplete panel (#1103) share besides [PanelLayoutParams], and nothing else: the key-shaped background a card
 * or a row is drawn on. The two panels keep their own content, state and engine.
 */
internal fun Modifier.drawableBackground(drawable: Drawable?, pressed: Boolean): Modifier =
    if (drawable == null) this else drawBehind {
        drawable.state = if (pressed) intArrayOf(android.R.attr.state_pressed, android.R.attr.state_enabled)
            else intArrayOf(android.R.attr.state_enabled)
        drawable.setBounds(0, 0, size.width.toInt(), size.height.toInt())
        drawIntoCanvas { drawable.draw(it.nativeCanvas) }
    }
