package com.diegonmarcos.cloudaccount.hub

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.diegonmarcos.superapp.fleetendpoints.DriveCredentialsContract as D
import com.diegonmarcos.superapp.fleetendpoints.EndpointChecker
import com.diegonmarcos.superapp.fleetendpoints.FleetServiceSettings
import com.diegonmarcos.superapp.profile.ActivityFeed
import com.diegonmarcos.superapp.profile.DriveCredentialStore
import com.diegonmarcos.superapp.profile.FleetEndpointRows
import com.diegonmarcos.superapp.profile.FleetEndpointsRuntime
import com.diegonmarcos.superapp.profile.Tile
import com.diegonmarcos.superapp.settings.AccountVault
import com.diegonmarcos.superapp.uikit.GroupTitle
import com.diegonmarcos.superapp.uikit.KitAction
import com.diegonmarcos.superapp.uikit.KitActionBar
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitDensity
import com.diegonmarcos.superapp.uikit.KitState
import com.diegonmarcos.superapp.uikit.KitStatusBanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object SetupPageTags {
    const val SERVICES = "setup:services"
    const val DRIVE = "setup:drive"
    const val NOTE = "setup:note"
    const val CRED_FIELD = "setup:cred:field"
    const val CRED_SAVE = "setup:cred:save"
    const val SSH_GENERATE = "setup:ssh:generate"
    const val SSH_IMPORT = "setup:ssh:import"
    const val SSH_COPY = "setup:ssh:copy"
    const val SSH_PUBLIC = "setup:ssh:public"
    const val SSH_CONFIG = "setup:ssh:config"
    fun service(id: String) = "setup:service:$id"
    fun test(id: String) = "setup:test:$id"
    fun credential(id: String) = "setup:credential:$id"
    fun sshHost(host: String) = "setup:sshhost:$host"
    fun remote(name: String) = "setup:remote:$name"
    fun mount(name: String) = "setup:mount:$name"
}

private fun copyPlain(ctx: Context, label: String, text: String) {
    ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
}

/**
 * Connect ▸ Setup ▸ Services. One row per service of build.json::fleet_endpoints, taken from the declaration at run time
 * ([FleetServiceSettings]): the mode (Private over the mesh, or Public + bearer / mTLS / SSO), the resolved URL, the
 * credential status (set or not, never the value), a Test, and set / rotate for the credential. A credential is written
 * only to the keystore-backed account vault, behind device auth; Cloud Vault's mTLS certificate is Cloud Vault's own.
 */
