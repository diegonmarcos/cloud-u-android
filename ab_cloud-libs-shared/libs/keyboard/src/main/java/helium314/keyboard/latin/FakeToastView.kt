// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import android.util.AttributeSet
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.diegonmarcos.superapp.uikit.KitImeComposeView
import com.diegonmarcos.superapp.uikit.KitPalette
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.toKitPalette

/**
 * The toast-like message KeyboardSwitcher shows over the keys on API 33+, where a real Toast needs
 * the notification permission: the app icon and the text on the toast background, in Compose.
 * KeyboardSwitcher still owns show / fade / hide; it only sets the text here.
 */
class FakeToastView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    KitImeComposeView(context, attrs) {

    private var message by mutableStateOf("")

    fun setText(text: String) { message = text }

    override fun palette(): KitPalette = Settings.getValues().mColors.toKitPalette()

    @Composable
    override fun Body() {
        val ctx = LocalContext.current
        val iconSize = with(LocalDensity.current) { 18.sp.toDp() }
        val icon = remember { ContextCompat.getDrawable(ctx, R.mipmap.ic_launcher_round)?.toBitmap(96, 96) }
        Row(
            Modifier.background(colorResource(R.color.toast_background), RoundedCornerShape(24.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon?.let {
                Image(it.asImageBitmap(), null, Modifier.size(iconSize))
                Spacer(Modifier.width(12.dp))
            }
            Text(message, color = Color.White, fontSize = 18.sp)
        }
    }
}
