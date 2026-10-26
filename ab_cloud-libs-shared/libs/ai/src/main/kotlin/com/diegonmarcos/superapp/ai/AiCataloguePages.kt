package com.diegonmarcos.superapp.ai

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import com.diegonmarcos.superapp.modelcatalogue.CatalogueFetch
import com.diegonmarcos.superapp.modelcatalogue.FileCatalogueStore
import com.diegonmarcos.superapp.modelcatalogue.ModelCatalogue
import com.diegonmarcos.superapp.modelcatalogue.ModelCatalogueRepository
import com.diegonmarcos.superapp.modelcatalogue.ModelCatalogueRepository.Origin
import com.diegonmarcos.superapp.modelcataloguepage.CatalogueTheme
import com.diegonmarcos.superapp.modelcataloguepage.CatalogueWords
import com.diegonmarcos.superapp.modelcataloguepage.ModelCataloguePage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** The public OpenRouter catalogues, read with no key: the body of a 2xx, else null (the page then draws the bundled snapshot). */
val AiPublicFetch = CatalogueFetch { url ->
    runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        try { if (c.responseCode in 200..299) c.inputStream.bufferedReader().use { it.readText() } else null } finally { c.disconnect() }
    }.getOrNull()
}

/**
 * The Model Catalogue as a full page (the composer's Model button): the subpages Frontier · Core · Specialist · Medium · Small
 * of the shared data (ai-catalogue.json). Core is libs:model-catalogue's A Text / B Search / C Audio & Speech / D Visual Media tables;
 * the others are the declared lists. A tap on a chat model picks it and returns to the chat; a model the live list does not carry
 * says so, and a phone-only row is greyed. The page is one column: a header, the tabs (sideways only when they do not fit) and
 * ONE scroller below them; it replaces the chat, never sits inside another scroller. [fetch] is the host's network door for prices.
 */
@Composable
fun AiModelCatalogue(chat: AiChatState, theme: AiTheme, fetch: CatalogueFetch = AiPublicFetch, modifier: Modifier = Modifier, onClose: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    // Close, Back and a pick end the page: the chat's own page flag, or the host's own way out when it shows the catalogue outside the chat.
    val done = { chat.page = ""; onClose?.invoke(); Unit }
    val cat = remember { runCatching { AiCatalogue.load(ctx) }.getOrNull() }
    val c = theme.colors
    val t = theme.type
    val m = theme.metrics
    val s = theme.strings
    var tab by remember { mutableStateOf("core") }
    BackHandler { done() }
    val core by produceState<CoreSource?>(null, fetch) {
        value = withContext(Dispatchers.IO) {
            val k = runCatching { ModelCatalogue.parse(ctx.assets.open(ModelCatalogue.ASSET).bufferedReader().use { it.readText() }) }.getOrNull()
            CoreSource(k, k?.let { ModelCatalogueRepository(it, fetch, FileCatalogueStore(File(ctx.filesDir, "model-catalogue")), System::currentTimeMillis) })
        }
    }
    Column(modifier.fillMaxSize().testTag(AiTags.CATALOGUE_PAGE)) {
        Header(theme, s.catalogue, AiTags.CATALOGUE_CLOSE, { done() }) {
            Text(s.webSearch, color = c.text2, style = t.style(t.small))
            Switch(chat.web, { chat.toggleWeb() }, Modifier.padding(horizontal = m.small).testTag(AiTags.WEB),
                colors = SwitchDefaults.colors(checkedTrackColor = c.accent, uncheckedTrackColor = c.field))
        }
        if (cat == null) { Text(s.modelsUnavailable, color = c.negative, style = t.style(t.small), modifier = Modifier.padding(m.gutter)); return@Column }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = m.gutter), horizontalArrangement = Arrangement.spacedBy(m.gap)) {
            cat.tabs.forEach { tb ->
                Text(tb.label, color = if (tb.id == tab) c.accent else c.text2, style = t.style(t.chatInput, if (tb.id == tab) FontWeight.Bold else FontWeight.Medium),
                    modifier = Modifier.clickable { tab = tb.id }.padding(vertical = m.gap, horizontal = m.small).testTag(AiTags.catTab(tb.id)))
            }
        }
        if (tab == "core") CorePage(chat, theme, core, done)
        else Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = m.gutter, vertical = m.gap),
            verticalArrangement = Arrangement.spacedBy(m.gap)) {
            val groups = when (tab) {
                "frontier" -> cat.frontier
                "specialist" -> cat.specialist
                else -> listOf(AiCatalogueGroup(tab, cat.tabs.first { it.id == tab }.label, cat.tier(tab)))
            }
            groups.forEach { g -> ModelGroup(chat, theme, g, done) }
        }
    }
}

