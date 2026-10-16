package com.diegonmarcos.ide

import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * Shared UI kit for the hub — one place for the visual language so every screen
 * looks like ONE app (dark surface, purple accent #7C3AED, rounded cards,
 * ripple feedback, monospace data values).
 *
 * 2026-10-16 owner decision: all apps must be Compose. Every screen is a ComposeView under
 * libs:ui-kit's CloudKitTheme, with the palette in the ide_* colour resources; the cards and
 * headers below are the hub's composables.
 */
object Ui {

    /** Apply the shared dark surface to a screen's window bars. */
    fun screen(a: Activity) {
        val bg = a.getColor(R.color.ide_bg)
        a.window.statusBarColor = bg
        a.window.navigationBarColor = bg
    }

    /** [content] on the hub's dark surface, as the view an Activity sets. */
    fun composeView(a: Activity, content: @Composable () -> Unit): ComposeView {
        screen(a)
        return ComposeView(a).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { Theme(content) }
        }
    }

    /** The hub's CloudKitTheme: the palette is the ide_* resources. */
    @Composable
    fun Theme(content: @Composable () -> Unit) {
        val palette = KitPalette(
            surface = colorResource(R.color.ide_card),
            surfaceSelected = colorResource(R.color.ide_accent),
            textPrimary = colorResource(R.color.ide_text),
            textSecondary = colorResource(R.color.ide_text_dim),
            accent = colorResource(R.color.ide_accent),
            hairline = colorResource(R.color.ide_card_stroke),
            tileInk = colorResource(R.color.ide_ink),
            ok = colorResource(R.color.ide_ok),
            warn = colorResource(R.color.ide_warn),
            bad = colorResource(R.color.ide_bad),
        )
        CloudKitTheme(palette) {
            Box(Modifier.fillMaxSize().background(colorResource(R.color.ide_bg)).systemBarsPadding()) { content() }
        }
    }

    /** Big app card: glyph bubble + title + status line. Optional long-press
     *  (e.g. replace a foreign phone copy with our bundled child APK). */
    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    fun AppCard(
        glyph: String, titleText: String, statusText: String,
        enabled: Boolean = true, onLongPress: (() -> Unit)? = null, onTap: () -> Unit,
    ) {
        val p = LocalKitPalette.current
        val shape = RoundedCornerShape(18.dp)
        Row(
            Modifier.fillMaxWidth().padding(bottom = 14.dp)
                .alpha(if (enabled) 1f else 0.55f)
                .shadow(4.dp, shape)
                .clip(shape).background(p.surface).border(BorderStroke(1.dp, p.hairline), shape)
                .combinedClickable(onClick = onTap, onLongClick = onLongPress)
                .padding(horizontal = 18.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Glyph bubble.
            Box(
                Modifier.padding(end = 16.dp).size(52.dp).clip(RoundedCornerShape(14.dp)).background(p.accent),
                contentAlignment = Alignment.Center,
            ) {
                Text(glyph, color = p.textPrimary, fontSize = 22.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
            // Title + status.
            Column(Modifier.weight(1f)) {
                Text(titleText, color = p.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(statusText, color = p.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
            }
            // Chevron.
            Text("›", color = colorResource(R.color.ide_accent_soft), fontSize = 22.sp)
        }
    }

    /** Page header: big title over a small monospace sub line ([subColor] overrides the dim default). */
    @Composable
    fun Header(titleText: String, subText: String, subColor: Color? = null) {
        val p = LocalKitPalette.current
        Column(Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp, end = 4.dp, bottom = 22.dp)) {
            Text(titleText, color = p.textPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(subText, color = subColor ?: p.textSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** The activity's whole screen: [content] under the hub's CloudKitTheme (what androidx.activity.compose.setContent is elsewhere). */
fun Activity.setContent(content: @Composable () -> Unit) = setContentView(Ui.composeView(this, content))
