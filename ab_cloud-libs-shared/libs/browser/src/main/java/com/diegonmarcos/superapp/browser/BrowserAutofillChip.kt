package com.diegonmarcos.superapp.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.autofill.FillTarget
import com.diegonmarcos.superapp.autofill.Snippet
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * Tier 2's one surface (a0_docs/eng-specs/autofill-3-tier.md): a compact chip at the bottom of the
 * page while a classified contact/address field has focus — "Fill address: Spain-A · Home", ▾ to pick
 * another profile / address, × to dismiss — "Insert snippet ▾" on an about / bio / message box (always a
 * pick, never filled on its own) — and, after an address form was submitted, the user-confirmed
 * "Save this address to Cloud Account?" offer. In a private tab the chip names no profile and a tap
 * always opens the list: nothing is filled without that explicit pick.
 */
@Composable
fun BrowserAutofillChip(chip: AutofillChip?, offer: SaveOffer?, onFill: (FillTarget) -> Unit, onSnippet: (Snippet) -> Unit, onDismiss: () -> Unit, onSave: (Boolean) -> Unit) {
    val p = LocalKitPalette.current
    var open by remember(chip) { mutableStateOf(false) }
    Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp).horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        when {
            offer != null -> {
                Text(offer.text, color = p.textPrimary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp))
                Pill("Save", "browser:autofill:save") { onSave(true) }
                Pill("Not now", "browser:autofill:save:no") { onSave(false) }
            }
            chip != null && chip.snippets.isNotEmpty() && open -> {
                chip.snippets.forEach { sn -> Pill(sn.label.ifBlank { sn.text.lineSequence().first().take(20) }, "browser:autofill:snippet:${sn.id}") { open = false; onSnippet(sn) } }
                Pill("×", "browser:autofill:dismiss", onDismiss)
            }
            chip != null && open -> {
                chip.targets.forEachIndexed { i, t -> Pill(t.title, "browser:autofill:pick:$i") { open = false; onFill(t) } }
                Pill("×", "browser:autofill:dismiss", onDismiss)
            }
            chip != null -> {
                // A snippet is never inserted by the first tap: the list opens and the user picks one.
                Pill(chip.text, "browser:autofill:chip") {
                    if (chip.snippets.isNotEmpty() || chip.incognito || chip.targets.size > 1) open = true else onFill(chip.targets.first())
                }
                Pill("×", "browser:autofill:dismiss", onDismiss)
            }
        }
    }
}

@Composable
private fun Pill(text: String, tag: String, onClick: () -> Unit) {
    val p = LocalKitPalette.current
    Text(text, color = p.textPrimary, style = MaterialTheme.typography.labelMedium, maxLines = 1,
        modifier = Modifier.padding(4.dp).background(p.surfaceSelected, RoundedCornerShape(14.dp)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp).testTag(tag))
}