@Composable
fun FleetServicesSetupPage() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val gate = RevealGate(LocalDeviceAuth.current)
    val vault = remember { AccountVault(ctx) }
    val services = remember { FleetEndpointsRuntime.services() }
    val rows = remember(services) { FleetServiceSettings.rows(services) }
    var tick by remember { mutableStateOf(0) }
    val verdicts = remember { mutableStateMapOf<String, Pair<Boolean, String>>() }
    var editing by remember { mutableStateOf<FleetServiceSettings.Row?>(null) }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun test(only: List<FleetServiceSettings.Row>) {
        busy = true
        scope.launch {
            withContext(Dispatchers.IO) {
                val checker = EndpointChecker()
                for (r in only) {
                    val s = services.first { it.id == r.id }
                    val c = runCatching { FleetEndpointRows.check(listOf(s), checker)[s.id] }.getOrNull()
                    verdicts[r.id] = if (c == null) false to "not checked"
                    else FleetServiceSettings.testOk(r, c.meshUp, c.publicStatus) to FleetServiceSettings.testLine(r, c.meshUp, c.publicStatus)
                }
            }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = KitDensity.large, vertical = KitDensity.medium).testTag(SetupPageTags.SERVICES),
        verticalArrangement = Arrangement.spacedBy(KitDensity.small)) {
        if (rows.isEmpty()) KitStatusBanner("No fleet service is declared.", KitState.WARN, tag = SetupPageTags.NOTE)
        else KitStatusBanner(note.ifEmpty { "${rows.size} services from the fleet declaration · private over the mesh first, public with the bearer, mTLS or SSO" },
            KitState.IDLE, tag = SetupPageTags.NOTE)
        KitActionBar(listOf(KitAction("Test all", "setup:test:all", !busy && rows.isNotEmpty()) { test(rows) }), filledFirst = false)
        GroupTitle("Services · ${rows.size}")
        // `tick` forces the credential status to be read again after a save.
        for (r in rows) {
            val has = tick >= 0 && r.credentialPath?.let { runCatching { vault.connection(it)?.toString().orEmpty().isNotBlank() }.getOrDefault(false) } == true
            val v = verdicts[r.id]
            val credWord = when {
                !r.needsCredential -> "no credential needed (private)"
                r.credentialPath == null -> r.credentialLabel
                has -> "${r.credentialLabel}: set"
                else -> "${r.credentialLabel}: not set"
            }
            Tile("${r.id} · ${r.kind}", Modifier.testTag(SetupPageTags.service(r.id)), tag = r.id,
                pill = (v?.let { if (it.first) "ok" to KitState.OK else "failed" to KitState.BAD } ?: (r.mode.label to KitState.IDLE)),
                secondary = listOfNotNull(
                    r.mode.label + " · " + r.url,
                    r.fallbackUrl.takeIf { it.isNotEmpty() }?.let { "fallback ${FleetServiceSettings.publicMode(r.auth).label} · $it" },
                    r.aliases.takeIf { it.isNotEmpty() }?.joinToString(prefix = "also ") ,
                    credWord, v?.second,
                ).joinToString("\n")) {
                Row {
                    TextButton(enabled = !busy, modifier = Modifier.testTag(SetupPageTags.test(r.id)), onClick = { test(listOf(r)) }) { Text("Test") }
                    if (r.needsCredential && r.credentialPath != null)
                        TextButton(modifier = Modifier.testTag(SetupPageTags.credential(r.id)), onClick = { editing = r }) { Text(if (has) "Rotate" else "Set") }
                }
            }
        }
        editing?.let { r ->
            var value by remember(r.id) { mutableStateOf("") }
            KitCard {
                GroupTitle("${r.id}: ${r.credentialLabel}")
                OutlinedTextField(value, { value = it }, label = { Text("New value (stored in this app's keystore only)", fontSize = KitDensity.caption) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().testTag(SetupPageTags.CRED_FIELD))
                KitActionBar(listOf(
                    KitAction("Save", SetupPageTags.CRED_SAVE, value.isNotBlank()) {
                        val path = r.credentialPath.orEmpty()
                        val v = value.trim()
                        if (path.isNotEmpty()) gate.reveal("Store the ${r.credentialLabel} for ${r.id}", read = { "ok" }, show = {
                            vault.putConnection(path, v); value = ""; editing = null; tick++
                            note = "${r.id}: credential stored"; ActivityFeed.record(ctx, "setup", "credential set for ${r.id}")
                        }, denied = { note = "not stored: device auth refused" })
                    },
                    KitAction("Cancel", "setup:cred:cancel") { value = ""; editing = null },
                ), filledFirst = false)
            }
        }
    }
}

/**
 * Connect ▸ Setup ▸ Drive. Cloud Drive's credentials, held here and published to Cloud Drive as `drive_credentials`:
 * Google Drive / Workspace rclone remotes (OAuth token or service account), Git over ssh (an ed25519 key in the keystore-backed vault,
 * its PUBLIC key to copy to GitHub / Gitea, and the per-host ssh config entries from the declaration) and cloud mounts (rclone and
 * sshfs / FUSE). Private values are masked, never printed, and every write is behind device auth.
 */
