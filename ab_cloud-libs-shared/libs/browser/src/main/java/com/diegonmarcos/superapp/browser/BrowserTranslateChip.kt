package com.diegonmarcos.superapp.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/** What the chip says for [state]; null = nothing to show. Pure so it is tested. */
fun translateChipText(state: PageTranslator.State, engineLabel: String): String? = when (state) {
    is PageTranslator.State.Idle -> null
    is PageTranslator.State.Working -> "Translating… ${state.translated} done · $engineLabel"
    is PageTranslator.State.Done -> "Translated · $engineLabel · tap to show the original"
    is PageTranslator.State.Failed -> "Translation stopped: ${state.reason}" + if (state.translated > 0) " (${state.translated} pieces translated) · tap to restore" else " · tap to dismiss"
}

/** The small chip over the page while a translation runs or is on; tapping it restores the original. */
@Composable
fun BrowserTranslateChip(state: PageTranslator.State, engineLabel: String, onTap: () -> Unit) {
    val text = translateChipText(state, engineLabel) ?: return
    val p = LocalKitPalette.current
    Text(text, color = p.textPrimary, style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(8.dp).background(p.surfaceSelected, RoundedCornerShape(14.dp))
            .clickable(onClick = onTap).padding(horizontal = 12.dp, vertical = 6.dp).testTag("browser:translate:chip"))
}
