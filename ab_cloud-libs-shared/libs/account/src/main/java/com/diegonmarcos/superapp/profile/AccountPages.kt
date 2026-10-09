package com.diegonmarcos.superapp.profile

import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudlib.auth.SignIn
import com.diegonmarcos.cloudlib.auth.SignInHost
import com.diegonmarcos.cloudlib.auth.SignInResult
import com.diegonmarcos.cloudlib.auth.SignInWays
import com.diegonmarcos.superapp.devtools.AppDebugServer
import com.diegonmarcos.superapp.settings.AccountVault
import com.diegonmarcos.superapp.settings.ConfigsPrefs
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitSectionHeader
import com.diegonmarcos.superapp.uikit.KitSelectableTile
import com.diegonmarcos.superapp.uikit.KitSettingsRow
import com.diegonmarcos.superapp.uikit.KitSwitchRow
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Cloud Account redesign task 3: the pages of the new shell that draw existing lib data —
 * Account ▸ profile (spec 4.1), Account ▸ connect (4.2), Settings (4.11) and the placeholder
 * every later task replaces. Compose on libs:ui-kit; the host wraps them in CloudKitTheme.
 */
object AccountPageTags {
    const val PROFILE = "account:profile"
    const val CONNECT = "account:connect"
    const val SETTINGS = "account:settings"
    const val BACKUP = "profile:backup"
    const val FETCH = "connect:fetch"
    fun way(kind: String) = "connect:way:$kind"
    fun placeholder(section: String, page: String) = "placeholder:$section/$page"
}

/**
 * Which device this phone is: Connections `device.id`, else the cockpit's pick, else DERIVED from
 * the model — `Build.MODEL` / `Build.DEVICE` against the fetched vault's `electronics.fleet` entries'
 * `model` and the cached `devices/*.json` listing's `model` (refreshed by [DeviceVault.devices]). A
 * single match becomes the id and is persisted to Connections `device.id`; zero or several stay blank
 * (the candidates are reported so the picker / runbook can name them). Local data only: safe on main.
 */
object AccountDevice {
    const val SRC_CONNECTIONS = "connections"
    const val SRC_COCKPIT = "cockpit"
    const val SRC_DERIVED = "derived"
    const val SRC_NONE = "none"
    private const val K_DERIVED = "device.derived"
    const val K_LISTING = "account.devices.listing"

    data class Resolved(val id: String, val source: String, val model: String, val candidates: List<String>) {
        fun json(): JSONObject = JSONObject().put("id", id).put("source", source).put("model", model)
            .put("candidates", org.json.JSONArray(candidates))
    }

    fun id(ctx: Context): String = resolve(ctx).id

    fun resolve(ctx: Context): Resolved {
        val v = AccountVault(ctx)
        val model = Build.MODEL.orEmpty()
        val conn = (v.connection("device.id") as? String)?.trim().orEmpty()
        if (conn.isNotBlank()) {
            val derived = (v.connection(K_DERIVED) as? String).orEmpty() == conn
            return Resolved(conn, if (derived) SRC_DERIVED else SRC_CONNECTIONS, model, emptyList())
        }
        val cockpit = VaultCockpit.selectedDevice(ctx).trim()
        if (cockpit.isNotBlank()) return Resolved(cockpit, SRC_COCKPIT, model, emptyList())
        val c = candidates(ctx)
        if (c.size == 1) {
            v.putConnection("device.id", c[0]); v.putConnection(K_DERIVED, c[0])
            return Resolved(c[0], SRC_DERIVED, model, c)
        }
        return Resolved("", SRC_NONE, model, c)
    }

    /** The ids whose declared model is this phone's (`Build.MODEL` or `Build.DEVICE`). */
    fun candidates(ctx: Context): List<String> {
        val bundle = AccountModel.get(ctx).server()?.body
        val listing = ConfigsPrefs(ctx).text(K_LISTING).takeIf { it.isNotBlank() }
            ?.let { runCatching { org.json.JSONArray(it) }.getOrNull() }
        return matches(bundle, listing, listOf(Build.MODEL.orEmpty(), Build.DEVICE.orEmpty()))
    }

