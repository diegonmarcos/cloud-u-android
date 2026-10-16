package com.diegonmarcos.superapp.devcontrol

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/** Test tags of the About page: one place, so the tests and the page cannot drift apart. */
internal object AboutTags {
    const val TITLE = "about:title"
    const val INDEX = "about:index"
    const val BACK = "about:back"
    const val LIST = "about:list"
    fun macro(label: String) = "about:macro:$label"
    fun section(head: String) = "about:section:$head"
    fun placeholder(head: String) = "about:placeholder:$head"
    fun row(key: String) = "about:row:$key"
    fun button(label: String) = "about:button:$label"
    fun toggle(label: String) = "about:toggle:$label"
    fun tree(label: String) = "about:tree:$label"
}

/** What the page lets its rows and buttons do: long-press copy, and say that it did. */
internal class AboutActions(val copy: (toast: String, text: String) -> Unit, val toast: (String) -> Unit)

internal val LocalAboutActions = staticCompositionLocalOf<AboutActions> {
    error("About composable used outside the About page")
}

@Composable
internal fun AboutTitle(text: String) {
    Text(text, color = LocalKitPalette.current.textPrimary, style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp).testTag(AboutTags.TITLE))
}

/** Big macro-section divider ("☁ CLOUD", "📱 PHONE"), splitting the page into groups. */
@Composable
internal fun AboutMacroHeader(macro: AboutBlock.Macro) {
    val p = LocalKitPalette.current
    Text(
        (macro.glyph + "  " + macro.label).trim(), color = p.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp).background(p.accent.copy(alpha = 0.13f))
            .padding(start = 8.dp, top = 12.dp, end = 8.dp, bottom = 10.dp).testTag(AboutTags.macro(macro.label)),
    )
}

@Composable
internal fun AboutSectionView(section: AboutSection) {
    val p = LocalKitPalette.current
    Column(Modifier.fillMaxWidth().testTag(AboutTags.section(section.head))) {
        Text(section.head, color = p.accent, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            // An unloaded block says so: it is not empty and it is not broken, it has simply not been reached.
            if (!section.started) AboutSmall("▸ loads when you reach it", Modifier.testTag(AboutTags.placeholder(section.head)))
            for (item in section.items) AboutItemView(item)
        }
    }
}

@Composable
internal fun AboutItemView(item: AboutItem) {
    when (item) {
        is AboutItem.Row -> AboutRowView(item)
        is AboutItem.Small -> AboutSmall(item.text)
        is AboutItem.Button -> AboutButton(item)
        is AboutItem.Toggle -> AboutToggle(item)
        is AboutItem.Custom -> item.content()
    }
}

@Composable
internal fun AboutSmall(text: String, modifier: Modifier = Modifier) {
    Text(text, color = LocalKitPalette.current.textSecondary, style = MaterialTheme.typography.labelSmall,
        modifier = modifier.padding(vertical = 4.dp))
}

/** A key / value line. LONG-PRESS the value to copy "key: value" — the contract this page has always had. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AboutRowView(row: AboutItem.Row) {
    val p = LocalKitPalette.current
    val actions = LocalAboutActions.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(row.key, color = p.textPrimary.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(110.dp))
        Text(row.text, color = row.color ?: p.accent, fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.weight(1f).combinedClickable(onClick = {}, onLongClick = {
                actions.copy("Copied ${row.key}", "${row.key}: ${row.text}")
            }).testTag(AboutTags.row(row.key)))
    }
}

@Composable
internal fun AboutButton(b: AboutItem.Button) {
    val p = LocalKitPalette.current
    Text(
        b.label, color = p.textPrimary, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            .background(if (b.gray) p.surfaceSelected else p.accent.copy(alpha = 0.85f))
            .clickable { b.onClick() }.padding(horizontal = 12.dp, vertical = 10.dp).testTag(AboutTags.button(b.label)),
    )
}

@Composable
internal fun AboutToggle(t: AboutItem.Toggle) {
    val p = LocalKitPalette.current
    Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(t.label, color = p.textPrimary, modifier = Modifier.weight(1f))
        Switch(checked = t.on, onCheckedChange = { t.on = it; t.onChange(it) }, modifier = Modifier.testTag(AboutTags.toggle(t.label)))
    }
}

@Composable
internal fun AboutBackToIndex(onClick: () -> Unit) {
    Text("↑  Go Back Up to Index", color = LocalKitPalette.current.textSecondary, fontSize = 12.sp, textAlign = TextAlign.End,
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(start = 6.dp, top = 10.dp, end = 6.dp, bottom = 14.dp)
            .testTag(AboutTags.BACK))
}

/** A tree dump as an accordion: tapping a button OPENS its box and CLOSES the others (one box visible at a time), tapping the
 *  open one closes everything. LONG-PRESS — on the button or anywhere in the open box — copies that tree's full text. */
