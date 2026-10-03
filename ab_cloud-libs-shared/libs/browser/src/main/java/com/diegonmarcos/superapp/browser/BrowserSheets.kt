package com.diegonmarcos.superapp.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.uikit.KitSectionHeader
import com.diegonmarcos.superapp.uikit.KitSelectableTile
import com.diegonmarcos.superapp.uikit.KitSettingsRow
import com.diegonmarcos.superapp.uikit.KitSwitchRow
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * #802 the browser's Compose surfaces, drawn over the page by the Views host
 * (Context.kitComposeView — the #773 interop, no Dialog or PopupWindow). Each one is
 * a pure function of what it is handed; the host owns every action.
 */

/** One row of a sheet: what [BrowserMenuRow] resolves to, or an ad-hoc row (the tab card menu). */
data class SheetRow(val id: String, val label: String, val enabled: Boolean = true, val why: String? = null, val checked: Boolean? = null)

fun BrowserMenuRow.sheet() = SheetRow(item.id, item.label, enabled, why, checked)

/** A bottom sheet over a scrim: an optional icon row, then titled sections. Scrim tap dismisses. */
@Composable
fun BrowserSheet(
    icons: List<SheetRow>,
    sections: List<Pair<String, List<SheetRow>>>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalKitPalette.current
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(onClick = onDismiss)) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = 560.dp)
                .background(p.surface, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .clickable(remember { MutableInteractionSource() }, null) {}   // the sheet itself does not dismiss
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .testTag("browser:sheet"),
        ) {
            if (icons.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    icons.forEach { r ->
                        TextButton(onClick = { onPick(r.id) }, enabled = r.enabled, modifier = Modifier.testTag("browser:menu:${r.id}")) {
                            Text(r.label, style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
            sections.forEach { (title, rows) ->
                HorizontalDivider(color = p.hairline)
                Text(title, color = p.textSecondary, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                rows.forEach { r ->
                    val suffix = when (r.checked) { true -> "  ✓"; false -> ""; null -> "" }
                    KitSettingsRow(
                        title = r.label + suffix,
                        subtitle = if (r.enabled) "" else r.why.orEmpty(),
                        modifier = Modifier.testTag("browser:menu:${r.id}"),
                        onClick = if (r.enabled) ({ onPick(r.id) }) else null,
                    )
                }
            }
        }
    }
}

/** Find in page: the query, the match count, previous / next / close. */
@Composable
fun BrowserFindBar(count: Int?, onQuery: (String) -> Unit, onNext: (Boolean) -> Unit, onClose: () -> Unit) {
    val p = LocalKitPalette.current
    var q by remember { mutableStateOf("") }
    Row(
        Modifier.fillMaxWidth().background(p.surface).padding(8.dp).testTag("browser:find"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(q, { q = it; onQuery(it) }, Modifier.weight(1f), singleLine = true,
            placeholder = { Text("Find in page") })
        Text(count?.let { " $it " } ?: "", color = p.textSecondary)
        TextButton({ onNext(false) }) { Text("▲") }
        TextButton({ onNext(true) }) { Text("▼") }
        TextButton(onClose) { Text("✕") }
    }
}

/**
 * Settings, drawn from the catalogue: a switch per bool, a pick list per enum, a slider per
 * int, a field per string. Nothing here names a setting — add one to build.json and it appears.
 */
@Composable
fun BrowserSettingsScreen(
    catalogue: BrowserSettingsCatalogue,
    value: (String) -> Any?,
    onSet: (String, Any) -> Unit,
    extra: List<SheetRow>,
    onExtra: (String) -> Unit,
    onClose: () -> Unit,
) {
    val p = LocalKitPalette.current
    var tick by remember { mutableStateOf(0) }
    Column(
        Modifier.fillMaxSize().background(p.surface).verticalScroll(rememberScrollState()).padding(16.dp)
            .testTag("browser:settings"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClose) { Text("← Back") }
            Text("Settings", color = p.textPrimary, style = MaterialTheme.typography.titleLarge)
        }
        catalogue.settings.groupBy { it.section }.forEach { (section, settings) ->
            KitSectionHeader(section.replaceFirstChar { it.uppercase() }, "", Modifier.padding(top = 12.dp))
            settings.forEach { s ->
                val v = tick.let { value(s.key) }   // reading tick re-reads the store after a write
                val set = { x: Any -> onSet(s.key, x); tick += 1 }
                when (s.type) {
                    "bool" -> KitSwitchRow(s.label, s.doc, v == true, { set(it) }, Modifier.testTag("browser:setting:${s.key}"))
                    "enum" -> {
                        var open by remember { mutableStateOf(false) }
                        KitSettingsRow(s.label, v?.toString().orEmpty(), Modifier.testTag("browser:setting:${s.key}"), onClick = { open = !open })
                        if (open) s.values.forEach { opt ->
                            KitSelectableTile(opt, "", selected = v == opt, onClick = { set(opt); open = false })
                        }
                    }
                    "int" -> {
                        var f by remember(v) { mutableFloatStateOf(((v as? Int) ?: s.min ?: 0).toFloat()) }
                        KitSettingsRow(s.label, "${f.toInt()}", Modifier.testTag("browser:setting:${s.key}"))
                        Slider(f, { f = it }, valueRange = (s.min ?: 0).toFloat()..(s.max ?: 100).toFloat(),
                            onValueChangeFinished = { set(f.toInt()) })
                    }
                    "string" -> {
                        var t by remember(v) { mutableStateOf(v?.toString().orEmpty()) }
                        Text(s.label, color = p.textPrimary)
                        OutlinedTextField(t, { t = it }, Modifier.fillMaxWidth().testTag("browser:setting:${s.key}"),
                            singleLine = true, supportingText = { Text(s.doc) })
                        TextButton({ set(t) }) { Text("Save") }
                    }
                }
            }
        }
        if (extra.isNotEmpty()) {
            HorizontalDivider(color = p.hairline)
            extra.forEach { r ->
                KitSettingsRow(r.label, if (r.enabled) "" else r.why.orEmpty(), Modifier.testTag("browser:menu:${r.id}"),
                    onClick = if (r.enabled) ({ onExtra(r.id) }) else null)
            }
        }
    }
}

/** A titled, scrollable block of text over a scrim (translation, tab groups). */
@Composable
fun BrowserTextPanel(title: String, text: String, rows: List<SheetRow> = emptyList(), onPick: (String) -> Unit = {}, onClose: () -> Unit) {
    val p = LocalKitPalette.current
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(onClick = onClose)) {
        Column(
            Modifier.align(Alignment.Center).fillMaxWidth().padding(16.dp).heightIn(max = 600.dp)
                .background(p.surface, RoundedCornerShape(16.dp)).clickable(remember { MutableInteractionSource() }, null) {}   // the sheet itself does not dismiss
                .verticalScroll(rememberScrollState()).padding(16.dp).testTag("browser:panel"),
        ) {
            Text(title, color = p.textPrimary, style = MaterialTheme.typography.titleLarge)
            if (text.isNotBlank()) Text(text, color = p.textPrimary, modifier = Modifier.padding(top = 8.dp))
            rows.forEach { r -> KitSettingsRow(r.label, r.why.orEmpty(), onClick = { onPick(r.id) }) }
            TextButton(onClose, Modifier.align(Alignment.End)) { Text("Close") }
        }
    }
}

/** One line of a library list (a bookmark, a download, a visit), or a folder header when [header]. */
data class ListRow(val id: String, val title: String, val subtitle: String = "", val header: Boolean = false)

/**
 * #802 the Library pages (bookmarks, downloads, history): a titled list over the page.
 * Tap opens a row; ✕ removes it (or the whole folder, on a header); [headerAction] is
 * a page-wide button (Clear). Rename on a folder header asks for the new path inline.
 */
@Composable
fun BrowserListScreen(
    title: String,
    rows: List<ListRow>,
    empty: String,
    onOpen: (ListRow) -> Unit,
    onRemove: ((ListRow) -> Unit)?,
    onRename: ((ListRow, String) -> Unit)? = null,
    headerAction: Pair<String, () -> Unit>? = null,
    onClose: () -> Unit,
) {
    val p = LocalKitPalette.current
    var renaming by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().background(p.surface).verticalScroll(rememberScrollState()).padding(16.dp)
            .testTag("browser:list:$title"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClose) { Text("← Back") }
            Text(title, color = p.textPrimary, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            headerAction?.let { (label, act) -> TextButton(act) { Text(label) } }
        }
        if (rows.isEmpty()) Text(empty, color = p.textSecondary, modifier = Modifier.padding(16.dp))
        rows.forEach { r ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable { onOpen(r) }.padding(vertical = 8.dp)) {
                    Text(if (r.header) "▸ ${r.title}" else r.title, color = if (r.header) p.accent else p.textPrimary,
                        style = if (r.header) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyLarge, maxLines = 1)
                    if (r.subtitle.isNotBlank()) Text(r.subtitle, color = p.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
                if (r.header && onRename != null) TextButton({ renaming = r.id; newName = r.id }) { Text("✎") }
                onRemove?.let { rm -> TextButton({ rm(r) }) { Text("✕") } }
            }
            if (renaming == r.id && onRename != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newName, { newName = it }, Modifier.weight(1f), singleLine = true)
                    TextButton({ onRename(r, newName); renaming = null }) { Text("Save") }
                }
            }
        }
    }
}