@Composable
fun DriveSetupPage() {
    val ctx = LocalContext.current
    val gate = RevealGate(LocalDeviceAuth.current)
    val store = remember { DriveCredentialStore(AccountVault(ctx)) }
    var tick by remember { mutableStateOf(0) }
    var note by remember { mutableStateOf("") }
    var form by remember { mutableStateOf<String?>(null) } // "google" | "mount" | "import"
    val remotes = remember(tick) { store.remotes() }
    val mounts = remember(tick) { store.mounts() }
    val hosts = remember(tick) { runCatching { store.sshHosts() }.getOrDefault(emptyList()) }
    val pub = remember(tick) { store.publicKey() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = KitDensity.large, vertical = KitDensity.medium).testTag(SetupPageTags.DRIVE),
        verticalArrangement = Arrangement.spacedBy(KitDensity.small)) {
        KitStatusBanner(note.ifEmpty { "${remotes.size} remotes · ${if (pub.isEmpty()) "no ssh key" else "ssh key set"} · ${mounts.size} mounts · published to Cloud Drive as drive_credentials" },
            KitState.IDLE, tag = SetupPageTags.NOTE)

        GroupTitle("Google Drive / Workspace · rclone")
        for (r in remotes) Tile(r.name, Modifier.testTag(SetupPageTags.remote(r.name)), tag = r.name,
            pill = (if (r.hasSecret) "set" to KitState.OK else "no credential" to KitState.WARN),
            secondary = "${r.type} · ${if (r.auth == D.AUTH_SERVICE_ACCOUNT) "service account" else "OAuth"}${r.scope.takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty()}") {
            TextButton(onClick = { gate.reveal("Remove ${r.name}", { "ok" }, { store.removeRemote(r.name); tick++; note = "removed ${r.name}" }) }) { Text("Remove") }
        }
        KitActionBar(listOf(KitAction(if (form == "google") "Close" else "Add Google remote", "setup:drive:add") { form = if (form == "google") null else "google" }), filledFirst = false)
        if (form == "google") GoogleRemoteForm { name, type, scope, token, sa, clientId, clientSecret ->
            gate.reveal("Store the rclone remote $name", { "ok" }, show = {
                val fields = linkedMapOf("scope" to scope, "client_id" to clientId, "client_secret" to clientSecret, "token" to token, "service_account_credentials" to sa)
                note = if (store.putRemote(D.Remote(name, type, fields))) "stored $name" else "not stored: check the name"
                ActivityFeed.record(ctx, "setup", "rclone remote $name"); form = null; tick++
            }, denied = { note = "not stored: device auth refused" })
        }

        GroupTitle("Git · SSH (ed25519)")
        Tile("Public key", Modifier.testTag(SetupPageTags.SSH_PUBLIC), tag = "ssh-public",
            pill = (if (pub.isEmpty()) "none" to KitState.WARN else "set" to KitState.OK),
            secondary = if (pub.isEmpty()) "Generate or import a key; the private half stays in this app's keystore." else store.fingerprint() + "\n" + pub)
        KitActionBar(listOf(
            KitAction("Generate", SetupPageTags.SSH_GENERATE) {
                gate.reveal("Generate an SSH key", { "ok" }, show = {
                    note = if (store.generate()) "new ed25519 key: copy its public key to GitHub and Gitea" else "key not generated"
                    ActivityFeed.record(ctx, "setup", "ssh key generated"); tick++
                }, denied = { note = "not generated: device auth refused" })
            },
            KitAction("Import", SetupPageTags.SSH_IMPORT) { form = if (form == "import") null else "import" },
            KitAction("Copy public key", SetupPageTags.SSH_COPY, pub.isNotEmpty()) { copyPlain(ctx, "ssh public key", pub); note = "public key copied" },
        ), filledFirst = false)
        if (form == "import") ImportKeyForm { pem, pass ->
            gate.reveal("Import an SSH key", { "ok" }, show = {
                val r = store.importKey(pem, pass)
                note = if (r.ok) "key imported" else "not imported: ${r.why}"; if (r.ok) { form = null; ActivityFeed.record(ctx, "setup", "ssh key imported") }; tick++
            }, denied = { note = "not imported: device auth refused" })
        }
        GroupTitle("ssh config · ${hosts.size} hosts")
        for (h in hosts) Tile("Host ${h.host}", Modifier.testTag(SetupPageTags.sshHost(h.host)), tag = h.host,
            secondary = "HostName ${h.hostname} · User ${h.user} · Port ${h.port} · IdentityFile ${h.identityFile}")
        KitActionBar(listOf(KitAction("Copy ssh config", SetupPageTags.SSH_CONFIG, hosts.isNotEmpty()) { copyPlain(ctx, "ssh config", D.sshConfig(hosts)); note = "ssh config copied" }), filledFirst = false)

        GroupTitle("Cloud mounts · rclone, sshfs / FUSE")
        for (m in mounts) Tile(m.name, Modifier.testTag(SetupPageTags.mount(m.name)), tag = m.name,
            pill = (m.kind to KitState.IDLE),
            secondary = if (m.kind == "rclone") "${m.remote}: → ${m.mountpoint}" else "${m.user}@${m.host}:${m.port}${m.path} → ${m.mountpoint}${if (m.password.isNotEmpty()) " · password set" else ""}") {
            TextButton(onClick = { gate.reveal("Remove ${m.name}", { "ok" }, { store.removeMount(m.name); tick++; note = "removed ${m.name}" }) }) { Text("Remove") }
        }
        KitActionBar(listOf(KitAction(if (form == "mount") "Close" else "Add mount", "setup:mount:add") { form = if (form == "mount") null else "mount" }), filledFirst = false)
        if (form == "mount") MountForm(remotes.map { it.name }) { m ->
            gate.reveal("Store the mount ${m.name}", { "ok" }, show = {
                note = if (store.putMount(m)) "stored ${m.name}" else "not stored: check the fields"; form = null; tick++
            }, denied = { note = "not stored: device auth refused" })
        }
    }
}

