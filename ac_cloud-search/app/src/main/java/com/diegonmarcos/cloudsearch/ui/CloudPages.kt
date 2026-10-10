package com.diegonmarcos.cloudsearch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.diegonmarcos.cloudsearch.BuildConfig
import com.diegonmarcos.cloudsearch.R
import com.diegonmarcos.cloudsearch.core.CloudConfig
import com.diegonmarcos.cloudsearch.core.agents.MailBody
import com.diegonmarcos.cloudsearch.core.agents.MailHeader
import com.diegonmarcos.cloudsearch.data.Browser
import com.diegonmarcos.cloudsearch.data.Launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * #913 the fleet searches this app can do honestly: Apps, Messages and Code. #913b they were the Cloud page; they are
 * now pages of Chat's strip, beside Search (SearchShell.ChatSection draws the strip).
 */
object CloudTags {
    const val QUERY = "cloud_query"
    const val SEARCH = "cloud_search"
    const val STATUS = "cloud_status"
    fun app(id: String) = "cloud_app_$id"
    fun message(id: String) = "cloud_message_$id"
}

private val cloudPadding = PaddingValues(start = Metrics.gutter, end = Metrics.gutter, top = Metrics.small, bottom = Metrics.contentBottom)

/** One of Chat's fleet-search pages: [page] is apps, messages or code (build.json::ui.sections[chat].pages). */
@Composable
fun CloudPage(state: SearchState, page: String) {
    val cloud = state.cfg.cloud
    Box(Modifier.fillMaxSize().testTag(Tags.page("cloud_$page"))) {
        if (cloud == null) Text(stringResource(R.string.cloud_none), Modifier.padding(Metrics.gutter), color = LocalGlass.current.text2)
        else when (page) {
            "messages" -> MessagesPage(state, cloud)
            "code" -> CodePage(state, cloud)
            else -> AppsPage(state, cloud)
        }
    }
}

@Composable
private fun AppsPage(state: SearchState, cloud: CloudConfig) {
    val g = LocalGlass.current
    val ctx = LocalContext.current
    val all = remember { CloudConfig.fleetApps(String(android.util.Base64.decode(BuildConfig.FLEET_APPS_B64, android.util.Base64.DEFAULT), Charsets.UTF_8)) }
    var q by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val shown = cloud.apps(all, q)
    LazyColumn(contentPadding = cloudPadding, verticalArrangement = Arrangement.spacedBy(Metrics.small)) {
        item { GlassField(q, { q = it }, stringResource(R.string.cloud_apps_placeholder), CloudTags.QUERY) }
        if (note.isNotBlank()) item { Text(note, color = g.negative, style = Type.style(Type.tiny)) }
        items(shown, key = { it.id }) { a ->
            val installed = remember(a.pkg) { ctx.packageManager.getLaunchIntentForPackage(a.pkg) != null }
            GlassCard(Modifier.testTag(CloudTags.app(a.id)), onClick = {
                note = if (Launch.app(ctx, a.pkg)) "" else ctx.getString(R.string.cloud_not_installed, a.label)
            }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                    Text(a.label, Modifier.weight(1f), color = g.text, style = Type.style(Type.body, FontWeight.SemiBold))
                    Badge(stringResource(if (installed) R.string.cloud_installed else R.string.cloud_missing), if (installed) g.positive else g.text2)
                }
                Text(a.pkg, color = g.text2, style = Type.style(Type.tiny))
            }
        }
    }
}

@Composable
private fun MessagesPage(state: SearchState, cloud: CloudConfig) {
    val g = LocalGlass.current
    val svc = state.services.agents
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf<List<MailHeader>>(emptyList()) }
    var openId by remember { mutableStateOf<String?>(null) }
    var body by remember { mutableStateOf<MailBody?>(null) }
    val ctx = LocalContext.current
    fun search() {
        if (svc == null) { status = ctx.getString(R.string.agents_none); return }
        status = ctx.getString(R.string.agents_running); openId = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { svc.mail.messages("", q, cloud.messagesSince(System.currentTimeMillis()), cloud.messagesLimit) } }
            rows = r.getOrDefault(emptyList())
            status = r.fold({ ctx.getString(R.string.cloud_messages_count, it.size) }, { it.message ?: it.javaClass.simpleName })
        }
    }
    LazyColumn(contentPadding = cloudPadding, verticalArrangement = Arrangement.spacedBy(Metrics.small)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap), verticalAlignment = Alignment.CenterVertically) {
                GlassField(q, { q = it }, stringResource(R.string.cloud_messages_placeholder), CloudTags.QUERY, Modifier.weight(1f), onSearch = { search() })
                Chip(stringResource(R.string.cloud_search), CloudTags.SEARCH, accent = true) { search() }
            }
            Text(status, Modifier.testTag(CloudTags.STATUS), color = g.text2, style = Type.style(Type.tiny))
        }
        items(rows, key = { it.accountId + "/" + it.id }) { h ->
            GlassCard(Modifier.testTag(CloudTags.message(h.id)), onClick = {
                if (openId == h.id) openId = null
                else if (svc != null) {
                    openId = h.id; body = null
                    scope.launch { body = withContext(Dispatchers.IO) { runCatching { svc.mail.body(h.accountId, h.id) }.getOrNull() } }
                }
            }) {
                Text(h.subject.ifBlank { "—" }, color = g.text, style = Type.style(Type.body, FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(h.fromName.ifBlank { h.fromEmail } + " · " + h.receivedAt.take(16).replace('T', ' '), color = g.text2, style = Type.style(Type.tiny), maxLines = 1)
                if (openId == h.id) Text(body?.text?.take(1500) ?: stringResource(R.string.agents_running), color = g.text, style = Type.style(Type.small))
            }
        }
    }
}

@Composable
private fun CodePage(state: SearchState, cloud: CloudConfig) {
    val g = LocalGlass.current
    val ctx = LocalContext.current
    var q by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val pkg = remember { CloudConfig.fleetApps(String(android.util.Base64.decode(BuildConfig.FLEET_APPS_B64, android.util.Base64.DEFAULT), Charsets.UTF_8)).firstOrNull { it.id == cloud.codeOpenFleet }?.pkg }
    LazyColumn(contentPadding = cloudPadding, verticalArrangement = Arrangement.spacedBy(Metrics.small)) {
        item {
            GlassCard {
                Text(stringResource(R.string.cloud_code_blurb, cloud.codeOwner), color = g.text2, style = Type.style(Type.small))
                GlassField(q, { q = it }, stringResource(R.string.cloud_code_placeholder), CloudTags.QUERY, onSearch = { cloud.codeUrl(q)?.let { Browser.open(ctx, it) } })
                Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                    Chip(stringResource(R.string.cloud_code_search), CloudTags.SEARCH, accent = true, icon = R.drawable.ph_arrow_square_out) { cloud.codeUrl(q)?.let { Browser.open(ctx, it) } }
                    Chip(stringResource(R.string.cloud_code_open), CloudTags.app("code")) {
                        note = if (pkg != null && Launch.app(ctx, pkg)) "" else ctx.getString(R.string.cloud_not_installed, cloud.codeOpenFleet)
                    }
                }
                if (note.isNotBlank()) Text(note, color = g.negative, style = Type.style(Type.tiny))
            }
        }
    }
}
