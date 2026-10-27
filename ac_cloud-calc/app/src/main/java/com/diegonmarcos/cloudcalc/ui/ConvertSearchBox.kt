package com.diegonmarcos.cloudcalc.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import com.diegonmarcos.cloudcalc.ConvertSearch
import com.diegonmarcos.cloudcalc.Declarations
import com.diegonmarcos.cloudcalc.Nutrition

/**
 * The Convert page's search box: type a category, unit, alias or food and tap a row to open its converter
 * (ConvertSearch holds the index and the ranking; CalcState.open does the jump).
 */
@Composable
internal fun ConvertSearchBox(modes: List<Declarations.Mode>) {
    val state = LocalCalcState.current
    var query by rememberSaveable { mutableStateOf("") }
    val entries = remember(modes) { ConvertSearch.index(modes, runCatching { Nutrition.bundled }.getOrNull()) }
    val hits = remember(entries, query) { ConvertSearch.search(entries, query) }
    Column(Modifier.fillMaxWidth().padding(horizontal = CalcMetrics.gutter, vertical = CalcMetrics.small)) {
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().testTag(CalcTags.CONVERT_SEARCH),
            label = { Text("Search units, categories, foods") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )
        hits.forEachIndexed { i, h ->
            Column(
                Modifier.fillMaxWidth().clickable { state.open(h.entry); query = "" }
                    .padding(vertical = CalcMetrics.small).testTag(CalcTags.searchHit(i)),
            ) {
                Text(h.entry.title, style = MaterialTheme.typography.bodyMedium)
                Text(h.entry.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
