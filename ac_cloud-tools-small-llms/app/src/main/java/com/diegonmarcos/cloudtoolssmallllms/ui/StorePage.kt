package com.diegonmarcos.cloudtoolssmallllms.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.diegonmarcos.cloudtoolssmallllms.Decl
import com.diegonmarcos.cloudtoolssmallllms.R
import com.diegonmarcos.cloudtoolssmallllms.data.InstalledModel
import com.diegonmarcos.cloudtoolssmallllms.data.LoadedModel
import com.diegonmarcos.cloudtoolssmallllms.data.Location
import com.diegonmarcos.cloudtoolssmallllms.data.ModelRow
import com.diegonmarcos.cloudtoolssmallllms.data.ModelRuntime
import com.diegonmarcos.cloudtoolssmallllms.data.RuntimeState

private fun gb(v: Double): String = java.lang.String.format(java.util.Locale.US, "%.1f", v)

/** What a model needs at [bits]: disk is the fp16 weights scaled by bits / 16, RAM is the declared figure at that precision. */
private fun needDisk(r: ModelRow, bits: Int): Double = r.diskFp16 * bits / 16.0
private fun needRam(r: ModelRow, bits: Int): Double = when (bits) { 4 -> r.ramQ4; 8 -> r.ramQ8; else -> r.ramFp16 }

/** The three rows of one model on one runtime: where it stands and how it fits. */
private data class Entry(val loc: Location, val rt: ModelRuntime, val st: RuntimeState, val row: ModelRow, val installed: InstalledModel?, val loaded: LoadedModel?)

/**
 * Store: every model of the data file, per location (this phone, the fleet's Ollama), with its state from the runtime
 * (Not installed / Installed with size and quantization / Loaded with RAM used), the free disk and RAM against what the
 * model needs and a fits badge, and the actions install (4 / 8 / 16-bit), uninstall, load, unload. [page] filters the list:
 * installed, loaded or all. Nothing is stored here: every state is read from the runtimes (ModelRuntime).
 */
@Composable
fun StorePage(model: ToolsModel, page: String) {
    val data = model.models
    val entries = model.runtimes.flatMap { rt ->
        val loc = data.locations.firstOrNull { it.id == rt.id } ?: return@flatMap emptyList()
        val st = model.runtimeStates[rt.id] ?: RuntimeState()
        data.rows.map { r -> Entry(loc, rt, st, r, st.installed.firstOrNull { it.name == r.model }, st.loaded.firstOrNull { it.name == r.model }) }
    }
    val shown = entries.filter { e ->
        when (page) {
            Decl.PAGE_INSTALLED -> e.installed != null || e.loaded != null
            Decl.PAGE_LOADED -> e.loaded != null
            else -> true
        }
    }
    LazyColumn(Modifier.fillMaxSize().testTag(Tags.STORE), contentPadding = PaddingValues(ToolsMetrics.gutter), verticalArrangement = Arrangement.spacedBy(ToolsMetrics.gap)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ToolsMetrics.gap)) {
                Chip(stringResource(R.string.store_refresh), tag = Tags.STORE_REFRESH) { model.refreshStore() }
            }
            model.storeBusy?.let { Text(stringResource(R.string.store_busy, it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary) }
            model.storeError?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(Tags.STORE_ERROR)) }
        }
        model.runtimes.forEach { rt ->
            val loc = data.locations.firstOrNull { it.id == rt.id } ?: return@forEach
            val st = model.runtimeStates[rt.id]
            item(key = "loc_" + rt.id) { LocationHeader(loc, rt, st) }
            val mine = shown.filter { it.loc.id == rt.id }
            if (mine.isEmpty()) item(key = "none_" + rt.id) { Text(stringResource(R.string.store_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(mine, key = { it.loc.id + "/" + it.row.model }) { e -> StoreCard(model, e) }
        }
    }
}

@Composable
private fun LocationHeader(loc: Location, rt: ModelRuntime, st: RuntimeState?) {
    Column(Modifier.fillMaxWidth().testTag(Tags.location(loc.id)), verticalArrangement = Arrangement.spacedBy(ToolsMetrics.tiny)) {
        Text(loc.label, style = MaterialTheme.typography.titleMedium)
        val free = stringResource(R.string.store_free, st?.freeDiskGb?.let { gb(it) } ?: "?", st?.freeRamGb?.let { gb(it) } ?: "?")
        Text(free, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (st?.error != null) Text(stringResource(R.string.store_unreachable, st.error), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        if (!rt.canManage) Text(stringResource(R.string.store_no_engine), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StoreCard(model: ToolsModel, e: Entry) {
    var bits by rememberSaveable(e.loc.id, e.row.model) { mutableStateOf(4) }
    val r = e.row
    val state = when {
        e.loaded != null -> stringResource(R.string.store_loaded, gb(e.loaded.ramGb))
        e.installed != null -> stringResource(R.string.store_installed, gb(e.installed.sizeGb), e.installed.bits?.let { "$it-bit" } ?: "?")
        else -> stringResource(R.string.store_not_installed)
    }
    val shownBits = e.installed?.bits ?: bits
    val diskOk = e.st.freeDiskGb?.let { it >= needDisk(r, shownBits) }
    val ramOk = e.st.freeRamGb?.let { it >= needRam(r, shownBits) }
    val fits = when {
        diskOk == null && ramOk == null -> null
        else -> (diskOk ?: true) && (ramOk ?: true)
    }
    val busy = model.storeBusy != null
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(ToolsMetrics.corner)).background(MaterialTheme.colorScheme.surface)
            .padding(ToolsMetrics.gutter).testTag(Tags.store(e.loc.id, r.model)),
        verticalArrangement = Arrangement.spacedBy(ToolsMetrics.small),
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.model, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(r.role + " · " + r.params, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(state, style = MaterialTheme.typography.labelMedium, color = if (e.loaded != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(stringResource(R.string.store_need, shownBits, gb(needDisk(r, shownBits)), gb(needRam(r, shownBits))), style = MaterialTheme.typography.labelSmall)
        if (e.installed == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(ToolsMetrics.small)) {
                listOf(4, 8, 16).forEach { b -> Chip("$b-bit", selected = bits == b, tag = Tags.bits(b)) { bits = b } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ToolsMetrics.small), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            if (fits != null) Chip(stringResource(if (fits) R.string.store_fits else R.string.store_no_fit), selected = fits, tag = Tags.FITS)
            if (e.rt.canManage && !busy) {
                val name = r.model
                if (e.installed == null) Chip(stringResource(R.string.store_install), selected = true, tag = Tags.STORE_INSTALL) {
                    model.storeAction(e.rt, "install $name $bits-bit on ${e.loc.label}") { it.install(name, bits) }
                }
                if (e.installed != null && e.loaded == null) {
                    Chip(stringResource(R.string.store_load), selected = true, tag = Tags.STORE_LOAD) { model.storeAction(e.rt, "load $name on ${e.loc.label}") { it.load(e.installed) } }
                    Chip(stringResource(R.string.store_uninstall), tag = Tags.STORE_UNINSTALL) { model.storeAction(e.rt, "uninstall $name on ${e.loc.label}") { it.uninstall(e.installed) } }
                }
                if (e.loaded != null) Chip(stringResource(R.string.store_unload), tag = Tags.STORE_UNLOAD) { model.storeAction(e.rt, "unload $name on ${e.loc.label}") { it.unload(e.loaded) } }
            }
        }
    }
}
