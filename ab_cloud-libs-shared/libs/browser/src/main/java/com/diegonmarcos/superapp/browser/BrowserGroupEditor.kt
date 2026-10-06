package com.diegonmarcos.superapp.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * #886 a group's name and colour, editable: the ✎ on a group header opens this. The name must be
 * non-blank and not already another group's (the rename refuses otherwise, and says so here first).
 */
@Composable
fun BrowserGroupEditor(
    group: String,
    color: Int,
    otherGroups: Set<String>,
    onSave: (name: String, color: Int) -> Unit,
    onUngroup: () -> Unit,
    onClose: () -> Unit,
) {
    val p = LocalKitPalette.current
    var name by remember { mutableStateOf(group) }
    var picked by remember { mutableStateOf(color) }
    val clash = name.trim() in otherGroups
    val ok = name.isNotBlank() && !clash
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(onClick = onClose)) {
        Column(
            Modifier.align(Alignment.Center).fillMaxWidth().padding(16.dp)
                .background(p.surface, androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .clickable(remember { MutableInteractionSource() }, null) {}
                .padding(16.dp).testTag("browser:group-editor"),
        ) {
            Text("Group", color = p.textPrimary, style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().padding(top = 8.dp).testTag("browser:group-editor:name"),
                singleLine = true, label = { Text("Name") }, isError = !ok,
                supportingText = { if (clash) Text("Another group already has this name") })
            Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BrowserTabGroups.PALETTE.forEach { c ->
                    Box(
                        Modifier.size(28.dp).clip(CircleShape).background(Color(c))
                            .border(if (c == picked) 3.dp else 0.dp, p.textPrimary, CircleShape)
                            .clickable { picked = c }.testTag("browser:group-editor:color"),
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onUngroup) { Text("Ungroup") }
                TextButton(onClose) { Text("Cancel") }
                TextButton({ onSave(name.trim(), picked) }, enabled = ok) { Text("Save") }
            }
        }
    }
}
