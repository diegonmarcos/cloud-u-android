package com.diegonmarcos.cloudaccount.hub

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudaccount.autofill.AutofillImportBody
import com.diegonmarcos.superapp.profile.ProfilePrefs
import com.diegonmarcos.superapp.settings.AccountVault
import com.diegonmarcos.superapp.uikit.KitAction
import com.diegonmarcos.superapp.uikit.KitActionBar
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitConfirmDialog
import com.diegonmarcos.superapp.uikit.KitDensity
import com.diegonmarcos.superapp.uikit.KitFingerprint
import com.diegonmarcos.superapp.uikit.KitHero
import com.diegonmarcos.superapp.uikit.KitListRow
import com.diegonmarcos.superapp.uikit.KitSectionHeader
import com.diegonmarcos.superapp.uikit.KitStatTile
import com.diegonmarcos.superapp.uikit.KitState
import com.diegonmarcos.superapp.uikit.KitStatePill
import com.diegonmarcos.superapp.uikit.KitStatusBanner
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The device auth every reveal and copy goes through. The default refuses: no host, no reveal. */
val LocalDeviceAuth = staticCompositionLocalOf<DeviceAuth> { DeviceAuth { _, done -> done(false) } }

/** Reads a picked file's text; MainActivity's document picker. */
typealias PickText = ((String) -> Unit) -> Unit

object HubTags {
    const val HOME = "hub:home"
    const val CONNECTIONS = "hub:connections"
    const val SETUP_ALL = "hub:setup-all"
    const val TOKENS_IMPORT = "hub:tokens:import"
    const val TOKENS_IMPORT_ALL = "hub:tokens:import-all"
    fun cell(p: String, m: AuthMethod) = "hub:cell:$p:${m.name}"
    fun secret(path: String) = "hub:secret:$path"
    fun reveal(path: String) = "hub:reveal:$path"
    fun provider(id: String) = "hub:provider:$id"
    fun app(id: String) = "hub:app:$id"
}

private fun stateOf(s: CellStatus) = when (s) {
    CellStatus.CONNECTED -> KitState.OK; CellStatus.EXPIRED -> KitState.BAD; CellStatus.MISSING -> KitState.WARN; CellStatus.NOT_USED -> KitState.IDLE
}

private fun stateOf(r: SetupResult?) = when { r == null -> KitState.IDLE; r.ok -> KitState.OK; else -> KitState.BAD }

@Composable
private fun Scroll(tag: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = KitDensity.large, vertical = KitDensity.medium)
        .testTag(tag), verticalArrangement = Arrangement.spacedBy(KitDensity.small)) { content() }
}

@Composable
private fun Caption(text: String) = Text(text, color = LocalKitPalette.current.textSecondary, fontSize = KitDensity.caption)

// ── one secret: masked, reveal / copy behind device auth, edit / rotate / delete ─────────────────────────────

@Composable
fun SecretLine(e: SecretEntry, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val gate = RevealGate(LocalDeviceAuth.current)
    var shown by remember(e.path) { mutableStateOf<String?>(null) }
    var note by remember(e.path) { mutableStateOf("") }
    var editing by remember(e.path) { mutableStateOf(false) }
    var confirmDelete by remember(e.path) { mutableStateOf(false) }
    val fp = remember(e.path, onChanged) { Providers.mask(HubAndroid.value(ctx, e.path).orEmpty()) }
    val exp = e.expiresAt?.let { (if (e.expired) "expired " else "expires ") + it.toString().take(10) }.orEmpty()
    KitListRow(e.path, Modifier.testTag(HubTags.secret(e.path)), tag = e.path,
        secondary = listOf(e.method.label, exp, note).filter { it.isNotBlank() }.joinToString(" · "),
        pill = (if (e.expired) "expired" else "stored") to (if (e.expired) KitState.BAD else KitState.OK),
        onClick = {
            if (shown != null) shown = null
            else gate.reveal("Show ${e.name}", { HubAndroid.value(ctx, e.path) }, { shown = it }, { note = "not shown: device auth refused" })
        })
    val v = shown
    if (v != null) Text(v, color = LocalKitPalette.current.textPrimary, fontSize = KitDensity.body, modifier = Modifier.testTag(HubTags.reveal(e.path)))
    else KitFingerprint(fp, tag = "fp:${e.path}")
    KitActionBar(listOf(
        KitAction(if (v == null) "Reveal" else "Hide", "hub:secret:reveal:${e.path}") {
            if (v != null) shown = null
            else gate.reveal("Show ${e.name}", { HubAndroid.value(ctx, e.path) }, { shown = it }, { note = "not shown: device auth refused" })
        },
        KitAction("Copy", "hub:secret:copy:${e.path}") {
            gate.reveal("Copy ${e.name}", { HubAndroid.value(ctx, e.path) },
                { SecretClipboard.copy(ctx, it); note = "copied · clears in ${SecretClipboard.CLEAR_AFTER_MS / 1000}s" },
                { note = "not copied: device auth refused" })
        },
        KitAction("Edit / rotate", "hub:secret:edit:${e.path}") { editing = true },
        KitAction("Delete", "hub:secret:delete:${e.path}") { confirmDelete = true },
    ), filledFirst = false)
    if (editing) {
        var text by remember { mutableStateOf("") }
        OutlinedTextField(text, { text = it }, label = { Text("New value for ${e.name}", fontSize = KitDensity.caption) }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().testTag("hub:secret:edit:field:${e.path}"))
        KitActionBar(listOf(
            KitAction("Save", "hub:secret:edit:save:${e.path}", enabled = text.isNotBlank()) {
                HubAndroid.put(ctx)(e.path, text.trim()); editing = false; shown = null; note = "rotated"; onChanged()
            },
            KitAction("Cancel", "hub:secret:edit:cancel:${e.path}") { editing = false },
        ))
    }
    if (confirmDelete) KitConfirmDialog("Delete ${e.name}?", "${e.path} is removed from this phone's account. Apps that use it stop working until you set it again.",
        "Delete", "Keep", onConfirm = { confirmDelete = false; HubAndroid.remove(ctx, e.path); onChanged() }, onDismiss = { confirmDelete = false })
}

