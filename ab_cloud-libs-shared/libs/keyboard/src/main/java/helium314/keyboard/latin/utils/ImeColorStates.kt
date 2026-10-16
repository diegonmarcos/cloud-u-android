// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Colors

/**
 * The colour a themed element has in one state, read out of [Colors] itself.
 *
 * [Colors] answers state-dependent colours (a pressed strip element, an activated toolbar key, a
 * pressed popup key) by tinting a drawable with a ColorStateList, which is how the View strip drew
 * them. The Compose strip must not re-derive those rules, so it asks the same tinting for the
 * colour of one state: tint a plain white drawable exactly as the Views were tinted, set the
 * state, and read the pixel. Anything [Colors.setColor] treats with a colour filter instead of a
 * state list (a plain text colour) comes out as that filter's colour, so the answer is right for
 * every [ColorType], not just the stateful ones.
 */
fun Colors.stateColor(type: ColorType, pressed: Boolean = false, activated: Boolean = false): Int {
    val drawable = ColorDrawable(Color.WHITE)
    setColor(drawable, type)
    val state = ArrayList<Int>(2)
    if (pressed) state.add(android.R.attr.state_pressed)
    if (activated) state.add(android.R.attr.state_activated)
    drawable.setState(state.toIntArray())
    val pixel = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    drawable.setBounds(0, 0, 1, 1)
    drawable.draw(Canvas(pixel))
    return pixel.getPixel(0, 0).also { pixel.recycle() }
}
