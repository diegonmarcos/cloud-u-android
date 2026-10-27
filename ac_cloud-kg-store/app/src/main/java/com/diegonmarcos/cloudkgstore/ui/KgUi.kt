package com.diegonmarcos.cloudkgstore.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudkgstore.KgCalls
import com.diegonmarcos.cloudkgstore.R

/** The test tags the shell and the pages are driven by. */
object KgTags {
    const val SHELL = "kg_shell"
    const val CHAT = "kg_chat"
    const val SECOND_ROW = "kg_second_row"
    const val PAGE_TEXT = "kg_page_text"
    const val QUERY = "kg_query"
    const val GO = "kg_go"
    const val STATUS = "kg_status"
}

/** A query box with its Search button; Search on the keyboard does the same. */
@Composable
fun KgQueryBar(query: String, onQuery: (String) -> Unit, hint: String, busy: Boolean, onGo: () -> Unit, goLabel: Int? = R.string.go) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query, onValueChange = onQuery, singleLine = true, placeholder = { Text(hint) },
            modifier = Modifier.weight(1f).testTag(KgTags.QUERY),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { onGo() }),
        )
        if (goLabel != null) Button(onClick = onGo, enabled = !busy, modifier = Modifier.testTag(KgTags.GO)) { Text(stringResource(goLabel)) }
    }
}

/** One horizontally scrollable row of single-choice chips: (id, label) pairs. */
@Composable
fun KgChips(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    if (options.size < 2) return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (id, label) -> FilterChip(selected = id == selected, onClick = { onSelect(id) }, label = { Text(label) }) }
    }
}

/** A line of status text: the page says what came of the last call, in words. */
@Composable
fun KgNote(text: String, error: Boolean = false, mono: Boolean = false) {
    if (text.isBlank()) return
    Text(
        text, style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        fontFamily = if (mono) FontFamily.Monospace else null,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag(KgTags.STATUS),
    )
}

/** A section heading inside a list. */
@Composable
fun KgHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp))
}

/** One result row: a title, up to two lines under it, and the whole row opens [onClick] when given. */
@Composable
fun KgRow(title: String, line1: String = "", line2: String = "", onClick: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().let { if (onClick != null) it.clickable { onClick() } else it }.padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (line1.isNotBlank()) Text(line1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (line2.isNotBlank()) Text(line2, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** What a [KgCalls.Result] came to, as one sentence: the count and route when it worked, the service's or transport's words when not. */
@Composable
fun statusText(r: KgCalls.Result<*>?, busy: Boolean, empty: Int = R.string.status_empty): String = when {
    busy -> stringResource(R.string.status_busy)
    r == null -> ""
    else -> when (r.kind) {
        KgCalls.Kind.OK -> stringResource(R.string.status_ok, r.items.size, r.via)
        KgCalls.Kind.EMPTY -> stringResource(empty) + if (r.detail.isNotBlank()) " " + r.detail else ""
        KgCalls.Kind.UNAVAILABLE -> stringResource(R.string.status_unavailable, r.detail)
        KgCalls.Kind.REFUSED -> stringResource(R.string.status_refused, r.detail)
        KgCalls.Kind.NOTE -> stringResource(R.string.status_note, r.detail)
    }
}

fun isError(r: KgCalls.Result<*>?): Boolean = r != null && (r.kind == KgCalls.Kind.UNAVAILABLE || r.kind == KgCalls.Kind.REFUSED)
