package com.diegonmarcos.superapp.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * #900 the fill bar above the keyboard: one chip per suggestion (a saved sign-in, a card, a profile, a value typed on
 * this site), each with where it comes from (local / vault / this site), a note when Cloud Vault could not be asked,
 * and × to dismiss. A tap is the controller's [AcAutocomplete.pick].
 */
@Composable
fun AcFillBar(bar: AcBar?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    if (bar == null) return
    val p = LocalKitPalette.current
    Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp).horizontalScroll(rememberScrollState()).testTag("browser:ac:bar"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        bar.chips.forEach { c ->
            Row(Modifier.padding(4.dp).background(p.surfaceSelected, RoundedCornerShape(14.dp)).clickable { onPick(c.id) }
                .padding(horizontal = 12.dp, vertical = 6.dp).testTag("browser:ac:chip:${c.id}"), verticalAlignment = Alignment.CenterVertically) {
                Text(c.text, color = p.textPrimary, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                c.badge?.let { Text(" · $it", color = p.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                    modifier = Modifier.testTag("browser:ac:badge:${c.id}")) }
            }
        }
        bar.note?.let { Text(it, color = p.textSecondary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp).testTag("browser:ac:note")) }
        Text("×", color = p.textSecondary, style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.clickable(onClick = onDismiss).padding(horizontal = 10.dp, vertical = 4.dp).testTag("browser:ac:dismiss"))
    }
}

/** Which profile, and which value per kind when a profile holds several. Fill uses the choice as it stands. */
@Composable
fun AcChooserSheet(state: AcChooserState, onFill: (AcChoice) -> Unit, onDismiss: () -> Unit) {
    val p = LocalKitPalette.current
    var choice by remember(state) { mutableStateOf(state.initial) }
    val tokens = state.fields.map { it.token }
    Column(
        Modifier.fillMaxWidth().heightIn(max = 460.dp).background(p.surface, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .clickable(remember { MutableInteractionSource() }, null) {}.verticalScroll(rememberScrollState()).padding(14.dp).testTag("browser:ac:chooser"),
    ) {
        Text(state.title, color = p.textPrimary, style = MaterialTheme.typography.titleMedium)
        if (state.profiles.size > 1) {
            Text("Profile", color = p.textSecondary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            state.profiles.forEach { pr ->
                Row(Modifier.fillMaxWidth().clickable { choice = AcFill.choiceFor(pr, tokens) }.testTag("browser:ac:profile:${pr.id}"),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(choice.profileId == pr.id, onClick = null)
                    Text(pr.title(), color = p.textPrimary, modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp))
                }
            }
        }
        state.plan.groups[choice.profileId].orEmpty().forEach { g ->
            Text(g.title, color = p.textSecondary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            g.options.forEach { o ->
                Row(Modifier.fillMaxWidth().clickable { choice = AcFill.with(choice, g.group, o.recordId) }.testTag("browser:ac:value:${g.group}:${o.recordId}"),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(choice.records[g.group] == o.recordId, onClick = null)
                    Column(Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp)) {
                        Text(o.label, color = p.textPrimary, style = MaterialTheme.typography.bodyMedium)
                        if (o.preview.isNotBlank()) Text(o.preview, color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onDismiss, modifier = Modifier.testTag("browser:ac:chooser:cancel")) { Text("Cancel") }
            TextButton({ onFill(choice) }, modifier = Modifier.testTag("browser:ac:chooser:fill")) { Text("Fill") }
        }
    }
}

/** After a submit: what could be saved, each with a box; the profile a detail goes into; "never here" for passwords. */
@Composable
fun AcSaveSheet(state: AcSaveState, onSave: (picked: Set<Int>, profileId: String?, never: Boolean) -> Unit, onDismiss: () -> Unit) {
    val p = LocalKitPalette.current
    var picked by remember(state) { mutableStateOf(state.proposals.indices.toSet()) }
    val hasProfile = state.proposals.any { it is AcProposal.ProfileRecord }
    val hasPassword = state.proposals.any { it is AcProposal.Password }
    var profileId by remember(state) { mutableStateOf<String?>(state.profiles.firstOrNull { it.isDefault }?.id ?: state.profiles.firstOrNull()?.id) }
    Column(
        Modifier.fillMaxWidth().heightIn(max = 460.dp).background(p.surface, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .clickable(remember { MutableInteractionSource() }, null) {}.verticalScroll(rememberScrollState()).padding(14.dp).testTag("browser:ac:save"),
    ) {
        Text("Save to Cloud Browser?", color = p.textPrimary, style = MaterialTheme.typography.titleMedium)
        state.proposals.forEachIndexed { i, pr ->
            Row(Modifier.fillMaxWidth().clickable { picked = if (i in picked) picked - i else picked + i }.testTag("browser:ac:save:item:$i"),
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(i in picked, onCheckedChange = null)
                Text(pr.title, color = p.textPrimary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp))
            }
        }
        if (hasProfile) {
            Text("Add details to", color = p.textSecondary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
            state.profiles.forEach { pr ->
                Row(Modifier.fillMaxWidth().clickable { profileId = pr.id }.testTag("browser:ac:save:profile:${pr.id}"), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(profileId == pr.id, onClick = null)
                    Text(pr.title(), color = p.textPrimary, modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp))
                }
            }
            Row(Modifier.fillMaxWidth().clickable { profileId = null }.testTag("browser:ac:save:profile:new"), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(profileId == null, onClick = null)
                Text("A new profile", color = p.textPrimary, modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp))
            }
        }
        state.vaultNote?.let { Text(it, color = p.textSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp).testTag("browser:ac:save:vault")) }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (hasPassword) TextButton({ onSave(emptySet(), profileId, true) }, modifier = Modifier.testTag("browser:ac:save:never")) { Text("Never here") }
            TextButton(onDismiss, modifier = Modifier.testTag("browser:ac:save:no")) { Text("Not now") }
            TextButton({ onSave(picked, profileId, false) }, enabled = picked.isNotEmpty(), modifier = Modifier.testTag("browser:ac:save:yes")) { Text("Save") }
        }
    }
}
