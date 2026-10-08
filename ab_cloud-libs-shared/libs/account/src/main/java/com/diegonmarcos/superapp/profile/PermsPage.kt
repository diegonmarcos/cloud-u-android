package com.diegonmarcos.superapp.profile

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.adbdebug.ShellChannel
import com.diegonmarcos.superapp.adbdebug.ShellChannels
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitSectionHeader
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Setup ▸ perms (account redesign spec 4.9): draws [PermsPlan] for the working profile.
 * Per app: granted / denied counts, every runtime permission and special grant, a Grant per row;
 * Grant all at the top runs [PermsPlan.apply] over the shell channel. No channel: the page says so and
 * offers the runbook's shell step. A NEEDS_USER row opens its Settings page; nothing pretends.
 */
object PermsPageTags {
    const val PAGE = "account:setup:perms"
    const val GRANT_ALL = "account:setup:perms:grant-all"
    const val NO_CHANNEL = "account:setup:perms:no-channel"
}

@Composable
private fun Mono(text: String, secondary: Boolean = false) {
    val p = LocalKitPalette.current
    Text(text, color = if (secondary) p.textSecondary else p.textPrimary, fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall)
}

@Composable
fun AccountPermsPage(go: (section: String, page: String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var plan by remember { mutableStateOf<PermsPlan.Plan?>(null) }
    var channel by remember { mutableStateOf<ShellChannel?>(null) }
    var busy by remember { mutableStateOf(false) }
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(tick) {
        withContext(Dispatchers.IO) {
            val ch = runCatching { ShellChannels.active(ctx) }.getOrNull()
            val profile = DeviceVault(ctx).working()?.optJSONObject("profile")
            val p = PermsPlan.plan(ctx, profile, ch)
            withContext(Dispatchers.Main) { channel = ch; plan = p }
        }
    }

    fun work(block: suspend () -> List<String>) {
        if (busy) return
        busy = true
        scope.launch {
            val out = withContext(Dispatchers.IO) { runCatching { block() }.getOrElse { listOf("✗ ${it.message}") } }
            lines = out; busy = false; tick++
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp).testTag(PermsPageTags.PAGE), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            KitSectionHeader("Setup ▸ perms", plan?.summary() ?: "reading every app's grants…")
        }
        item {
            KitCard(Modifier.fillMaxWidth()) {
                val ch = channel
                if (ch == null) {
                    Mono("No shell channel: Grant all needs one. Pair it in the runbook's shell step.", secondary = true)
                    OutlinedButton(onClick = { go("setup", "runbook") }, modifier = Modifier.fillMaxWidth().testTag(PermsPageTags.NO_CHANNEL)) {
                        Text("Open runbook ▸ shell")
                    }
                } else Mono("shell channel: ${ch.name()}", secondary = true)
                OutlinedButton(enabled = !busy && plan != null && ch != null,
                    onClick = { plan?.let { p -> work { PermsPlan.apply(ctx, p, ch).lines.map { it.text() } } } },
                    modifier = Modifier.fillMaxWidth().testTag(PermsPageTags.GRANT_ALL)) {
                    Text(if (busy) "Granting…" else "Grant all (${plan?.todo?.size ?: 0} from the profile)")
                }
                lines.forEach { Mono(it) }
            }
        }
        val p = plan
        if (p != null) {
            if (p.global.isNotEmpty()) item {
                KitCard(Modifier.fillMaxWidth()) {
                    Mono("Device")
                    p.global.forEach { ItemRow(it, busy, channel) { i -> work { listOf(PermsPlan.applyOne(ctx, i, channel).text()) } } }
                }
            }
            items(p.apps, key = { it.pkg }) { a ->
                KitCard(Modifier.fillMaxWidth()) {
                    Mono(a.label + if (a.fleet) "  · fleet" else "")
                    Mono("${a.pkg}  ·  ${a.granted} granted · ${a.denied} denied", secondary = true)
                    a.items.forEach { ItemRow(it, busy, channel) { i -> work { listOf(PermsPlan.applyOne(ctx, i, channel).text()) } } }
                }
            }
        }
    }
}

@Composable
private fun ItemRow(item: PermsPlan.Item, busy: Boolean, channel: ShellChannel?, grant: (PermsPlan.Item) -> Unit) {
    val ctx = LocalContext.current
    val mark = when (item.granted) { true -> "✓"; false -> "·"; null -> "?" }
    val want = if (item.wanted) "  (profile)" else ""
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) { Mono("$mark ${item.kind.id}  ${item.name}$want", secondary = item.granted == true) }
        if (item.granted != true) {
            if (item.kind == PermsPlan.Kind.USER) {
                TextButton(onClick = {
                    runCatching { ctx.startActivity(PermsPlan.settingsIntent(item)) }
                        .onFailure { Toast.makeText(ctx, "No settings screen", Toast.LENGTH_SHORT).show() }
                }) { Text("needs the user ↗") }
            } else {
                TextButton(enabled = !busy && (channel != null || item.kind == PermsPlan.Kind.VERIFIER), onClick = { grant(item) }) { Text("Grant") }
            }
        }
    }
}