    /** Pure: `electronics.fleet.<id>.model` and listing rows' `model` matched (case-insensitive) to [models]. */
    fun matches(bundle: JSONObject?, listing: org.json.JSONArray?, models: List<String>): List<String> {
        val want = models.map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()
        if (want.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        val fleet = bundle?.optJSONObject("electronics")?.optJSONObject("fleet")
        fleet?.keys()?.forEach { id ->
            val m = (fleet.optJSONObject(id)?.opt("model") as? String)?.trim()?.lowercase()
            if (m != null && m in want) out += id
        }
        if (listing != null) for (i in 0 until listing.length()) {
            val row = listing.optJSONObject(i) ?: continue
            val id = row.optString("id")
            val m = (row.opt("model") as? String)?.trim()?.lowercase()
            if (id.isNotBlank() && id != DeviceProfile.DEFAULT_ID && m != null && m in want) out += id
        }
        return out.toList()
    }

    fun setId(ctx: Context, id: String) {
        AccountVault(ctx).putConnection("device.id", id.trim())
        AccountVault(ctx).putConnection(K_DERIVED, "")
        VaultCockpit.selectDevice(ctx, id.trim())
    }

    /** The devices the landed vault declares (electronics), by id. */
    fun declared(ctx: Context): List<VaultCockpit.Device> =
        AccountModel.get(ctx).server()?.body?.let { runCatching { VaultCockpit.devices(it) }.getOrNull() }.orEmpty()
}

private fun jsonAt(ctx: Context, path: String): JSONObject? =
    (AccountVault(ctx).connection(path) as? String)?.let { runCatching { JSONObject(it) }.getOrNull() }

@Composable
private fun Dense(text: String, secondary: Boolean = false, tag: String? = null) {
    val p = LocalKitPalette.current
    Text(text, color = if (secondary) p.textSecondary else p.textPrimary, fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall, modifier = if (tag != null) Modifier.testTag(tag) else Modifier)
}

@Composable
private fun Tile(label: String, value: String, modifier: Modifier, onClick: (() -> Unit)? = null) {
    KitCard(modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)) {
        Dense(label, secondary = true)
        Dense(value)
    }
}

@Composable
private fun Pill(label: String, tag: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag(tag)) { Text(label) }
}

/** A page a later task of the redesign fills; it says which, so the app is navigable now. */
@Composable
fun AccountPlaceholderPage(section: String, page: String, task: String, body: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
        .testTag(AccountPageTags.placeholder(section, page)), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        KitSectionHeader("$section ▸ $page", "filled by $task of the Cloud Account redesign")
        body?.invoke()
    }
}

// ── Account ▸ profile ────────────────────────────────────────────────────