/** #802 "Clear browsing data": one checkbox per declared box, then Clear. */
@Composable
fun BrowserClearScreen(boxes: List<Pair<String, String>>, note: String, onClear: (Set<String>) -> Unit, onClose: () -> Unit) {
    val p = LocalKitPalette.current
    var picked by remember { mutableStateOf(boxes.map { it.first }.toSet()) }
    Column(Modifier.fillMaxSize().background(p.surface).verticalScroll(rememberScrollState()).padding(16.dp).testTag("browser:clear")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClose) { Text("← Back") }
            Text("Clear browsing data", color = p.textPrimary, style = MaterialTheme.typography.titleLarge)
        }
        boxes.forEach { (id, label) ->
            Row(Modifier.fillMaxWidth().clickable { picked = if (id in picked) picked - id else picked + id },
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(id in picked, onCheckedChange = null)
                Text(label, color = p.textPrimary)
            }
        }
        Text(note, color = p.textSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
        TextButton({ onClear(picked) }, enabled = picked.isNotEmpty(), modifier = Modifier.testTag("browser:clear:go")) { Text("Clear") }
    }
}

/**
 * #802 the Web Scraper sheet: a CSS selector (typed, or picked by tapping the page), an
 * optional attribute, pages to follow through a "next" link, then Run on the phone or on
 * the server; Export writes the last result to Downloads as CSV.
 */
