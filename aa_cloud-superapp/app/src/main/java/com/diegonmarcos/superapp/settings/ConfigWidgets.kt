package com.diegonmarcos.superapp.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/*
 * The form pieces the Configs pages share (DNS, Permissions, One-hand, ...): a heading, a caption, a monospace read-out, a
 * full-width button, a radio row and a check row. Same proportions as the hand-built TextViews they replace (a headline with
 * 16/4 dp padding, a caption at 70% alpha, a 33%-black read-out, a button with 6 dp above and below), under the dense
 * theme (no 24 sp lines, 36 dp touch floor), painted from the palette roles.
 */

/** Section heading: the old `TextAppearance_Material_Headline` TextView with 16 dp above and 4 below. */
@Composable
internal fun ConfigHeader(text: String, modifier: Modifier = Modifier) {
    Text(text, color = LocalKitPalette.current.textPrimary, style = MaterialTheme.typography.headlineSmall,
        modifier = modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp).testTag("cfg:header:$text"))
}

@Composable
internal fun ConfigCaption(text: String, modifier: Modifier = Modifier) {
    Text(text, color = LocalKitPalette.current.textPrimary, style = MaterialTheme.typography.labelSmall,
        modifier = modifier.fillMaxWidth().alpha(0.7f).padding(bottom = 8.dp))
}

/** A monospace, selectable read-out on a faint dark plate. [tint] colours the plate (red for "not in effect"). */
@Composable
internal fun ConfigReadout(text: String, modifier: Modifier = Modifier, ink: Color? = null, plate: Color = Color(0x33000000)) {
    SelectionContainer {
        Text(text, color = ink ?: LocalKitPalette.current.textPrimary, fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = modifier.fillMaxWidth().background(plate).padding(horizontal = 8.dp, vertical = 10.dp))
    }
}

/** The page's full-width action: accent-filled, 6 dp above and below. [plate] overrides the fill (the red consent button). */
@Composable
internal fun ConfigButton(
    label: String, modifier: Modifier = Modifier, enabled: Boolean = true, plate: Color? = null, onClick: () -> Unit,
) {
    val p = LocalKitPalette.current
    Text(label, color = p.textPrimary, textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp)
            .background((plate ?: p.accent.copy(alpha = 0.85f)).copy(alpha = if (enabled) 1f else 0.4f))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("cfg:button:$label"))
}

/** A radio choice: the whole row selects. [label] may carry a second line ("label\nsubtitle"). */
@Composable
internal fun ConfigRadioRow(label: String, selected: Boolean, enabled: Boolean = true, tag: String = label, onSelect: () -> Unit) {
    val p = LocalKitPalette.current
    Row(Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
        .padding(vertical = 4.dp).testTag("cfg:radio:$tag"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label, color = p.textPrimary.copy(alpha = if (enabled) 1f else 0.5f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun ConfigCheckRow(label: String, checked: Boolean, enabled: Boolean = true, tag: String = label, onChange: (Boolean) -> Unit) {
    val p = LocalKitPalette.current
    Row(Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
        .padding(vertical = 4.dp).testTag("cfg:check:$tag"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(label, color = p.textPrimary.copy(alpha = if (enabled) 1f else 0.5f), style = MaterialTheme.typography.bodyMedium)
    }
}

/** A column with the page gutter the hand-built Configs pages used (18 dp). */
@Composable
internal fun ConfigColumn(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(18.dp), content = content)
}
