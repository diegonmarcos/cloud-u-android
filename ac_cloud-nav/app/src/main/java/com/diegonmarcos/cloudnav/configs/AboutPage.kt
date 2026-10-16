package com.diegonmarcos.cloudnav.configs

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/** A button of a permission row. [granted]: true = done (muted, check), false = action needed, null = a plain action. */
class PermBtn(val label: String, val granted: Boolean?, val onClick: () -> Unit) {
    val text: String get() = if (granted == true) "✓ $label" else label
}

/** One element of the About page. The page is built as data ([AboutSink]) and drawn by [AboutPage]. */
sealed interface AboutItem {
    class Title(val text: String) : AboutItem
    class Macro(val text: String) : AboutItem
    class Header(val text: String) : AboutItem
    class Row(val key: String, val value: String) : AboutItem
    class Small(val text: String) : AboutItem
    class Action(val label: String, val dark: Boolean, val onClick: () -> Unit) : AboutItem
    /** A monospace block; [text] is read while composing, so it can be state. */
    class Mono(val text: () -> String, val maxLines: Int = Int.MAX_VALUE) : AboutItem
    class ButtonRow(val buttons: List<Pair<String, () -> Unit>>) : AboutItem
    class PermRow(val buttons: List<PermBtn>) : AboutItem
    class Toggle(val label: String, val checked: Boolean, val onChange: (Boolean) -> Unit) : AboutItem
}

class AboutSink {
    val items = ArrayList<AboutItem>()
    fun add(item: AboutItem) { items.add(item) }
}

object AboutTags {
    const val PAGE = "about:page"
    const val TOGGLE = "about:toggle"
    fun row(key: String) = "about:row:$key"
    fun action(label: String) = "about:action:$label"
    fun perm(label: String) = "about:perm:$label"
}

@Composable
fun AboutPage(list: List<AboutItem>, onCopyRow: (String, String) -> Unit) {
    val p = LocalKitPalette.current
    LazyColumn(Modifier.fillMaxSize().background(p.surface).testTag(AboutTags.PAGE), contentPadding = PaddingValues(16.dp)) {
        items(list) { AboutItemView(it, onCopyRow) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AboutItemView(item: AboutItem, onCopyRow: (String, String) -> Unit) {
    val p = LocalKitPalette.current
    when (item) {
        is AboutItem.Title -> Text(item.text, Modifier.padding(bottom = 12.dp), color = p.textPrimary,
            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        is AboutItem.Macro -> Text(item.text, Modifier.fillMaxWidth().padding(top = 20.dp).background(p.surfaceSelected).padding(horizontal = 8.dp, vertical = 10.dp),
            color = p.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        is AboutItem.Header -> Text(item.text, Modifier.padding(top = 14.dp, bottom = 4.dp), color = p.accent,
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        is AboutItem.Row -> Row(
            Modifier.fillMaxWidth().testTag(AboutTags.row(item.key))
                .combinedClickable(onClick = {}, onLongClick = { onCopyRow(item.key, item.value) })
                .padding(vertical = 4.dp),
        ) {
            Text(item.key, Modifier.width(110.dp), color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
            Text(item.value, Modifier.weight(1f), color = p.textPrimary, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
        is AboutItem.Small -> Text(item.text, Modifier.padding(vertical = 4.dp), color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
        is AboutItem.Action -> Button(onClick = item.onClick,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag(AboutTags.action(item.label)),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (item.dark) p.surfaceSelected else p.accent,
                contentColor = if (item.dark) p.textPrimary else p.tileInk)) { Text(item.label) }
        is AboutItem.Mono -> Text(item.text(), Modifier.fillMaxWidth().padding(vertical = 4.dp).background(p.surfaceSelected).padding(8.dp),
            color = p.textPrimary, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
            maxLines = item.maxLines, overflow = TextOverflow.Ellipsis)
        is AboutItem.ButtonRow -> Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((label, onTap) in item.buttons) {
                Button(onClick = onTap, modifier = Modifier.weight(1f).testTag(AboutTags.action(label)),
                    colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = p.tileInk)) { Text(label, maxLines = 1) }
            }
        }
        is AboutItem.PermRow -> Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (b in item.buttons) {
                val done = b.granted == true
                Button(onClick = b.onClick, modifier = Modifier.weight(1f).heightIn(min = 64.dp).testTag(AboutTags.perm(b.label)),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (done) p.surfaceSelected else p.accent,
                        contentColor = if (done) p.textSecondary else p.tileInk)) {
                    Text(b.text, fontSize = 12.sp, maxLines = 3, textAlign = TextAlign.Center)
                }
            }
        }
        is AboutItem.Toggle -> {
            var on by remember { mutableStateOf(item.checked) }
            Row(Modifier.fillMaxWidth().testTag(AboutTags.TOGGLE)
                .toggleable(value = on, role = Role.Switch, onValueChange = { on = it; item.onChange(it) })
                .padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(item.label, Modifier.weight(1f), color = p.textPrimary, style = MaterialTheme.typography.bodyLarge)
                Switch(checked = on, onCheckedChange = null)
            }
        }
    }
}