// ── a provider: its secrets + its own Setup (connect, test, re-auth) ─────────────────────────────────────────

@Composable
fun ProviderCard(p: Provider, entries: List<SecretEntry>, go: (String, String) -> Unit, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var result by remember(p.id) { mutableStateOf<SetupResult?>(null) }
    var busy by remember(p.id) { mutableStateOf(false) }
    var connecting by remember(p.id) { mutableStateOf(false) }
    KitCard(Modifier.testTag(HubTags.provider(p.id))) {
        KitListRow(p.label, tag = "provider:${p.id}", secondary = "${entries.size} stored · " + p.methods.joinToString { it.label },
            pill = when { entries.isEmpty() -> "missing" to KitState.WARN; entries.any { it.expired } -> "expired" to KitState.BAD; else -> "connected" to KitState.OK })
        for (e in entries) SecretLine(e, onChanged)
        KitSectionHeader("Setup", "", eyebrow = true)
        result?.let { r -> for (s in r.steps) KitListRow(s.kind.label, tag = "setup:${p.id}:${s.kind.name}", secondary = s.line,
            pill = (if (s.ok) "ok" else "failed") to (if (s.ok) KitState.OK else KitState.BAD)) }
        KitActionBar(listOf(
            KitAction("Connect", "hub:provider:connect:${p.id}") { connecting = !connecting },
            KitAction(if (busy) "Testing…" else "Test", "hub:provider:test:${p.id}", enabled = !busy) {
                busy = true
                scope.launch { result = withContext(Dispatchers.IO) { HubAndroid.providerTarget(ctx, p).run() }; busy = false }
            },
            KitAction("Re-auth", "hub:provider:reauth:${p.id}") {
                if (AuthMethod.WEB_OAUTH in p.methods && p.id in setOf("github", "gitea", "cloud-authelia")) go("home", "connect") else connecting = true
            },
        ), filledFirst = false)
        if (connecting) ConnectToken(p) { connecting = false; onChanged() }
    }
}

/** Connect by pasting one token: stored at the path TokenImport would give it, so every page agrees. */
@Composable
private fun ConnectToken(p: Provider, done: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf("api_token") }
    var value by remember { mutableStateOf("") }
    OutlinedTextField(name, { name = it }, label = { Text("Name", fontSize = KitDensity.caption) }, singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("hub:connect:name:${p.id}"))
    OutlinedTextField(value, { value = it }, label = { Text("Token / key for ${p.label}", fontSize = KitDensity.caption) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth().testTag("hub:connect:value:${p.id}"))
    KitActionBar(listOf(KitAction("Save", "hub:connect:save:${p.id}", enabled = value.isNotBlank() && name.isNotBlank()) {
        TokenImport.importOne(TokenImport.Row(p.id, Providers.methodOf(name.lowercase()) ?: AuthMethod.API_TOKEN, "${p.id}_$name", value.trim()), HubAndroid.put(ctx))
        done()
    }))
}

// ── Secrets ▸ providers ──────────────────────────────────────────────────────────────────────────────────────

