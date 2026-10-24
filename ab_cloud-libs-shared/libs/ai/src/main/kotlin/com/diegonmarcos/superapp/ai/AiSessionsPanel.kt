package com.diegonmarcos.superapp.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import java.time.ZoneId

/**
 * The sessions list as a side-drawer body (Gemini-style): a New chat row, then the stored sessions grouped Today /
 * Previous 7 days / Older, newest first. A tap opens one in the chat and calls [onPicked] (the host closes its drawer);
 * the row's menu renames it (a dialog) or deletes it. Over [AiChatState] only, so any host's drawer can hold it.
 */
@Composable
fun AiSessionsPanel(chat: AiChatState, theme: AiTheme, modifier: Modifier = Modifier, onPicked: () -> Unit = {}) {
    val c = theme.colors
    val m = theme.metrics
    val t = theme.type
    var tick by remember { mutableIntStateOf(0) }
    var renaming by remember { mutableStateOf<AiSession?>(null) }
    val all = remember(tick, chat.session.id, chat.session.title, chat.session.messages.size, chat.sending) { chat.host().sessions() }
    val groups = remember(all) { AiSessions.group(all, System.currentTimeMillis(), ZoneId.systemDefault()) }
    Column(modifier.fillMaxWidth().testTag(AiTags.DRAWER)) {
        Row(
            Modifier.fillMaxWidth().padding(m.gap).clip(RoundedCornerShape(m.chatBarRadius)).background(c.field)
                .clickable { chat.fresh(); onPicked() }.padding(horizontal = m.gutter, vertical = m.cardPad).testTag(AiTags.DRAWER_NEW),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            theme.icon(AiIcons.PLUS_CIRCLE, m.icon, c.accent, null)
            Text(theme.strings.newChat, color = c.text, style = t.style(t.chat, FontWeight.Medium), modifier = Modifier.padding(start = m.gap))
        }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (all.isEmpty()) Text(theme.strings.noSessions, color = c.text2, style = t.style(t.small), modifier = Modifier.padding(m.gutter))
            for ((bucket, list) in groups) {
                Text(
                    when (bucket) { AiBucket.TODAY -> theme.strings.today; AiBucket.PREVIOUS_7_DAYS -> theme.strings.previous7Days; AiBucket.OLDER -> theme.strings.older },
                    color = c.text2, style = t.style(t.label, FontWeight.Bold),
                    modifier = Modifier.padding(start = m.gutter, top = m.gutter, bottom = m.small),
                )
                for (s in list) SessionRow(s, s.id == chat.session.id, theme,
                    onOpen = { chat.open(s); onPicked() },
                    onRename = { renaming = s },
                    onDelete = { chat.remove(s.id); tick++ })
            }
        }
    }
    renaming?.let { s ->
        var text by remember(s.id) { mutableStateOf(s.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(theme.strings.rename) },
            text = { OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag(AiTags.RENAME_FIELD)) },
            confirmButton = { TextButton({ chat.rename(s.id, text); renaming = null; tick++ }, Modifier.testTag(AiTags.RENAME_SAVE)) { Text(theme.strings.save) } },
            dismissButton = { TextButton({ renaming = null }) { Text(theme.strings.cancel) } },
        )
    }
}

@Composable
private fun SessionRow(s: AiSession, current: Boolean, theme: AiTheme, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    val c = theme.colors
    val m = theme.metrics
    val t = theme.type
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().background(if (current) c.field else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onOpen).padding(start = m.gutter).testTag(AiTags.session(s.id)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            s.title.ifBlank { theme.strings.untitled }, color = if (current) c.accent else c.text, style = t.style(t.chat),
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(vertical = m.cardPad),
        )
        Box {
            Text("⋮", color = c.text2, style = t.style(t.greetingSub), modifier = Modifier.clickable { menu = true }.padding(horizontal = m.gutter, vertical = m.cardPad))
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(theme.strings.rename) }, onClick = { menu = false; onRename() }, modifier = Modifier.testTag(AiTags.rename(s.id)))
                DropdownMenuItem(text = { Text(theme.strings.delete, color = c.negative) }, onClick = { menu = false; onDelete() }, modifier = Modifier.testTag(AiTags.delete(s.id)))
            }
        }
    }
}
