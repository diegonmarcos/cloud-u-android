// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.diegonmarcos.superapp.uikit.KitImeComposeView
import com.diegonmarcos.superapp.uikit.KitPalette
import helium314.keyboard.keyboard.KeyboardTypeface
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.toKitPalette

/**
 * The "paste what you just copied" chip the suggestion strip shows (an external suggestion view): a
 * paste glyph and the clip's text (or a thumbnail of its image) in the strip's pill, and a close
 * glyph. Compose under CloudKitTheme; ClipboardHistoryManager builds it, hands it to the strip as a
 * plain View, and hides it again after a paste or a dismissal.
 */
class ClipboardSuggestionChip(
    context: Context,
    private val text: CharSequence?,
    private val image: Bitmap?,
    private val pasteIcon: Drawable?,
    private val closeIcon: Drawable?,
    private val onPaste: (View) -> Unit,
    private val onClose: () -> Unit,
) : KitImeComposeView(context) {

    init {
        // what the inflated layout used to ask for: as wide as its content, as tall as the strip
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun palette(): KitPalette = Settings.getValues().mColors.toKitPalette()

    @Composable
    override fun Body() {
        val colors = Settings.getValues().mColors
        val textColor = Color(colors.get(ColorType.KEY_TEXT))
        val fontFamily = KeyboardTypeface.customTypeface()?.let { FontFamily(it) }
        val paste = remember { pasteIcon?.toBitmap()?.asImageBitmap() }
        val close = remember { closeIcon?.toBitmap()?.asImageBitmap() }
        val thumbnail = remember { image?.asImageBitmap() }
        val pill = RoundedCornerShape(32.dp)
        Row(
            Modifier.fillMaxHeight().padding(vertical = 5.dp)
                .clip(pill).background(Color(colors.get(ColorType.CLIPBOARD_SUGGESTION_BACKGROUND)), pill)
                .padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.clickable { onPaste(this@ClipboardSuggestionChip) }.padding(start = 12.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                paste?.let { Image(it, null, Modifier.padding(end = 3.dp)) }
                Text(text?.toString().orEmpty(), color = textColor, fontFamily = fontFamily, fontSize = 14.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            thumbnail?.let {
                Image(it, null, Modifier.clickable { onPaste(this@ClipboardSuggestionChip) }.padding(vertical = 6.dp)
                    .padding(end = 6.dp), contentScale = ContentScale.Inside)
            }
            val label = stringResource(R.string.spoken_clipboard_suggestion)
            close?.let {
                Image(it, null, Modifier.semantics { contentDescription = label }.clickable { onClose() })
            }
        }
    }
}