/** Core's source: the bundled selection and its prices (cached, live, or the snapshot), read off the main thread. */
private class CoreSource(val catalogue: ModelCatalogue.Catalogue?, val repo: ModelCatalogueRepository?)

@Composable
private fun ColumnScope.CorePage(chat: AiChatState, theme: AiTheme, core: CoreSource?, done: () -> Unit) {
    val c = theme.colors
    val t = theme.type
    val m = theme.metrics
    val s = theme.strings
    val origins = mapOf(Origin.LIVE to "live", Origin.CACHED to "cached", Origin.STALE to "stale", Origin.SNAPSHOT to "bundled snapshot")
    val words = CatalogueWords(
        title = s.core, current = { "Current: $it" }, pricesAsOf = { date, origin -> "Prices as of $date ($origin)" }, origin = { origins.getValue(it) },
        refreshing = "refreshing…", unreadable = s.modelsUnavailable, footer = "Prices are OpenRouter's per 1M tokens; Floor is the cheapest provider.",
        notChat = "browse only", noAnthropic = "no Anthropic model", none = "none", notListed = s.notListed,
        columns = listOf("Provider", "Model", "OpenRouter Model ID", "Architecture / Params", "License", "Input", "Output (Floor)"),
    )
    val ct = CatalogueTheme(
        text = c.text, text2 = c.text2, accent = c.accent, field = c.field, card = c.tile, border = c.tileBorder, negative = c.negative,
        title = t.style(t.chat, FontWeight.Bold), groupTitle = t.style(t.chatInput, FontWeight.Bold), sectionTitle = t.style(t.small, FontWeight.SemiBold),
        body = t.style(t.small), label = t.style(t.label),
        gutter = m.gutter, small = m.small, gap = m.gap, tiny = m.tiny, cardRadius = m.cardRadius, cardPad = m.cardPad, tileRadius = m.cardRadius, hairline = m.hairline,
    )
    ModelCataloguePage(
        catalogue = core?.catalogue, repo = core?.repo, ready = core != null,
        current = chat.model, currentName = chat.models.firstOrNull { it.id == chat.model }?.name ?: chat.model,
        theme = ct, words = words, onPick = { id -> chat.pick(id); done() }, modifier = Modifier.weight(1f),
    )
}

/** One headed list of a declared tab: its models, a tap picks. */
@Composable
private fun ModelGroup(chat: AiChatState, theme: AiTheme, g: AiCatalogueGroup, done: () -> Unit) {
    val c = theme.colors
    val t = theme.type
    val m = theme.metrics
    Text(g.label, color = c.text, style = t.style(t.chatInput, FontWeight.Bold), modifier = Modifier.padding(top = m.gap))
    g.models.forEach { mod ->
        val listed = chat.models.isEmpty() || chat.models.any { it.id == mod.id }
        Column(
            Modifier.fillMaxWidth().alpha(if (mod.local) 0.45f else 1f).clickable(enabled = !mod.local) { chat.pick(mod.id); done() }
                .padding(vertical = m.small).testTag(AiTags.catRow(mod.id)),
        ) {
            Text(mod.name, color = if (mod.id == chat.model) c.accent else c.text, style = t.style(t.small, FontWeight.SemiBold))
            val sub = listOf(mod.id.takeUnless { mod.local }.orEmpty(), mod.sub, if (mod.local) theme.strings.localOnly else if (!listed) theme.strings.notListed else "")
                .filter { it.isNotBlank() }.joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, color = c.text2, style = t.style(t.label))
        }
    }
}

