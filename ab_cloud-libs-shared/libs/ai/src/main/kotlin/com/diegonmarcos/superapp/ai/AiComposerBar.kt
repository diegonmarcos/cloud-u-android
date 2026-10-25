package com.diegonmarcos.superapp.ai

import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The glyph a control is drawn with (Cloud Code draws its icon font's; the Compose page keeps to plain text, so no icon set is needed). */
internal fun controlGlyph(id: String): String = when (id) {
    "plus" -> "+"
    "task" -> "✔"
    "agent" -> "🤖"
    "model" -> "✨"
    "effort" -> "🎚"
    "mode" -> "⚡"
    "mcp" -> "🔌"
    "more" -> "⋮"
    else -> "•"
}

internal const val BAR_PREFS = "ai_composer"

/** The bar's collapsed state of one app, from its own SharedPreferences. */
internal fun loadCollapsed(ctx: Context, design: AiDesign, app: String): Boolean =
    ctx.getSharedPreferences(BAR_PREFS, Context.MODE_PRIVATE).getBoolean(design.collapseKey(app), design.collapsedByDefault)

/**
 * The composer's config bar (Cloud Code's, shared): the controls [decl] enables, always in the design's order
 * (+) · Task · Agent · Model · Effort · Perms · MCP · (More). A selected task greys Agent and Model: they show the task's
 * mapped agent and model and open nothing until the task is cleared. The chevron at the end collapses the whole bar to one compact
 * chip (the active task, else the model); the state is kept per app. [onCatalogue] adds the host's own model page to the Model
 * picker; [onAttach] answers the (+) menu's Attach items (files, images, notes, Drive: the app's pickers). The controls scroll sideways
 * only when they do not fit; the bar sits in a column, never inside another scroller.
 */
@Composable
fun AiComposerBar(
    chat: AiChatState,
    theme: AiTheme,
    design: AiDesign,
    decl: AiComposerDecl,
    modifier: Modifier = Modifier,
    onCatalogue: (() -> Unit)? = null,
    onAttach: ((String) -> Unit)? = null,
) {
    val shown = design.ordered(decl.controls)
    if (shown.isEmpty()) return
    val ctx = LocalContext.current
    LaunchedEffect(decl.app) { chat.collapsed = loadCollapsed(ctx, design, decl.app) }
    LaunchedEffect(Unit) {
        if (chat.models.isEmpty() && shown.any { it.id == "model" }) chat.models = withContext(Dispatchers.IO) { runCatching { chat.host().models() }.getOrDefault(emptyList()) }
    }
    fun toggle() {
        chat.collapsed = !chat.collapsed
        ctx.getSharedPreferences(BAR_PREFS, Context.MODE_PRIVATE).edit().putBoolean(design.collapseKey(decl.app), chat.collapsed).apply()
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (chat.collapsed) {
            CompactChip(chat, theme, design) { toggle() }
            Box(Modifier.weight(1f))
        } else {
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(design.metric("ctl_gap").dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                shown.forEach { ctl ->
                    when (ctl.id) {
                        "plus" -> PlusControl(chat, theme, design, ctl, onAttach)
                        "task" -> TaskControl(chat, theme, design, decl, ctl)
                        "agent" -> AgentControl(chat, theme, design, ctl)
                        "model" -> ModelControl(chat, theme, design, ctl, onCatalogue)
                        "effort" -> EffortControl(chat, theme, design, ctl)
                        "mode" -> ModeControl(chat, theme, design, ctl)
                        "mcp" -> McpControl(chat, theme, design, ctl)
                        "more" -> MoreControl(chat, theme, design, ctl)
                    }
                }
            }
        }
        Box(
            Modifier.size(design.metric("bar_toggle_size").dp).clickable { toggle() }.testTag(AiTags.BAR_TOGGLE),
            contentAlignment = Alignment.Center,
        ) { Text(if (chat.collapsed) "▴" else "▾", color = theme.colors.text2, fontSize = design.metric("ctl_icon").sp) }
    }
}

/** The collapsed bar: one chip with the active task, else the model. */
@Composable
private fun CompactChip(chat: AiChatState, theme: AiTheme, design: AiDesign, onClick: () -> Unit) {
    val shape = RoundedCornerShape(design.metric("ctl_radius").dp)
    val label = chat.selectedTask?.label ?: chat.models.firstOrNull { it.id == chat.model }?.name ?: chat.model
    Row(
        Modifier.height(design.metric("ctl_height").dp).clip(shape).border(theme.metrics.hairline, theme.colors.tileBorder, shape)
            .clickable(onClick = onClick).testTag(AiTags.BAR_COMPACT).padding(horizontal = design.metric("ctl_pad_h").dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(design.metric("ctl_gap").dp),
    ) {
        Text(controlGlyph(if (chat.selectedTask != null) "task" else "model"), fontSize = design.metric("ctl_icon").sp, color = theme.colors.text)
        Text(label.substringAfterLast('/'), color = theme.colors.text, fontSize = design.metric("compact_font").sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = theme.metrics.modelMaxWidth))
    }
}

/** One chip of the row with its menu. [menu] gets a `close`. A disabled chip is greyed and opens nothing. */
@Composable
private fun Ctl(
    ctl: AiControl, theme: AiTheme, design: AiDesign, label: String, enabled: Boolean = true,
    menu: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(design.metric("ctl_radius").dp)
    val font = design.metric("ctl_font").sp
    Box {
        Row(
            Modifier.height(design.metric("ctl_height").dp)
                .alpha(if (enabled) 1f else design.metric("ctl_disabled_alpha"))
                .clip(shape).border(theme.metrics.hairline, theme.colors.tileBorder, shape)
                .clickable(enabled = enabled) { open = true }.testTag(AiTags.ctl(ctl.id))
                .padding(horizontal = design.metric("ctl_pad_h").dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(design.metric("ctl_gap").dp),
        ) {
            Text(controlGlyph(ctl.id), fontSize = design.metric("ctl_icon").sp, color = theme.colors.text)
            if (label.isNotEmpty()) Text(label, color = theme.colors.text, fontSize = font, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = theme.metrics.modelMaxWidth))
        }
        DropdownMenu(expanded = open && enabled, onDismissRequest = { open = false }) { menu { open = false } }
    }
}

@Composable
private fun PlusControl(chat: AiChatState, theme: AiTheme, design: AiDesign, ctl: AiControl, onAttach: ((String) -> Unit)?) {
    val t = theme.type
    Ctl(ctl, theme, design, "") { close ->
        if (onAttach != null) design.attachKinds.forEach { (id, label) ->
            DropdownMenuItem(text = { Text(label, style = t.style(t.small)) }, onClick = { close(); onAttach(id) }, modifier = Modifier.testTag(AiTags.attach(id)))
        }
        DropdownMenuItem(text = { Text(design.newChatLabel, style = t.style(t.small, FontWeight.SemiBold)) }, onClick = { chat.fresh(); close() }, modifier = Modifier.testTag(AiTags.NEW_CHAT))
    }
}

@Composable
private fun TaskControl(chat: AiChatState, theme: AiTheme, design: AiDesign, decl: AiComposerDecl, ctl: AiControl) {
    val t = theme.type
    Ctl(ctl, theme, design, chat.selectedTask?.label ?: ctl.label, enabled = chat.tasks.isNotEmpty()) { close ->
        if (!decl.taskRequired) DropdownMenuItem(
            text = { Text(design.noTask, style = t.style(t.small), color = if (!chat.locked) theme.colors.accent else theme.colors.text) },
            onClick = { chat.chooseTask(""); close() }, modifier = Modifier.testTag(AiTags.TASK_NONE),
        )
        chat.tasks.forEach { task ->
            DropdownMenuItem(
                text = {
                    Column {
                        Text(task.label, style = t.style(t.small, FontWeight.SemiBold), color = if (task.id == chat.task) theme.colors.accent else theme.colors.text)
                        Text(task.model.substringAfterLast('/'), style = t.style(t.small), color = theme.colors.text2)
                    }
                },
                onClick = { chat.chooseTask(task.id); close() }, modifier = Modifier.testTag(AiTags.task(task.id)),
            )
        }
    }
}

@Composable
private fun AgentControl(chat: AiChatState, theme: AiTheme, design: AiDesign, ctl: AiControl) {
    val agents = remember { chat.host().agents() }
    // A host with no agent gateway (Cloud Tools) still shows the greyed chip of the agent a task maps to.
    if (agents.isEmpty() && !chat.locked) return
    val c = theme.colors
    val t = theme.type
    val label = agents.firstOrNull { it.id == chat.agent }?.label ?: design.extraAgents.firstOrNull { it.id == chat.agent }?.label ?: chat.agent.ifEmpty { theme.strings.agentNone }
    Ctl(ctl, theme, design, label, enabled = !chat.locked) { close ->
        DropdownMenuItem(text = { Text(theme.strings.agentNone, style = t.style(t.small)) },
            onClick = { chat.chooseAgent(""); close() }, modifier = Modifier.testTag(AiTags.agent("none")))
        agents.forEach { a ->
            DropdownMenuItem(
                text = {
                    Column {
                        Text(a.label, style = t.style(t.small, FontWeight.SemiBold), color = if (a.id == chat.agent) c.accent else c.text)
                        Text(a.sub, style = t.style(t.small), color = c.text2)
                    }
                },
                onClick = { chat.chooseAgent(a.id); close() }, modifier = Modifier.testTag(AiTags.agent(a.id)),
            )
        }
    }
}

@Composable
private fun ModelControl(chat: AiChatState, theme: AiTheme, design: AiDesign, ctl: AiControl, onCatalogue: (() -> Unit)?) {
    val c = theme.colors
    val s = theme.strings
    val t = theme.type
    val byTab = remember(chat.models) { design.byTab(chat.models) }
    val current = chat.models.firstOrNull { it.id == chat.model }
    var tab by remember(chat.model) { mutableStateOf(current?.let { design.tabOf(it) } ?: design.tabs.first().id) }
    Ctl(ctl, theme, design, (current?.name ?: chat.model).substringAfterLast('/'), enabled = !chat.locked) { close ->
        if (onCatalogue != null) DropdownMenuItem(
            text = { Text(s.catalogue, style = t.style(t.small, FontWeight.SemiBold), color = c.accent) },
            onClick = { close(); onCatalogue() }, modifier = Modifier.testTag(AiTags.MODEL_CATALOGUE),
        )
        DropdownMenuItem(
            text = { Text(s.webSearch, style = t.style(t.small, FontWeight.SemiBold)) },
            trailingIcon = {
                Switch(chat.web, { chat.toggleWeb() }, Modifier.testTag(AiTags.WEB),
                    colors = SwitchDefaults.colors(checkedTrackColor = c.accent, uncheckedTrackColor = c.field))
            },
            onClick = { chat.toggleWeb() },
        )
        // The tabs: Frontier · Code · Search · Medium · Small.
        Row(Modifier.fillMaxWidth().padding(horizontal = theme.metrics.gap), horizontalArrangement = Arrangement.spacedBy(theme.metrics.small)) {
            design.tabs.forEach { tb ->
                Text(
                    tb.label, color = if (tb.id == tab) c.accent else c.text2,
                    style = t.style(t.small, if (tb.id == tab) FontWeight.Bold else FontWeight.Medium),
                    modifier = Modifier.clickable { tab = tb.id }.padding(vertical = theme.metrics.gap, horizontal = theme.metrics.small).testTag(AiTags.modelTab(tb.id)),
                )
            }
        }
        val rows = byTab[tab].orEmpty()
        if (chat.models.isEmpty()) DropdownMenuItem(text = { Text(s.modelsUnavailable, style = t.style(t.small)) }, onClick = { close() })
        else if (rows.isEmpty()) DropdownMenuItem(text = { Text(s.noModelsInTab, style = t.style(t.small), color = c.text2) }, onClick = {})
        rows.forEach { mod ->
            DropdownMenuItem(
                text = {
                    Text(mod.name + (if (mod.nativeWeb) " · " + s.webNative else "") + (if (mod.free) " · " + s.free else ""),
                        style = t.style(t.small), color = if (mod.id == chat.model) c.accent else c.text)
                },
                onClick = { chat.pick(mod.id); close() }, modifier = Modifier.testTag(AiTags.modelItem(mod.id)),
            )
        }
    }
}

@Composable
private fun EffortControl(chat: AiChatState, theme: AiTheme, design: AiDesign, ctl: AiControl) {
    val t = theme.type
    val id = chat.effort.ifEmpty { design.defaultEffort }
    Ctl(ctl, theme, design, if (id == design.defaultEffort) ctl.label else design.effort(id)?.label ?: ctl.label) { close ->
        design.efforts.forEach { e ->
            DropdownMenuItem(
                text = { Text(e.label, style = t.style(t.small, FontWeight.SemiBold), color = if (e.id == id) theme.colors.accent else theme.colors.text) },
                onClick = { chat.chooseEffort(e); close() }, modifier = Modifier.testTag(AiTags.effort(e.id)),
            )
        }
    }
}

@Composable
private fun ModeControl(chat: AiChatState, theme: AiTheme, design: AiDesign, ctl: AiControl) {
    val t = theme.type
    val id = chat.mode.ifEmpty { design.defaultMode }
    LaunchedEffect(Unit) { if (chat.mode.isEmpty()) chat.chooseMode(design.defaultMode) }
    Ctl(ctl, theme, design, design.mode(id)?.short ?: ctl.label) { close ->
        design.modes.forEach { m ->
            DropdownMenuItem(
                text = { Text(m.label, style = t.style(t.small, FontWeight.SemiBold), color = if (m.id == id) theme.colors.accent else theme.colors.text) },
                onClick = { chat.chooseMode(m.id); close() }, modifier = Modifier.testTag(AiTags.mode(m.id)),
            )
        }
    }
}

@Composable
private fun McpControl(chat: AiChatState, theme: AiTheme, design: AiDesign, ctl: AiControl) {
    val c = theme.colors
    val t = theme.type
    LaunchedEffect(Unit) {
        if (chat.mcpServers.isEmpty()) chat.mcpServers = withContext(Dispatchers.IO) { runCatching { chat.host().mcpServers() }.getOrDefault(emptyList()) }
    }
    Ctl(ctl, theme, design, if (chat.mcpOn.isEmpty()) ctl.label else "${ctl.label} ${chat.mcpOn.size}") { _ ->
        if (chat.mcpServers.isEmpty()) DropdownMenuItem(text = { Text(theme.strings.noMcp, style = t.style(t.small), color = c.text2) }, onClick = {})
        chat.mcpServers.forEach { sv ->
            DropdownMenuItem(
                text = { Text(sv.name + (sv.tools?.let { " · $it" } ?: ""), style = t.style(t.small), color = c.text) },
                trailingIcon = {
                    Switch(sv.name in chat.mcpOn, { chat.toggleMcp(sv.name) },
                        colors = SwitchDefaults.colors(checkedTrackColor = c.accent, uncheckedTrackColor = c.field))
                },
                onClick = { chat.toggleMcp(sv.name) }, modifier = Modifier.testTag(AiTags.mcp(sv.name)),
            )
        }
    }
}

/** The More sheet: the declared groups under their headers; an item either acts at once or opens a dialog with its text. */
@Composable
private fun MoreControl(chat: AiChatState, theme: AiTheme, design: AiDesign, ctl: AiControl) {
    val c = theme.colors
    val t = theme.type
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<Pair<String, String>?>(null) }
    var live by remember { mutableStateOf<Map<String, Boolean>?>(null) }
    Ctl(ctl, theme, design, "") { close ->
        LaunchedEffect(Unit) { live = withContext(Dispatchers.IO) { runCatching { chat.host().plugins() }.getOrDefault(emptyMap()) } }
        design.moreGroups.forEach { g ->
            Text(g.label, color = c.text2, style = t.style(t.label, FontWeight.Bold), modifier = Modifier.padding(horizontal = theme.metrics.gutter, vertical = theme.metrics.small).testTag("ai_more_group_${g.id}"))
            g.items.forEach { item ->
                if (item.kind == "plugin") {
                    val listed = live?.containsKey(item.plugin) == true
                    val on = chat.pluginChoice[item.id] ?: (live?.get(item.plugin) == true)
                    DropdownMenuItem(
                        text = { Text(item.label + if (listed) "" else " · " + theme.strings.notListed, style = t.style(t.small), color = if (listed) c.text else c.text2) },
                        trailingIcon = { Switch(on, { chat.setPlugin(item, it) }, enabled = listed, colors = SwitchDefaults.colors(checkedTrackColor = c.accent, uncheckedTrackColor = c.field)) },
                        enabled = listed, onClick = { chat.setPlugin(item, !on) }, modifier = Modifier.testTag(AiTags.morePlugin(item.id)),
                    )
                } else DropdownMenuItem(
                    text = { Text(item.label, style = t.style(t.small), color = c.text) },
                    onClick = {
                        close()
                        when (item.kind) {
                            "usage" -> dialog = item.label to chat.host().usage(chat.session.id).joinToString("\n") { "${it.label}: ${it.value}" }.ifBlank { theme.strings.noUsage }
                            "export" -> dialog = item.label to AiExport.markdown(chat.session)
                            "system" -> dialog = item.label to chat.host().systemPrompt().ifBlank { theme.strings.noSystem }
                            "info" -> dialog = item.label to item.text
                            "prompt" -> chat.draft = item.prompt
                            "undo" -> chat.undoLast()
                            "retry" -> chat.undoLast()?.let { ask -> scope.launch { chat.send(ask) } }
                        }
                        Unit
                    },
                    modifier = Modifier.testTag(AiTags.more(item.id)),
                )
            }
        }
    }
    dialog?.let { (title, body) ->
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(title) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()).testTag(AiTags.MORE_DIALOG)) { Text(body) } },
            confirmButton = { TextButton({ dialog = null }) { Text(theme.strings.close) } },
        )
    }
}
