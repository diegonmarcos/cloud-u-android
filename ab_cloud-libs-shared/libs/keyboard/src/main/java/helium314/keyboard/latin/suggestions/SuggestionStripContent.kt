// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.suggestions

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput

/**
 * What a strip element does with a finger: a tap, a long press, and a pressed look in the theme's
 * strip colour while the finger is down (the feedback the strip's View elements got from their
 * selector backgrounds). A drag that scrolls the row cancels the press, as it did for a View.
 *
 * [onTap] gets the position of the tap inside the element. [key] says when the element is another
 * one — the handlers are re-installed then, so they never act on a word that is no longer there.
 */
@Composable
internal fun Modifier.stripPress(
    pressedColor: Color,
    shape: Shape,
    key: Any?,
    onTap: (Offset) -> Unit,
    onLongPress: (() -> Unit)? = null,
): Modifier {
    var pressed by remember { mutableStateOf(false) }
    return this
        .background(if (pressed) pressedColor else Color.Transparent, shape)
        .pointerInput(key) {
            detectTapGestures(
                onPress = {
                    pressed = true
                    try {
                        tryAwaitRelease()
                    } finally {
                        pressed = false
                    }
                },
                onLongPress = onLongPress?.let { handler -> { _: Offset -> handler() } },
                onTap = onTap,
            )
        }
}