@Composable
fun SecretsProvidersPage(go: (String, String) -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    val groups = remember(tick) { Providers.secrets(HubAndroid.connections(ctx)).filter { (p, s) -> p.core || s.isNotEmpty() } }
    Scroll("hub:secrets") {
        KitSectionHeader("Secrets", "SSH keys, tokens, passwords and logins this account keeps, by provider. Masked; reveal and copy ask for your fingerprint or screen lock; a copy clears itself.")
        for ((p, entries) in groups) ProviderCard(p, entries, go) { tick++ }
    }
}

// ── Profiles ▸ apps ──────────────────────────────────────────────────────────────────────────────────────────

@Composable
fun AppProfilesPage() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }
    val plan = remember(tick) { HubAndroid.plan(ctx) }
    val rows = remember(tick) { HubAndroid.appRows(ctx, plan) }
    val results = remember { mutableStateMapOf<String, SetupResult>() }
    var busy by remember { mutableStateOf(false) }
    Scroll("hub:profiles:apps") {
        val o = rows.firstOrNull { it.id == open }
        if (o == null) {
            KitSectionHeader("App profiles", "Each fleet app's account and configuration as Cloud Account holds it. Tap an app for its profile and its own Setup.")
            KitCard {
                for (r in rows) KitListRow(r.label, Modifier.testTag(HubTags.app(r.id)), tag = r.id, leading = r.label.take(1).uppercase(),
                    secondary = listOfNotNull(r.endpoint, "${r.captured} setting(s)", r.sync.takeIf { it.isNotEmpty() }?.joinToString()).joinToString(" · "),
                    pill = r.state to when (r.state) { "signed in", "configured" -> KitState.OK; "signed out" -> KitState.WARN; else -> KitState.IDLE },
                    onClick = { open = r.id })
            }
        } else {
            TextButton(onClick = { open = null }, modifier = Modifier.testTag("hub:app:back")) { Text("‹ All apps") }
            KitSectionHeader(o.label, o.pkg)
            KitCard {
                KitListRow("State", secondary = o.state, tag = "app:${o.id}:state")
                KitListRow("Server / endpoint", secondary = o.endpoint ?: "none captured", tag = "app:${o.id}:endpoint")
                KitListRow("Sync", secondary = o.sync.joinToString().ifEmpty { "no sync settings captured" }, tag = "app:${o.id}:sync")
                KitListRow("Stores it owns", secondary = o.stores.joinToString().ifEmpty { "none captured yet (Configs ▸ Fleet push captures them)" }, tag = "app:${o.id}:stores")
                KitListRow("Settings captured", secondary = "${o.captured} · ${o.planned} would be pushed", tag = "app:${o.id}:counts")
            }
            KitSectionHeader("Setup", "", eyebrow = true)
            results[o.id]?.let { r -> for (s in r.steps) KitListRow(s.kind.label, tag = "setup:${o.id}:${s.kind.name}", secondary = s.line,
                pill = (if (s.ok) "ok" else "failed") to (if (s.ok) KitState.OK else KitState.BAD)) }
            KitActionBar(listOf(
                KitAction(if (busy) "Running…" else "Set up ${o.label}", "hub:app:setup:${o.id}", enabled = !busy) {
                    busy = true
                    scope.launch {
                        val app = AppProfiles.App(o.id, o.label, o.pkg)
                        results[o.id] = withContext(Dispatchers.IO) { HubAndroid.appTarget(ctx, app, plan).run() }
                        busy = false; tick++
                    }
                },
                KitAction("Open app", "hub:app:open:${o.id}", enabled = o.installed) {
                    ctx.packageManager.getLaunchIntentForPackage(o.pkg)?.let { ctx.startActivity(it) }
                },
            ))
        }
    }
}

// ── Home ─────────────────────────────────────────────────────────────────────────────────────────────────────