@Composable
private fun Field(label: String, value: String, set: (String) -> Unit, secret: Boolean = false, number: Boolean = false, tag: String? = null) {
    OutlinedTextField(value, set, label = { Text(label, fontSize = KitDensity.caption) }, singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        modifier = Modifier.fillMaxWidth().let { if (tag != null) it.testTag(tag) else it })
}

@Composable
private fun GoogleRemoteForm(save: (name: String, type: String, scope: String, token: String, sa: String, clientId: String, clientSecret: String) -> Unit) {
    var name by remember { mutableStateOf("gdrive") }
    var scope by remember { mutableStateOf("drive") }
    var token by remember { mutableStateOf("") }
    var sa by remember { mutableStateOf("") }
    var cid by remember { mutableStateOf("") }
    var cs by remember { mutableStateOf("") }
    KitCard {
        GroupTitle("rclone remote (type drive)")
        Field("Remote name", name, { name = it })
        Field("Scope (drive, drive.readonly, drive.file)", scope, { scope = it })
        Field("OAuth token JSON (from `rclone authorize drive`)", token, { token = it }, secret = true)
        Field("or service account credentials JSON (Workspace)", sa, { sa = it }, secret = true)
        Field("OAuth client id (optional)", cid, { cid = it })
        Field("OAuth client secret (optional)", cs, { cs = it }, secret = true)
        KitActionBar(listOf(KitAction("Save remote", "setup:drive:save", name.isNotBlank() && (token.isNotBlank() || sa.isNotBlank())) {
            // rclone.conf is one line per key: collapse the JSON to a single line.
            save(name.trim(), "drive", scope.trim(), token.replace(Regex("\\s*\\n\\s*"), ""), sa.replace(Regex("\\s*\\n\\s*"), ""), cid.trim(), cs.trim())
        }))
    }
}

@Composable
private fun ImportKeyForm(import: (pem: String, passphrase: String) -> Unit) {
    var pem by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    KitCard {
        GroupTitle("Import an ed25519 private key")
        OutlinedTextField(pem, { pem = it }, label = { Text("Private key (OpenSSH / PEM)", fontSize = KitDensity.caption) }, minLines = 3,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().testTag("setup:ssh:pem"))
        Field("Passphrase (if protected)", pass, { pass = it }, secret = true)
        KitActionBar(listOf(KitAction("Import key", "setup:ssh:import:go", pem.isNotBlank()) { import(pem, pass); pem = ""; pass = "" }))
    }
}

@Composable
private fun MountForm(remotes: List<String>, save: (D.Mount) -> Unit) {
    var kind by remember { mutableStateOf("rclone") }
    var name by remember { mutableStateOf("") }
    var remote by remember { mutableStateOf(remotes.firstOrNull().orEmpty()) }
    var host by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var path by remember { mutableStateOf("") }
    var point by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    KitCard {
        GroupTitle("Mount")
        KitActionBar(listOf(
            KitAction(if (kind == "rclone") "rclone ✓" else "rclone", "setup:mount:kind:rclone") { kind = "rclone" },
            KitAction(if (kind == "sshfs") "sshfs ✓" else "sshfs", "setup:mount:kind:sshfs") { kind = "sshfs" },
        ), filledFirst = false)
        Field("Mount name", name, { name = it })
        if (kind == "rclone") Field("rclone remote", remote, { remote = it })
        else {
            Field("Host", host, { host = it }); Field("User", user, { user = it }); Field("Port", port, { port = it }, number = true)
            Field("Remote path", path, { path = it }); Field("Password (optional: the ssh key is used otherwise)", pass, { pass = it }, secret = true)
        }
        Field("Mountpoint", point, { point = it })
        KitActionBar(listOf(KitAction("Save mount", "setup:mount:save", name.isNotBlank()) {
            save(D.Mount(name.trim(), kind, remote.trim(), host.trim(), user.trim(), port.toIntOrNull() ?: 22, path.trim(), point.trim(), pass))
        }))
    }
}