/**
 * The Task Catalogue as a full page (the composer's Task button): the shared groups (Big LLMs; Medium & Small LLMs) with their
 * categories, then the app's own tasks the catalogue does not list. A task the app does not offer is greyed; selecting one
 * greys Agent and Model and sets them from its mapping, then returns to the chat. One column, one scroller.
 */
@Composable
fun AiTaskCatalogue(chat: AiChatState, theme: AiTheme, decl: AiComposerDecl, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val cat = remember { runCatching { AiCatalogue.load(ctx) }.getOrNull() }
    val c = theme.colors
    val t = theme.type
    val m = theme.metrics
    val s = theme.strings
    BackHandler { chat.page = "" }
    Column(modifier.fillMaxSize().testTag(AiTags.TASK_PAGE)) {
        Header(theme, s.taskCatalogue, AiTags.TASK_CLOSE, { chat.page = "" }) {
            if (!decl.taskRequired) Text(AiDesign.load(ctx).noTask, color = if (chat.task.isEmpty()) c.accent else c.text, style = t.style(t.chatInput, FontWeight.SemiBold),
                modifier = Modifier.clickable { chat.chooseTask(""); chat.page = "" }.padding(horizontal = m.gap).testTag(AiTags.TASK_NONE))
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = m.gutter, vertical = m.gap),
            verticalArrangement = Arrangement.spacedBy(m.small)) {
            val offered = chat.tasks.map { it.id }.toSet()
            cat?.groups?.forEach { g ->
                Text(g.label, color = c.text, style = t.style(t.chat, FontWeight.Bold), modifier = Modifier.padding(top = m.gap).testTag(AiTags.taskGroup(g.id)))
                g.categories.forEach { k ->
                    Text(k.label, color = c.text2, style = t.style(t.chatInput, FontWeight.SemiBold), modifier = Modifier.padding(top = m.small))
                    k.items.forEach { item -> TaskRow(chat, theme, item.id, item.label, item.task, item.id in offered, item.note) }
                }
            }
            val known = cat?.tasks?.map { it.id }.orEmpty().toSet()
            val own = chat.tasks.filter { it.id !in known }
            if (own.isNotEmpty()) {
                Text(s.appTasks, color = c.text, style = t.style(t.chat, FontWeight.Bold), modifier = Modifier.padding(top = m.gap).testTag(AiTags.taskGroup("app")))
                own.forEach { TaskRow(chat, theme, it.id, it.label, it, true, it.note) }
            }
        }
    }
}

@Composable
private fun TaskRow(chat: AiChatState, theme: AiTheme, id: String, label: String, task: AiTask?, offered: Boolean, note: String?) {
    val c = theme.colors
    val t = theme.type
    val m = theme.metrics
    val on = offered && task != null
    Column(
        Modifier.fillMaxWidth().alpha(if (on) 1f else 0.45f).clickable(enabled = on) { chat.chooseTask(id); chat.page = "" }
            .padding(vertical = m.small).testTag(AiTags.task(id)),
    ) {
        Text(label, color = if (id == chat.task) c.accent else c.text, style = t.style(t.small, FontWeight.SemiBold))
        val sub = if (task != null) (if (offered) "" else theme.strings.taskNotOffered + " · ") + task.agent + " · " + task.model.substringAfterLast('/') else theme.strings.taskNotOffered
        Text(listOfNotNull(sub, note).filter { it.isNotBlank() }.joinToString(" · "), color = c.text2, style = t.style(t.label))
    }
}

@Composable
private fun Header(theme: AiTheme, title: String, closeTag: String, onClose: () -> Unit, extra: @Composable () -> Unit = {}) {
    val c = theme.colors
    val t = theme.type
    val m = theme.metrics
    Row(Modifier.fillMaxWidth().padding(horizontal = m.gutter, vertical = m.gap), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), color = c.text, style = t.style(t.chat, FontWeight.Bold))
        extra()
        Text(theme.strings.close, color = c.accent, style = t.style(t.chatInput, FontWeight.SemiBold),
            modifier = Modifier.clickable(onClick = onClose).padding(horizontal = m.gap).testTag(closeTag))
    }
}