@Composable
fun HomePage(go: (String, String) -> Unit, pickText: PickText) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    val blob = remember(tick) { HubAndroid.connections(ctx) }
    val groups = remember(tick) { Providers.secrets(blob) }
    val matrix = remember(tick) { Providers.matrix(blob) }
    val profile = remember(tick) { ProfilePrefs(ctx) }
    val summary = remember(tick) { runCatching { AccountVault(ctx).summary() }.getOrNull() }
    Scroll(HubTags.HOME) {
        // a) picture + summary, once there is data
        val creds = groups.values.sumOf { it.size }
        val expired = groups.values.sumOf { l -> l.count { it.expired } }
        if (profile.name.isNotBlank() || creds > 0) {
            val pic = remember(profile.pictureUri) {
                runCatching { ctx.contentResolver.openInputStream(Uri.parse(profile.pictureUri))?.use { BitmapFactory.decodeStream(it) } }.getOrNull()
            }
            KitHero(profile.name.ifBlank { "Account" }, listOf(profile.email).filter { it.isNotBlank() }, profile.name.take(2), tag = "home",
                onClick = { go("home", "account") },
                avatar = pic?.let { b -> @Composable { Image(b.asImageBitmap(), "profile picture", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) } })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(KitDensity.small)) {
                KitStatTile("$creds", "credentials", if (creds > 0) KitState.OK else KitState.WARN, Modifier.weight(1f), tag = "home:creds")
                KitStatTile("$expired", "expired", if (expired > 0) KitState.BAD else KitState.OK, Modifier.weight(1f), tag = "home:expired")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(KitDensity.small)) {
                KitStatTile("${summary?.optInt("connections") ?: 0}", "connection values", KitState.IDLE, Modifier.weight(1f), tag = "home:connections")
                KitStatTile("${summary?.optInt("configs") ?: 0}", "app settings", KitState.IDLE, Modifier.weight(1f), tag = "home:configs")
            }
        } else {
            KitStatusBanner("Nothing imported yet: import your configs or tokens below, or connect on Home ▸ Connect.", KitState.WARN, tag = "home:empty")
        }

        // b) Connections
        KitSectionHeader("Connections", "", eyebrow = true)
        ConnectionsTable(matrix, go) { tick++ }

        // c) Import configs
        KitSectionHeader("Import configs", "", eyebrow = true)
        KitCard { AutofillImportBody(pickText) }

        // d) Import tokens
        KitSectionHeader("Import tokens", "", eyebrow = true)
        KitCard { TokenImporter(pickText) { tick++ } }

        // e) Tokens
        KitSectionHeader("Tokens", "", eyebrow = true)
        KitCard {
            val all = groups.values.flatten()
            if (all.isEmpty()) Caption("No token stored yet.")
            for (e in all) SecretLine(e) { tick++ }
        }

        // f) Setup all
        KitSectionHeader("Setup all", "", eyebrow = true)
        SetupAllCard { tick++ }
    }
}

@Composable
private fun ConnectionsTable(rows: List<ConnectionRow>, go: (String, String) -> Unit, changed: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tested = remember { mutableStateMapOf<String, SetupResult>() }
    KitCard(Modifier.testTag(HubTags.CONNECTIONS)) {
        Row(Modifier.fillMaxWidth()) {
            Text("Provider", Modifier.weight(1.3f), color = LocalKitPalette.current.textSecondary, fontSize = KitDensity.caption)
            for (m in AuthMethod.values()) Text(m.label, Modifier.weight(1f), color = LocalKitPalette.current.textSecondary, fontSize = KitDensity.caption)
        }
        for (r in rows) {
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(r.provider.label, Modifier.weight(1.3f), color = LocalKitPalette.current.textPrimary, fontSize = KitDensity.body)
                for (m in AuthMethod.values()) {
                    val c = r.cells.getValue(m)
                    Column(Modifier.weight(1f).testTag(HubTags.cell(r.provider.id, m)).clickable(enabled = c.status != CellStatus.NOT_USED) {
                        when (c.status) {
                            CellStatus.CONNECTED -> scope.launch {
                                tested[r.provider.id] = withContext(Dispatchers.IO) { HubAndroid.providerTarget(ctx, r.provider).run() }
                            }
                            CellStatus.MISSING, CellStatus.EXPIRED ->
                                if (m == AuthMethod.WEB_OAUTH && r.provider.id in setOf("github", "gitea", "cloud-authelia")) go("home", "connect")
                                else go("secrets", "providers")
                            CellStatus.NOT_USED -> {}
                        }
                    }) {
                        KitStatePill(c.status.word, stateOf(c.status))
                        val action = when (c.status) { CellStatus.CONNECTED -> "test"; CellStatus.EXPIRED -> "re-auth"; CellStatus.MISSING -> "set up"; else -> "" }
                        if (action.isNotEmpty()) Caption(action)
                    }
                }
            }
            tested[r.provider.id]?.let { t -> KitListRow("Test ${r.provider.label}", tag = "tested:${r.provider.id}", secondary = t.line, pill = (if (t.ok) "ok" else "failed") to stateOf(t)) }
        }
        TextButton(onClick = changed, modifier = Modifier.testTag("hub:connections:refresh")) { Text("Refresh") }
    }
}

