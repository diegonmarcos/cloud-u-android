package com.diegonmarcos.cloudbrowser.search

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.SolidColor
import com.diegonmarcos.superapp.ai.AiColors
import com.diegonmarcos.superapp.ai.AiTheme
import com.diegonmarcos.superapp.uikit.KitPalette

/** libs:ai's theme in the browser's kit palette; icons are libs:ai's glyphs (the browser ships no icon set of cloud-search's). */
@Composable
fun kitAiTheme(p: KitPalette): AiTheme = AiTheme(
    colors = AiColors(
        text = p.textPrimary, text2 = p.textSecondary, accent = p.accent, negative = MaterialTheme.colorScheme.error,
        field = p.surfaceSelected, tile = p.surfaceSelected, tileBorder = p.hairline, chatBar = p.surfaceSelected,
        glassBorder = p.hairline, userBubble = p.textPrimary, onUserBubble = p.tileInk, onAi = p.surface,
        ai = SolidColor(p.accent),
    ),
)
