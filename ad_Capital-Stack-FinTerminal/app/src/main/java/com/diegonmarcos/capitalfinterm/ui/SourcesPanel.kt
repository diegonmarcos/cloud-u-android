package com.diegonmarcos.capitalfinterm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.diegonmarcos.capitalfinterm.AssetData
import com.diegonmarcos.capitalfinterm.Catalog
import com.diegonmarcos.capitalfinterm.R
import com.diegonmarcos.capitalfinterm.Source
import com.diegonmarcos.capitalfinterm.SourceKeys
import com.diegonmarcos.superapp.uikit.GroupTitle
import com.diegonmarcos.superapp.uikit.KitButton
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitSectionHeader
import com.diegonmarcos.superapp.uikit.KitSettingsRow
import com.diegonmarcos.superapp.uikit.KitState
import com.diegonmarcos.superapp.uikit.KitStatePill
import com.diegonmarcos.superapp.uikit.KitTextButton
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * Config > Sources (the `sources` kind): the whole source catalog of data/sources.json by category, with what each source covers, what
 * it needs (no key, a free key, a paid one), which FinTerm entries it feeds, its documentation, and a field for the user's own API key.
 * A key is kept by [SourceKeys] in encrypted storage on this phone, is never shown again (only that one is saved), never logged.
 */
@Composable
fun SourcesPanel() {
    val ctx = LocalContext.current
    val sources = remember { AssetData.sources(ctx) }
    val categories = remember { Catalog.categories(AssetData.sourcesJson(ctx), sources) }
    val keys = remember { SourceKeys(EncryptedSecretBox(ctx)) }
    var saved by remember { mutableStateOf(sources.filter { keys.has(it.id) }.map { it.id }.toSet()) }
    KitCard {
        KitSectionHeader(stringResource(R.string.sources_title), stringResource(R.string.sources_sub))
    }
    sources.filter { it.primary }.forEach { s ->
        GroupTitle(stringResource(R.string.sources_primary))
        ContainerControl(s.id)
        SourceCard(
            source = s,
            hasKey = s.id in saved,
            onSave = { raw -> keys.save(s.id, raw).also { ok -> if (ok) saved = saved + s.id } },
            onClear = { keys.clear(s.id); saved = saved - s.id },
        )
    }
    categories.forEach { category ->
        GroupTitle(category)
        sources.filter { it.category == category && !it.primary }.forEach { s ->
            SourceCard(
                source = s,
                hasKey = s.id in saved,
                onSave = { raw -> keys.save(s.id, raw).also { ok -> if (ok) saved = saved + s.id } },
                onClear = { keys.clear(s.id); saved = saved - s.id },
            )
        }
    }
}

@Composable
private fun SourceCard(source: Source, hasKey: Boolean, onSave: (String) -> Boolean, onClear: () -> Unit) {
    val ctx = LocalContext.current
    val p = LocalKitPalette.current
    val all = stringResource(R.string.sources_all)
    val feeds = source.feeds.joinToString("\n") { f -> f.tab.uppercase() + ": " + (if ("*" in f.entries) all else f.entries.joinToString(", ")) }
    val auth = when (source.authType) {
        Catalog.AUTH_NONE -> stringResource(R.string.auth_none)
        Catalog.AUTH_FREE -> stringResource(R.string.auth_free)
        else -> stringResource(R.string.auth_paid)
    }
    val (word, state) = when {
        !source.needsKey -> stringResource(R.string.state_no_key) to KitState.OK
        hasKey -> stringResource(R.string.state_key_saved) to KitState.OK
        else -> stringResource(R.string.state_add_key) to KitState.WARN
    }
    KitCard(Modifier.testTag(Tags.source(source.id))) {
        KitSettingsRow(title = source.name, subtitle = source.coverage, trailing = { KitStatePill(word, state) })
        Text(auth + " - " + if (source.hasConnector) stringResource(R.string.connector_live) else stringResource(R.string.connector_layout),
            color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.sources_feeds, feeds), color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
        Text(source.endpoint.ifBlank { stringResource(R.string.sources_user_endpoint) }, color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
        if (source.attribution.isNotBlank()) Text(source.attribution, color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
        if (source.authNote.isNotBlank()) Text(source.authNote, color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
        if (source.needsKey) KeyEditor(source, hasKey, onSave, onClear)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (source.docs.isNotBlank()) KitTextButton(onClick = { openUrl(ctx, source.docs) }) { Text(stringResource(R.string.sources_docs)) }
            if (source.signup.isNotBlank()) KitTextButton(onClick = { openUrl(ctx, source.signup) }) { Text(stringResource(R.string.sources_get_key)) }
        }
    }
}

/** The key field: masked while typed, kept nowhere but SourceKeys, cleared from the screen the moment it is saved. */
@Composable
private fun KeyEditor(source: Source, hasKey: Boolean, onSave: (String) -> Boolean, onClear: () -> Unit) {
    var draft by remember(source.id) { mutableStateOf("") }
    var rejected by remember(source.id) { mutableStateOf(false) }
    OutlinedTextField(
        value = draft, onValueChange = { draft = it; rejected = false }, singleLine = true,
        label = { Text(stringResource(R.string.key_label, source.name)) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth().testTag(Tags.key(source.id)),
    )
    if (rejected) Text(stringResource(R.string.key_rejected), color = MaterialTheme.colorScheme.error)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KitButton(onClick = { if (onSave(draft)) { draft = ""; rejected = false } else rejected = true }) { Text(stringResource(R.string.key_save)) }
        if (hasKey) KitTextButton(onClick = onClear) { Text(stringResource(R.string.key_remove)) }
    }
}
