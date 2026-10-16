// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.emoji

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.diegonmarcos.superapp.uikit.KitImeComposeView
import com.diegonmarcos.superapp.uikit.KitPalette
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.stateColor
import helium314.keyboard.latin.utils.toKitPalette

/**
 * The emoji category tabs, in the strip's place while the emoji pages are up: one icon per
 * category, the selected one in the accent colour. The pages call [setTabs] once the categories
 * are known and [selectedCategoryId] as the category changes.
 */
class EmojiCategoryTabs @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : KitImeComposeView(context, attrs) {

    class Tab(val categoryId: Int, val iconRes: Int, val description: String)

    private class TabItem(val tab: Tab, val icon: Drawable?)

    private var items by mutableStateOf<List<TabItem>>(emptyList())
    private var onTabClick: ((Int) -> Unit)? = null

    var selectedCategoryId by mutableIntStateOf(-1)

    init {
        // the strip style's background drawable belongs to the View strip; the tabs paint their own
        background = null
    }

    fun setTabs(tabs: List<Tab>, onClick: (Int) -> Unit) {
        items = tabs.map { TabItem(it, ContextCompat.getDrawable(context, it.iconRes)?.mutate()) }
        onTabClick = onClick
    }

    override fun palette(): KitPalette = Settings.getValues().mColors.toKitPalette()

    @Composable
    override fun Body() {
        val colors = Settings.getValues().mColors
        val background = Color(remember(colors) { colors.stateColor(ColorType.STRIP_BACKGROUND) })
        val pressed = Color(remember(colors) { colors.stateColor(ColorType.STRIP_BACKGROUND, pressed = true) })
        val density = LocalDensity.current
        Row(Modifier.fillMaxSize().background(background)) {
            items.forEach { item ->
                var isPressed by remember { mutableStateOf(false) }
                val selected = item.tab.categoryId == selectedCategoryId
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .background(if (isPressed) pressed else Color.Transparent, RoundedCornerShape(8.dp))
                        .semantics { contentDescription = item.tab.description }
                        .pointerInput(item) {
                            detectTapGestures(
                                onPress = {
                                    isPressed = true
                                    try { tryAwaitRelease() } finally { isPressed = false }
                                },
                                onTap = { onTabClick?.invoke(item.tab.categoryId) },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    val icon = item.icon
                    if (icon != null) {
                        val w = icon.intrinsicWidth
                        val h = icon.intrinsicHeight
                        Canvas(Modifier.width(with(density) { w.toDp() }).height(with(density) { h.toDp() })) {
                            drawIntoCanvas {
                                colors.setColor(icon, if (selected) ColorType.EMOJI_CATEGORY_SELECTED else ColorType.EMOJI_CATEGORY)
                                icon.setBounds(0, 0, w, h)
                                icon.draw(it.nativeCanvas)
                            }
                        }
                    }
                }
            }
        }
    }
}