@Composable
private fun TokenImporter(pickText: PickText, changed: () -> Unit) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    val rows = remember { mutableStateListOf<TokenImport.Row>() }
    var note by remember { mutableStateOf("") }
    OutlinedTextField(text, { text = it }, label = { Text("Paste JSON, .env or key=value lines", fontSize = KitDensity.caption) }, minLines = 3,
        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().testTag("hub:tokens:text"))
    KitActionBar(listOf(
        KitAction("Parse", "hub:tokens:parse", enabled = text.isNotBlank()) {
            val r = TokenImport.parse(text); rows.clear(); rows.addAll(r.rows); text = ""
            note = "${r.rows.size} token(s) read as ${r.format}" + if (r.skipped > 0) ", ${r.skipped} line(s) skipped" else ""
        },
        KitAction("Pick file", "hub:tokens:file") {
            pickText { t -> val r = TokenImport.parse(t); rows.clear(); rows.addAll(r.rows)
                note = "${r.rows.size} token(s) read as ${r.format}" + if (r.skipped > 0) ", ${r.skipped} line(s) skipped" else "" }
        },
    ))
    if (note.isNotBlank()) Caption(note)
    if (rows.isNotEmpty()) {
        Row(Modifier.fillMaxWidth()) {
            for ((h, w) in listOf("" to 0.5f, "Provider" to 1f, "Type" to 1f, "Name" to 1.4f, "Value" to 1.4f)) Text(h, Modifier.weight(w),
                color = LocalKitPalette.current.textSecondary, fontSize = KitDensity.caption)
        }
        rows.forEachIndexed { i, r ->
            Row(Modifier.fillMaxWidth().testTag("hub:tokens:row:$i"), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(r.selected, { rows[i] = r.copy(selected = it) }, Modifier.weight(0.5f))
                Text(Providers.byId(r.provider).label, Modifier.weight(1f).clickable {
                    val ids = Providers.CATALOG.map { it.id }; rows[i] = r.copy(provider = ids[(ids.indexOf(r.provider) + 1) % ids.size])
                }, fontSize = KitDensity.caption, color = LocalKitPalette.current.textPrimary)
                Text(r.type.label, Modifier.weight(1f).clickable {
                    val ms = AuthMethod.values(); rows[i] = r.copy(type = ms[(r.type.ordinal + 1) % ms.size])
                }, fontSize = KitDensity.caption, color = LocalKitPalette.current.textPrimary)
                Text(r.name, Modifier.weight(1.4f), fontSize = KitDensity.caption, color = LocalKitPalette.current.textPrimary)
                Text(r.masked, Modifier.weight(1.4f), fontSize = KitDensity.caption, color = LocalKitPalette.current.textSecondary)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Caption("→ ${r.path}" + if (r.expires.isNotBlank()) " · expires ${r.expires.take(10)}" else "")
                TextButton(onClick = {
                    TokenImport.importOne(r, HubAndroid.put(ctx)); rows.removeAt(i); note = "imported ${r.name}"; changed()
                }, modifier = Modifier.testTag("hub:tokens:import:$i")) { Text("Import") }
            }
        }
        Caption("Tap a provider or a type to change it.")
        KitActionBar(listOf(KitAction("Import all (${rows.count { it.selected }})", HubTags.TOKENS_IMPORT_ALL, enabled = rows.any { it.selected }) {
            val n = TokenImport.importAll(rows.toList(), HubAndroid.put(ctx))
            rows.removeAll { it.selected }; note = "imported $n token(s)"; changed()
        }, KitAction("Discard", "hub:tokens:discard") { rows.clear(); note = "" }))
    }
}

@Composable
private fun SetupAllCard(changed: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val results = remember { mutableStateListOf<SetupResult>() }
    var busy by remember { mutableStateOf(false) }
    KitCard(Modifier.testTag(HubTags.SETUP_ALL)) {
        Caption("Runs each provider's Setup, then each app's, one after the other, and lists what happened.")
        KitActionBar(listOf(KitAction(if (busy) "Running…" else "Setup all", "hub:setup-all:run", enabled = !busy) {
            busy = true; results.clear()
            scope.launch {
                val targets = withContext(Dispatchers.IO) { HubAndroid.allTargets(ctx) }
                withContext(Dispatchers.IO) { SetupAll.run(targets) { r -> scope.launch { results += r } } }
                busy = false; changed()
            }
        }))
        if (results.isNotEmpty()) {
            val ok = results.count { it.ok }
            KitStatusBanner("$ok of ${results.size} set up", if (ok == results.size) KitState.OK else KitState.WARN, tag = "setup-all")
        }
        for (r in results) KitListRow(r.label, tag = "setup-all:${r.id}", secondary = r.line, pill = (if (r.ok) "ok" else "failed") to stateOf(r))
    }
}
