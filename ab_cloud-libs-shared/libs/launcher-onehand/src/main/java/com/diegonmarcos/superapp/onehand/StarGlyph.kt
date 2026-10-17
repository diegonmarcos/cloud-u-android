package com.diegonmarcos.superapp.onehand

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitPalette

/*
 * The five home stars' glyph, in Compose. Each star was a TextView in the shell layout that its class poked directly
 * (text, setTextSize, setPadding, setShadowLayer). The host is now a ComposeView with the SAME id, gravity, elevation and
 * visibility in the layout, and the star classes keep doing what they did to it as a View - translationX/Y anchoring,
 * visibility, the twinkle animator, the press-drag-release forwarding - because those are placement and gesture, not
 * drawing. What is drawn is this: one glyph with a glow, sized and padded from state.
 */

/** What a star draws: the glyph, its size, the tap padding around it (dp) and the glow. */
internal class StarGlyphState {
    var text by mutableStateOf("✦")
    var sizeSp by mutableFloatStateOf(13f)
    var padDp by mutableFloatStateOf(0f)
    var glow by mutableIntStateOf(0xFF9CC2FF.toInt())
    var glowRadius by mutableFloatStateOf(7f)
}

/** Install the glyph in [this] star host. Safe to call once per star from `setup()`. */
internal fun ComposeView.bindStar(state: StarGlyphState) {
    setContent {
        // The star is ink on the galaxy, not a themed surface: a fixed light palette, with the kit theme only for density.
        CloudKitTheme(STAR_PALETTE) { StarGlyph(state) }
    }
}

private val STAR_PALETTE = KitPalette.fromArgb(
    surface = 0xFF000000.toInt(), surfaceSelected = 0xFF000000.toInt(), textPrimary = 0xFFF2F7FF.toInt(),
    textSecondary = 0xFFF2F7FF.toInt(), accent = 0xFF9CC2FF.toInt(), hairline = 0x00000000, tileInk = 0xFFF2F7FF.toInt(),
)

@Composable
internal fun StarGlyph(s: StarGlyphState) {
    BasicText(
        s.text,
        modifier = Modifier.testTag("star").padding(s.padDp.dp),
        style = TextStyle(
            color = Color(0xFFF2F7FF), fontSize = s.sizeSp.sp,
            shadow = Shadow(Color(s.glow), Offset.Zero, s.glowRadius),
        ),
    )
}