@Composable
fun AccountProfilePage(open: (section: String, page: String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val model = remember { AccountModel.get(ctx) }
    model.version.intValue
    var tick by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    val shown = model.shown()
    val about = InfoMask.schema.firstOrNull { it.id == "about" }
    val resolved = remember(tick) { AccountDevice.resolve(ctx) }
    val deviceId = resolved.id
    val working = remember(tick) { DeviceVault(ctx).working() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag(AccountPageTags.PROFILE),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        KitSectionHeader("Identity", "the vault's about topic, through the mask; tap a field to edit")
        KitCard {
            if (about == null) Dense("no about topic is declared", true)
            else {
                val rows = InfoMask.declared.schemaRows(about, shown?.opt(about.id))
                if (rows.isEmpty() || shown == null) Dense("nothing fetched yet: Account ▸ connect", true)
                for (row in rows) {
                    val full = if (row.path.isBlank()) about.id else about.id + AccountDrift.SEP + row.path
                    val editable = (row.kind == InfoMask.Kind.SHOWN || row.kind == InfoMask.Kind.EMPTY) && '[' !in row.path
                    Column(Modifier.fillMaxWidth().then(if (editable) Modifier.clickable { editing = full } else Modifier)) {
                        Dense(row.path.ifBlank { about.id }, true)
                        Dense(when (row.kind) {
                            InfoMask.Kind.MASKED -> "•••• (${row.size})"
                            InfoMask.Kind.EMPTY -> "empty"
                            InfoMask.Kind.COLLAPSED -> "… (${row.size})"
                            InfoMask.Kind.PENDING -> row.text
                            InfoMask.Kind.SHOWN -> shownValue(full, row.text)
                        })
                    }
                }
            }
        }
        KitSectionHeader("This phone", "which device file this phone backs up to and restores from")
        KitCard {
            KitSettingsRow("Device id", deviceId.ifBlank { "not picked — tap to pick" }, onClick = { picking = true })
            if (resolved.source == AccountDevice.SRC_DERIVED) Dense("derived from model ${resolved.model}", true)
            Dense("model ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            val prof = working?.optJSONObject("profile")
            Dense("loaded profile: " + (working?.optString("device")?.ifBlank { null } ?: "none"))
            Dense("captured at: " + (prof?.optJSONObject("device")?.optString("captured_at")?.ifBlank { null } ?: "—"))
        }
        val fetched = remember(tick, model.version.intValue) { ConnectWays.lastFetch(ctx) }
        val backup = remember(tick) { jsonAt(ctx, "backup.last") }
        val restore = remember(tick) { jsonAt(ctx, "restore.last") }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile("Vault fetched", fetched?.optString("at")?.ifBlank { null } ?: "never", Modifier.weight(1f))
            Tile("Last backup", backup?.let { "${it.optString("device")} · ${it.optString("at")}" } ?: "never", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile("Last restore", restore?.let { "${it.optString("device")} · ${it.optString("sha").take(7)}" } ?: "never", Modifier.weight(1f))
            Tile("Drift", remember(tick, model.version.intValue) { profilesDriftCount(ctx, model).let { if (it == 0) "in sync" else "$it differ" } }, Modifier.weight(1f)) { open("profiles", "diff") }
        }
        Pill(if (busy) "Backing up…" else "Backup now", AccountPageTags.BACKUP) {
            if (busy) return@Pill
            busy = true; result = "…"
            scope.launch {
                result = withContext(Dispatchers.IO) { DeviceVault(ctx).backup(AccountDevice.id(ctx), false).optString("result") }
                busy = false; tick++
            }
        }
        Pill("Restore", "profile:restore") { open("setup", "runbook") }
        Pill("Migrate this phone", "profile:migrate") { open("setup", "runbook") }
        if (result.isNotBlank()) Dense(result)
    }

    if (picking) {
        val devices = remember { AccountDevice.declared(ctx) }
        var typed by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("Which device is this phone?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (devices.isEmpty()) Dense("the vault declares no devices yet (fetch it on Account ▸ connect)", true)
                    for (d in devices) KitSelectableTile(d.label.ifBlank { d.id },
                        when {
                            d.id == deviceId && resolved.source == AccountDevice.SRC_DERIVED -> "${d.id} · derived from model ${resolved.model}"
                            d.id in resolved.candidates -> "${d.id} · model ${resolved.model} matches"
                            else -> d.id
                        }, d.id == deviceId, {
                        AccountDevice.setId(ctx, d.id); picking = false; tick++
                    })
                    OutlinedTextField(typed, { typed = it }, label = { Text("new device…") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(enabled = typed.isNotBlank() && typed.trim() != DeviceProfile.DEFAULT_ID,
                    onClick = { AccountDevice.setId(ctx, typed); picking = false; tick++ }) { Text("Use new id") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        )
    }
    editing?.let { path ->
        var value by remember(path) { mutableStateOf(AccountDrift.leaves(shown)[path]?.let(AccountDrift::text).orEmpty()) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(path) },
            text = { OutlinedTextField(value, { value = it }) },
            confirmButton = {
                TextButton(enabled = !InfoMask.declared.hides(path, value), onClick = {
                    model.edit(path, value); result = model.save(); editing = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

// ── Account ▸ connect ────────────────────────────────────────────────────

@Composable
private fun OriginPicker(tick: Int, changed: () -> Unit) {
    val ctx = LocalContext.current
    val decl = remember { ConnectWays.decl() }
    val primary = remember(tick) { DeviceVault(ctx).primary()?.id }
    for (f in decl.forges) {
        val usable = f.repo != null
        KitSelectableTile(f.id, if (usable) "${f.repo} · ${decl.branch}" else "no vault repo declared on ${f.id} yet", f.id == primary, {
            if (usable) { AccountVault(ctx).putConnection("forge.primary", f.id); changed() }
        })
    }
}

@Composable
fun AccountConnectPage() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf<String?>(null) }
    val ways = remember { ConnectWays.ways() }
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }

    fun launchWay(block: () -> ConnectWays.Outcome) {
        status = "…"
        scope.launch { status = withContext(Dispatchers.IO) { runCatching(block).getOrElse { ConnectWays.Outcome(false, "✗ ${it.javaClass.simpleName}") } }.line; tick++ }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        launchWay {
            val text = runCatching { ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
            if (text.isNullOrEmpty()) ConnectWays.Outcome(false, "✗ could not read ${uri.lastPathSegment.orEmpty()}") else ConnectWays.importFile(ctx, text)
        }
    }
    val signInHost = remember {
        object : SignInHost {
            override fun onSignedIn(result: SignInResult) { status = ConnectWays.signedIn(ctx, result).line; tick++ }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag(AccountPageTags.CONNECT),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((forge, group) in ways.groupBy { it.forge }) {
            KitSectionHeader(forge?.replaceFirstChar { it.uppercase() } ?: "File", group.mapNotNull { it.note.ifBlank { null } }.joinToString(" · "))
            for (way in group) {
                when (val h = ConnectWays.handler(way.kind)) {
                    ConnectWays.Handler.GhLogin -> Pill(way.label, AccountPageTags.way(way.kind)) {
                        launchWay { ConnectWays.ghLogin(ctx) { code, page -> main.post { status = ConnectWays.ghPrompt(ctx, code, page) } } }
                    }
                    ConnectWays.Handler.Pat, ConnectWays.Handler.Ssh, ConnectWays.Handler.GiteaToken ->
                        Pill(if (h == ConnectWays.Handler.Ssh) "${way.label} (read only)" else way.label, AccountPageTags.way(way.kind)) { dialog = way.kind }
                    ConnectWays.Handler.AutheliaWeb -> {
                        val ids = remember { SignIn.offered(emptyList()).filter { it.kind == SignIn.Kind.AUTHELIA_WEB }.map { it.id } }
                        if (ids.isEmpty()) Dense("${way.label}: not offered by this build's sign-in declaration", true)
                        else SignInWays(host = signInHost, policy = ids, pill = { _, tag, onClick -> Pill(way.label, tag, onClick) })
                    }
                    ConnectWays.Handler.File -> Pill(way.label, AccountPageTags.way(way.kind)) {
                        filePicker.launch(arrayOf("application/json", "text/*", "*/*"))
                    }
                    is ConnectWays.Handler.Unwired -> Dense(h.why, true)
                }
            }
        }
        KitSectionHeader("Origin", "the primary forge: where Fetch now reads and Backup writes")
        OriginPicker(tick) { tick++ }
        Pill("Fetch now", AccountPageTags.FETCH) { launchWay { ConnectWays.fetchNow(ctx) } }
        val last = remember(tick) { ConnectWays.lastFetch(ctx) }
        Dense(last?.let { "last fetch ${it.optString("at")}: ${it.optString("line")}" } ?: "no fetch yet", true)
        if (status.isNotBlank()) Dense(status)
    }

    dialog?.let { kind ->
        var secret by remember(kind) { mutableStateOf("") }
        var pass by remember(kind) { mutableStateOf("") }
        val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            secret = runCatching { ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
                ?.let(ConnectWays::extractSshKey).orEmpty()
        }
        val ssh = kind == ConnectWays.GITHUB_SSH
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(ways.firstOrNull { it.kind == kind }?.label ?: kind) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (ssh) Dense("Read only: a shallow bare clone, the one file read, then deleted. Backups need a token.", true)
                    else Dense("Filed in this phone's encrypted vault once it works; never shown back.", true)
                    OutlinedTextField(secret, { secret = it }, label = { Text(if (ssh) "private key" else "token") },
                        visualTransformation = PasswordVisualTransformation(), singleLine = !ssh)
                    if (ssh) {
                        TextButton(onClick = { keyPicker.launch("*/*") }) { Text("Import key from file…") }
                        OutlinedTextField(pass, { pass = it }, label = { Text("passphrase (empty if none)") },
                            visualTransformation = PasswordVisualTransformation(), singleLine = true)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val s = secret; val pw = pass
                    secret = ""; pass = ""; dialog = null
                    launchWay {
                        when (ConnectWays.handler(kind)) {
                            ConnectWays.Handler.Pat -> ConnectWays.pat(ctx, s)
                            ConnectWays.Handler.Ssh -> ConnectWays.ssh(ctx, s, pw)
                            ConnectWays.Handler.GiteaToken -> ConnectWays.giteaToken(ctx, s)
                            else -> ConnectWays.Outcome(false, "✗ $kind takes no secret")
                        }
                    }
                }) { Text(if (ssh) "Clone & read" else "Read") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
    }
}

// ── Settings ─────────────────────────────────────────────────────────────

/** Connections `backup.auto`: off | daily | wifi. */
object AutoBackup {
    const val OFF = "off"
    const val DAILY = "daily"
    const val WIFI = "wifi"
    fun mode(ctx: Context): String = (AccountVault(ctx).connection("backup.auto") as? String)?.ifBlank { null } ?: OFF
}

/** Connections `debug.api`: "off" keeps the loopback debug server stopped. */
object DebugApiSwitch {
    fun enabled(ctx: Context): Boolean = (AccountVault(ctx).connection("debug.api") as? String) != "off"
    fun apply(ctx: Context) { if (enabled(ctx)) AppDebugServer.start(ctx) else AppDebugServer.stop() }
}

@Composable
fun AccountSettingsPage(version: String) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var typed by remember { mutableStateOf(AccountDevice.id(ctx)) }
    var token by remember { mutableStateOf("") }
    var line by remember { mutableStateOf("") }
    val vault = remember { AccountVault(ctx) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag(AccountPageTags.SETTINGS),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        KitSectionHeader("Device id", "overrides the pick on Account ▸ profile")
        OutlinedTextField(typed, { typed = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Pill("Save device id", "settings:device") {
            if (typed.isNotBlank() && typed.trim() != DeviceProfile.DEFAULT_ID) { AccountDevice.setId(ctx, typed); line = "✓ device id = ${typed.trim()}" }
            else line = "✗ a device id, not blank and not DEFAULT"
        }
        KitSectionHeader("Primary forge", "where Fetch now reads and Backup writes")
        OriginPicker(tick) { tick++ }
        KitSectionHeader("Auto-backup", "capture, compare, commit only when something changed")
        val mode = remember(tick) { AutoBackup.mode(ctx) }
        for ((id, label) in listOf(AutoBackup.OFF to "Off", AutoBackup.DAILY to "Daily", AutoBackup.WIFI to "On Wi-Fi")) {
            KitSelectableTile(label, if (id == AutoBackup.WIFI) "daily, only on an unmetered network" else "", mode == id, {
                vault.putConnection("backup.auto", id); AccountHost.autoBackupChanged(ctx); tick++
            })
        }
        KitSectionHeader("Fleet token", "the primary forge's token, filed encrypted; never shown back")
        val held = remember(tick) { DeviceVault(ctx).credentials() }
        Dense(held.keys().asSequence().joinToString(" · ") { "$it: ${if (held.optBoolean(it)) "held" else "none"}" }, true)
        OutlinedTextField(token, { token = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(), label = { Text("paste a token") })
        Pill("File token for the primary forge", "settings:token") {
            val f = DeviceVault(ctx).primary()
            line = when {
                f == null -> "✗ no usable forge declared"
                token.isBlank() -> "✗ paste a token first"
                else -> { vault.putConnection("forge.${f.id}.token", token.trim()); "✓ token filed for ${f.id}" }
            }
            token = ""; tick++
        }
        KitSectionHeader("Debug API", "the loopback /api/account/… routes")
        val on = remember(tick) { DebugApiSwitch.enabled(ctx) }
        KitSwitchRow("Debug API", if (AppDebugServer.isRunning()) "listening on loopback" else "stopped", on, { v ->
            vault.putConnection("debug.api", if (v) "on" else "off"); DebugApiSwitch.apply(ctx); tick++
        })
        KitSectionHeader("About", "Cloud Account $version")
        if (line.isNotBlank()) Dense(line)
    }
}
