package com.diegonmarcos.superapp.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.account.R
import com.diegonmarcos.superapp.settings.AccountVault
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitSectionHeader
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

object VaultTags {
    const val CONNECTIONS = "account:connections"
    const val DATA = "account:data"
    const val CONFIGS = "account:configs"
    const val SECRETS = "account:secrets"
    const val SUMMARY = "vault:summary"
    const val EXPORT = "vault:bundle:export"
    const val IMPORT = "vault:bundle:import"
    const val PASS = "vault:bundle:pass"
    const val PASS_OK = "vault:bundle:pass_ok"
    const val MIGRATE = "vault:migrate"
    const val CAPTURE = "vault:capture"
    const val RESULT = "vault:result"
    fun row(path: String) = "vault:row:$path"
    fun grant(pkg: String, key: String) = "vault:grant:$pkg:$key"
    fun revoke(pkg: String, key: String) = "vault:revoke:$pkg:$key"
    fun revokeAll(pkg: String) = "vault:revoke_all:$pkg"
    const val GRANT_PKG = "vault:grant_pkg"
    const val GRANT_KEY = "vault:grant_key"
    const val GRANT_ADD = "vault:grant_add"
}

@Composable
private fun Dense(text: String, tag: String? = null, secondary: Boolean = false) {
    val p = LocalKitPalette.current
    Text(text, color = if (secondary) p.textSecondary else p.textPrimary, fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall, modifier = if (tag != null) Modifier.testTag(tag) else Modifier)
}

@Composable
private fun SummaryLine(v: AccountVault, tick: Int) {
    val s = remember(tick) { v.summary() }
    Dense(stringResource(R.string.vault_summary, s.optInt("connections"), s.optInt("data"), s.optInt("configs"), s.optInt("secrets")), VaultTags.SUMMARY, true)
}

// ── CONNECTIONS ──────────────────────────────────────────────────────────

/**
 * Connections, data first: every `section.key` path the vault holds, one dense row each, a value drawn only when the
 * manifest classes its destination non-secret (anything it cannot class is masked: fail closed). Beside it the whole
 * bundle (encrypted export / import) and the one-shot "take in what SuperApp kept".
 */