internal class AboutTree(val label: String, val columns: List<Pair<String, String>>) {
    val textSize get() = if (columns.size > 1) 7.sp else 9.sp
    val copyText get() = if (columns.size == 1) columns[0].second
        else columns.joinToString("\n\n") { "## ${it.first}\n${it.second}" }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AboutTreeAccordion(trees: List<AboutTree>) {
    val p = LocalKitPalette.current
    val actions = LocalAboutActions.current
    var open by remember { mutableIntStateOf(-1) }
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((idx, t) in trees.withIndex()) {
            Text((if (open == idx) "▾ " else "▸ ") + t.label, color = p.accent, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).background(p.textPrimary.copy(alpha = 0.13f)).combinedClickable(
                    onClick = { open = if (open == idx) -1 else idx },
                    onLongClick = { actions.copy("${t.label} copied", t.copyText) },
                ).padding(horizontal = 6.dp, vertical = 8.dp).testTag(AboutTags.tree(t.label)))
        }
    }
    val t = trees.getOrNull(open) ?: return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for ((sub, txt) in t.columns) {
            val colName = if (sub.isBlank()) t.label else "${t.label} · $sub"
            Column(Modifier.weight(1f)) {
                if (sub.isNotBlank()) Text(sub, color = p.accent, fontWeight = FontWeight.Bold, fontSize = 10.sp,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp, end = 4.dp, bottom = 2.dp))
                // No text selection - it would hijack the long-press into a selection gesture. Long-press copies instead.
                Box(Modifier.horizontalScroll(rememberScrollState())) {
                    Text(txt, color = p.textPrimary, fontFamily = FontFamily.Monospace, fontSize = t.textSize,
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.2f)).combinedClickable(
                            onClick = {}, onLongClick = { actions.copy("$colName copied", txt) },
                        ).padding(8.dp))
                }
            }
        }
    }
}

/** The Index card block at the top: derived from the page's own macro headers, so a new header appears here by itself. */
@Composable
internal fun AboutIndex(macros: List<AboutBlock.Macro>, onGo: (String) -> Unit) {
    val p = LocalKitPalette.current
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp).testTag(AboutTags.INDEX)) {
        Text("Index", color = p.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
        // Same card grid C3 ▸ Topology's Index draws, from the same builder — three across, because these labels are
        // words rather than the short nouns a six-wide stack row carries. The row SHAPE is a layout decision; the
        // CONTENT is derived from the macros in page order, so a removed one cannot leave a dead link behind.
        val cells = macros.map { m ->
            com.diegonmarcos.superapp.launcher.IndexTiles.Cell(label = m.label, glyph = m.glyph, onClick = { onGo(m.label) })
        }
        var at = 0
        for (perRow in INDEX_ROW_SHAPE) {
            if (at >= cells.size) break
            val end = minOf(at + perRow, cells.size)
            com.diegonmarcos.superapp.launcher.IndexTileGrid(perRow, cells.subList(at, end))
            at = end
        }
        // Whatever the shape does not name still gets a row instead of vanishing.
        if (at < cells.size) com.diegonmarcos.superapp.launcher.IndexTileGrid(3, cells.subList(at, cells.size))
    }
}

/** How many index cards sit on each row, top to bottom. The ORDER comes from the page itself — only the shape is decided here. */
internal val INDEX_ROW_SHAPE = listOf(2, 3, 3, 3)

/** A one-field text prompt (web server port / document root). [onNeutral] adds a middle button ("Default"). */
internal class AboutPrompt(
    val title: String,
    val message: String,
    val initial: String,
    val numeric: Boolean,
    val neutralLabel: String? = null,
    val onNeutral: (() -> Unit)? = null,
    val onSave: (String) -> Unit,
)

@Composable
internal fun AboutPromptDialog(prompt: AboutPrompt, onDismiss: () -> Unit) {
    var text by remember(prompt) { mutableStateOf(prompt.initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(prompt.title) },
        text = {
            Column {
                Text(prompt.message)
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = if (prompt.numeric) KeyboardType.Number else KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("about:prompt:field"))
            }
        },
        confirmButton = { TextButton(onClick = { prompt.onSave(text); onDismiss() }, modifier = Modifier.testTag("about:prompt:save")) { Text("Save") } },
        dismissButton = {
            Row {
                if (prompt.neutralLabel != null) TextButton(onClick = { prompt.onNeutral?.invoke(); onDismiss() }) { Text(prompt.neutralLabel) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