@Composable
fun BrowserScraperScreen(
    result: String,
    onPick: () -> Unit,
    onUsePicked: ((String) -> Unit) -> Unit,
    onRun: (css: String, attr: String, pages: Int, next: String) -> Unit,
    onRemote: (css: String) -> Unit,
    onExport: () -> Unit,
    onClose: () -> Unit,
) {
    val p = LocalKitPalette.current
    var css by remember { mutableStateOf("") }
    var attr by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var pages by remember { mutableStateOf("1") }
    Column(Modifier.fillMaxSize().background(p.surface).verticalScroll(rememberScrollState()).padding(16.dp).testTag("browser:scraper")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClose) { Text("← Back") }
            Text("Web scraper", color = p.textPrimary, style = MaterialTheme.typography.titleLarge)
        }
        OutlinedTextField(css, { css = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("CSS selector") })
        Row {
            TextButton(onPick) { Text("Pick on page") }
            TextButton({ onUsePicked { css = it } }) { Text("Use picked") }
        }
        OutlinedTextField(attr, { attr = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Attribute (optional: href, src…)") })
        OutlinedTextField(next, { next = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Next-page link selector (optional)") })
        OutlinedTextField(pages, { pages = it.filter { c -> c.isDigit() }.take(2) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Pages") })
        Row {
            TextButton({ onRun(css, attr, pages.toIntOrNull() ?: 1, next) }, enabled = css.isNotBlank()) { Text("Run here") }
            TextButton({ onRemote(css) }) { Text("Run on server") }
            TextButton(onExport) { Text("Export CSV") }
        }
        Text(result, color = p.textSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
    }
}