@Composable
fun ConnectionsTab(pickBundle: ((String) -> Unit) -> Unit, export: (String, String) -> Unit) {
    val ctx = LocalContext.current
    val p = LocalKitPalette.current
    val scope = rememberCoroutineScope()
    val vault = remember { AccountVault(ctx) }
    var tick by remember { mutableStateOf(0) }
    var result by remember { mutableStateOf("") }
    var askPass by remember { mutableStateOf<((CharArray) -> Unit)?>(null) }
    val m = remember { runCatching { AccountFleet.manifest(ctx) }.getOrNull() }
    val paths = remember(tick) { com.diegonmarcos.superapp.profile.SetupPlan.leaves(vault.connections()) }
    val routes = remember { m?.let { SetupPlan.routes(it) }.orEmpty() }
    fun visible(path: String): Boolean {
        val r = routes[path]?.firstOrNull() ?: return false
        val app = m?.apps?.values?.firstOrNull { a -> m.stores[r.store]?.usedBy?.any { it in a.libs || it == a.module } == true } ?: return false
        return m.keyClass(m.stores.getValue(r.store), r.key, app.id) == "config"
    }
    Column(Modifier.fillMaxWidth().testTag(VaultTags.CONNECTIONS), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        KitSectionHeader(stringResource(R.string.vault_connections_title), stringResource(R.string.vault_connections_caption))
        SummaryLine(vault, tick)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(modifier = Modifier.testTag(VaultTags.EXPORT), onClick = {
                askPass = { pass -> scope.launch { val text = withContext(Dispatchers.Default) { vault.exportBundle(pass) }; export("cloud-account-bundle.json", text); result = ctx.getString(R.string.vault_exported) } }
            }) { Text(stringResource(R.string.vault_export)) }
            OutlinedButton(modifier = Modifier.testTag(VaultTags.IMPORT), onClick = {
                pickBundle { text ->
                    askPass = { pass -> scope.launch {
                        val r = withContext(Dispatchers.Default) { vault.importBundle(text, pass) }
                        result = if (r.ok) ctx.getString(R.string.vault_imported, r.sections.joinToString(", ")) else "✗ ${r.why}"; tick++
                    } }
                }
            }) { Text(stringResource(R.string.vault_import)) }
        }
        OutlinedButton(modifier = Modifier.fillMaxWidth().testTag(VaultTags.MIGRATE), onClick = {
            scope.launch {
                val filled = withContext(Dispatchers.IO) { AccountMigrate.run(ctx) }
                result = ctx.getString(R.string.vault_migrated, filled.size, filled.joinToString(", ") { it.path }); tick++
            }
        }) { Text(stringResource(R.string.vault_migrate)) }
        if (result.isNotBlank()) Dense(result, VaultTags.RESULT, true)
        KitCard {
            if (paths.isEmpty()) Dense(stringResource(R.string.vault_connections_none), secondary = true)
            for (path in paths) {
                val v = vault.connection(path)
                Row(Modifier.fillMaxWidth().testTag(VaultTags.row(path)), horizontalArrangement = Arrangement.SpaceBetween) {
                    Dense(path)
                    Dense(if (visible(path)) v.toString().take(48) else ctx.getString(R.string.vault_masked, v?.toString()?.length ?: 0), secondary = true)
                }
            }
        }
    }
    askPass?.let { done ->
        var pass by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askPass = null },
            title = { Text(stringResource(R.string.vault_pass_title)) },
            text = { OutlinedTextField(pass, { pass = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.testTag(VaultTags.PASS)) },
            confirmButton = { TextButton(enabled = pass.isNotEmpty(), modifier = Modifier.testTag(VaultTags.PASS_OK),
                onClick = { askPass = null; done(pass.toCharArray()) }) { Text(stringResource(R.string.account_edit_ok)) } },
            dismissButton = { TextButton(onClick = { askPass = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

// ── DATA ─────────────────────────────────────────────────────────────────

@Composable
fun DataTab() {
    val ctx = LocalContext.current
    val vault = remember { AccountVault(ctx) }
    val model = remember { AccountModel.get(ctx) }
    Column(Modifier.fillMaxWidth().testTag(VaultTags.DATA), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        KitSectionHeader(stringResource(R.string.vault_data_title), stringResource(R.string.vault_data_caption))
        SummaryLine(vault, 0)
        KitCard {
            val ids = vault.identities()
            Dense(stringResource(R.string.vault_data_identities, ids.length()))
            for (i in 0 until ids.length()) Dense("  " + (ids.optJSONObject(i)?.let { it.optString("label").ifBlank { it.optString("name") } } ?: ids.optString(i)), secondary = true)
            Dense(stringResource(R.string.vault_data_profile, ctx.getSharedPreferences("profile_prefs", android.content.Context.MODE_PRIVATE).all.size))
            for (slot in AccountStore.Slot.values()) {
                val d = runCatching { model.store.read(slot) }.getOrNull()
                Dense("  ${slot.name} · " + (d?.let { "${it.meta.source} · ${it.meta.at}" } ?: stringResource(R.string.account_file_absent_short)), secondary = true)
            }
        }
    }
}

// ── CONFIGS ──────────────────────────────────────────────────────────────

@Composable
fun ConfigsTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val vault = remember { AccountVault(ctx) }
    var tick by remember { mutableStateOf(0) }
    var result by remember { mutableStateOf("") }
    val configs = remember(tick) { vault.appConfigs() }
    Column(Modifier.fillMaxWidth().testTag(VaultTags.CONFIGS), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        KitSectionHeader(stringResource(R.string.vault_configs_title), stringResource(R.string.vault_configs_caption))
        SummaryLine(vault, tick)
        OutlinedButton(modifier = Modifier.fillMaxWidth().testTag(VaultTags.CAPTURE), onClick = {
            scope.launch {
                val got = withContext(Dispatchers.IO) { AccountMigrate.capture(vault, AccountFleet.manifest(ctx), { FleetSetup.installed(ctx, it) }, FleetSetup.transport(ctx)) }
                result = ctx.getString(R.string.vault_captured, got.size); tick++
            }
        }) { Text(stringResource(R.string.vault_capture)) }
        if (result.isNotBlank()) Dense(result, VaultTags.RESULT, true)
        KitCard {
            if (configs.length() == 0) Dense(stringResource(R.string.vault_configs_none), secondary = true)
            for (app in configs.keys().asSequence().sorted()) {
                val stores = configs.optJSONObject(app) ?: continue
                Dense(app)
                for (store in stores.keys().asSequence().sorted()) {
                    val files = stores.optJSONObject(store) ?: JSONObject()
                    val n = files.keys().asSequence().sumOf { f -> (files.optJSONObject(f)?.length() ?: 0) }
                    Dense("  $store · $n", VaultTags.row("$app/$store"), true)
                }
            }
        }
    }
}

// ── SECRETS ──────────────────────────────────────────────────────────────

/** Per-app grants: which package may read which key. Revocable per key or whole; values never drawn. */
@Composable
fun SecretsTab() {
    val ctx = LocalContext.current
    val p = LocalKitPalette.current
    val vault = remember { AccountVault(ctx) }
    var tick by remember { mutableStateOf(0) }
    val grants = remember(tick) { vault.grants.all() }
    var pkg by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().testTag(VaultTags.SECRETS), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        KitSectionHeader(stringResource(R.string.vault_secrets_title), stringResource(R.string.vault_secrets_caption))
        SummaryLine(vault, tick)
        KitCard {
            if (grants.isEmpty()) Dense(stringResource(R.string.vault_secrets_none), secondary = true)
            for ((who, keys) in grants) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Dense(who)
                    Text(stringResource(R.string.vault_revoke_all), color = p.accent, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.testTag(VaultTags.revokeAll(who)).clickable { vault.grants.revoke(who); tick++ })
                }
                for (k in keys) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Dense("  $k", VaultTags.grant(who, k), true)
                    Text(stringResource(R.string.vault_revoke), color = p.accent, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.testTag(VaultTags.revoke(who, k)).clickable { vault.grants.revoke(who, k); tick++ })
                }
            }
        }
        KitCard {
            OutlinedTextField(pkg, { pkg = it }, singleLine = true, label = { Text(stringResource(R.string.vault_grant_pkg)) }, modifier = Modifier.fillMaxWidth().testTag(VaultTags.GRANT_PKG))
            OutlinedTextField(key, { key = it }, singleLine = true, label = { Text(stringResource(R.string.vault_grant_key)) }, modifier = Modifier.fillMaxWidth().testTag(VaultTags.GRANT_KEY))
            OutlinedButton(enabled = pkg.isNotBlank() && key.isNotBlank(), modifier = Modifier.fillMaxWidth().testTag(VaultTags.GRANT_ADD),
                onClick = { vault.grants.grant(pkg.trim(), key.trim()); pkg = ""; key = ""; tick++ }) { Text(stringResource(R.string.vault_grant_add)) }
        }
    }
}
