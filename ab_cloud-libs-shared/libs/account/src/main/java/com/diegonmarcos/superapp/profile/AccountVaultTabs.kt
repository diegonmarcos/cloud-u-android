package com.diegonmarcos.superapp.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.account.R
import com.diegonmarcos.superapp.settings.AccountVault
import com.diegonmarcos.superapp.uikit.KitAction
import com.diegonmarcos.superapp.uikit.KitActionBar
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitChip
import com.diegonmarcos.superapp.uikit.KitFingerprint
import com.diegonmarcos.superapp.uikit.KitListRow
import com.diegonmarcos.superapp.uikit.KitSettingsRow
import com.diegonmarcos.superapp.uikit.KitState
import com.diegonmarcos.superapp.uikit.KitStatusBanner
import com.diegonmarcos.superapp.uikit.KitSectionHeader
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

object VaultTags {
    const val CONNECTIONS = "account:connections"
    const val SECRETS = "account:secrets"
    const val GRANTS = "account:grants"
    const val VAULT_LINK = "vault:cloud_vault_link"
    fun secretRow(path: String) = "vault:secret:$path"
    const val SUMMARY = "vault:summary"
    const val EXPORT = "vault:bundle:export"
    const val IMPORT = "vault:bundle:import"
    const val PASS = "vault:bundle:pass"
    const val PASS_OK = "vault:bundle:pass_ok"
    const val MIGRATE = "vault:migrate"
    const val RESULT = "vault:result"
    fun row(path: String) = "vault:row:$path"
    fun grant(pkg: String, key: String) = "vault:grant:$pkg:$key"
    fun revoke(pkg: String, key: String) = "vault:revoke:$pkg:$key"
    fun revokeAll(pkg: String) = "vault:revoke_all:$pkg"
    const val GRANT_PKG = "vault:grant_pkg"
    const val GRANT_KEY = "vault:grant_key"
    const val GRANT_ADD = "vault:grant_add"
}

/** A caption line under a section (summary, result); rows are KitListRow. */
@Composable
private fun Caption(text: String, tag: String? = null) {
    val p = LocalKitPalette.current
    Text(text, color = p.textSecondary, style = MaterialTheme.typography.bodySmall,
        modifier = if (tag != null) Modifier.testTag(tag) else Modifier)
}

@Composable
private fun SummaryLine(v: AccountVault, tick: Int) {
    val s = remember(tick) { v.summary() }
    Caption(stringResource(R.string.vault_summary, s.optInt("connections"), s.optInt("data"), s.optInt("configs"), s.optInt("secrets")), VaultTags.SUMMARY)
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
    var result by remember { mutableStateOf(Said(KitState.IDLE, "")) }
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
        KitActionBar(listOf(
            KitAction(stringResource(R.string.vault_export), VaultTags.EXPORT) {
                askPass = { pass -> scope.launch { val text = withContext(Dispatchers.Default) { vault.exportBundle(pass) }; export("cloud-account-bundle.json", text); result = Said(KitState.OK, ctx.getString(R.string.vault_exported)) } }
            },
            KitAction(stringResource(R.string.vault_import), VaultTags.IMPORT) {
                pickBundle { text ->
                    askPass = { pass -> scope.launch {
                        val r = withContext(Dispatchers.Default) { vault.importBundle(text, pass) }
                        result = if (r.ok) Said(KitState.OK, ctx.getString(R.string.vault_imported, r.sections.joinToString(", "))) else Said(KitState.BAD, r.why); tick++
                    } }
                }
            },
            KitAction(stringResource(R.string.vault_migrate), VaultTags.MIGRATE) {
                scope.launch {
                    val filled = withContext(Dispatchers.IO) { AccountMigrate.run(ctx) }
                    result = Said(KitState.OK, ctx.getString(R.string.vault_migrated, filled.size, filled.joinToString(", ") { it.path })); tick++
                }
            },
        ), filledFirst = false)
        if (result.text.isNotBlank()) KitStatusBanner(result.text, result.state, Modifier.testTag(VaultTags.RESULT), tag = "vault:result")
        KitCard {
            if (paths.isEmpty()) KitSettingsRow(stringResource(R.string.vault_connections_none))
            for (path in paths) {
                val v = vault.connection(path)
                KitListRow(fieldLabel(path), Modifier.testTag(VaultTags.row(path)), secondary = path, tag = path,
                    onLongClick = { copyPath(ctx, path) }) {
                    if (visible(path)) Text(v.toString().take(48), color = p.textSecondary, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
                    else KitFingerprint(fingerprint(v?.toString().orEmpty()), length = v?.toString()?.length ?: 0, tag = path)
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

// ── SECRETS ──────────────────────────────────────────────────────────────

private data class SecretRow(val path: String, val fingerprint: String, val source: String)

/** sha256(value) hex prefix: a fingerprint, never the value. */
internal fun fingerprint(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)

/**
 * Secrets ▸ secrets: ONLY keys the fleet manifest classes `secret` (a key it cannot class is never listed: fail closed).
 * Per key: presence, a fingerprint (never the value) and the app / store that owns it. The header link opens Cloud Vault;
 * its package comes from the fleet manifest's `vault` row, never a literal, and the label turns to "Install via Store" when absent.
 */
@Composable
fun SecretsTab(openVault: () -> Unit) {
    val ctx = LocalContext.current
    val vault = remember { AccountVault(ctx) }
    val m = remember { runCatching { AccountFleet.manifest(ctx) }.getOrNull() }
    val routes = remember { m?.let { SetupPlan.routes(it) }.orEmpty() }
    val paths = remember { SetupPlan.leaves(vault.connections()) }
    val vaultApp = remember { AccountFleet.fleetApps().firstOrNull { it.id == "vault" } }
    val installed = remember(vaultApp) { vaultApp?.let { FleetSetup.installed(ctx, it.pkg) } ?: false }
    val rows = remember(paths) {
        paths.mapNotNull { path ->
            val r = routes[path]?.firstOrNull() ?: return@mapNotNull null
            val app = m?.apps?.values?.firstOrNull { a -> m.stores[r.store]?.usedBy?.any { it in a.libs || it == a.module } == true } ?: return@mapNotNull null
            if (m.keyClass(m.stores.getValue(r.store), r.key, app.id) != "secret") return@mapNotNull null
            SecretRow(path, fingerprint(vault.connection(path)?.toString().orEmpty()), "${app.id}:${r.store}.${r.key}")
        }
    }
    Column(Modifier.fillMaxWidth().testTag(VaultTags.SECRETS), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        KitSectionHeader(stringResource(R.string.vault_secrets_title), stringResource(R.string.vault_secrets_values_caption))
        KitListRow(vaultApp?.label ?: stringResource(R.string.vault_cloud_vault_fallback_label), Modifier.testTag(VaultTags.VAULT_LINK),
            secondary = "passwords live there; this page is the fleet's secrets", tag = "cloud-vault",
            pill = if (installed) "installed" to KitState.OK else "not installed" to KitState.IDLE) {
            TextButton(onClick = openVault) {
                Text(stringResource(if (installed) R.string.vault_open_cloud_vault else R.string.vault_install_cloud_vault))
            }
        }
        KitCard {
            if (rows.isEmpty()) KitSettingsRow(stringResource(R.string.vault_secrets_values_none))
            for (row in rows) {
                KitListRow(fieldLabel(row.path), Modifier.testTag(VaultTags.secretRow(row.path)), secondary = "${row.path} · ${row.source}",
                    tag = row.path, pill = "held" to KitState.OK) {
                    KitFingerprint(row.fingerprint, tag = row.path)
                }
            }
        }
    }
}

// ── GRANTS ───────────────────────────────────────────────────────────────

/** Secrets ▸ grants: which package may read which key. Revocable per key or whole; values never drawn. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GrantsTab() {
    val ctx = LocalContext.current
    val p = LocalKitPalette.current
    val vault = remember { AccountVault(ctx) }
    var tick by remember { mutableStateOf(0) }
    val grants = remember(tick) { vault.grants.all() }
    var pkg by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().testTag(VaultTags.GRANTS), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        KitSectionHeader(stringResource(R.string.vault_secrets_title), stringResource(R.string.vault_secrets_caption))
        SummaryLine(vault, tick)
        if (grants.isEmpty()) KitCard { KitSettingsRow(stringResource(R.string.vault_secrets_none)) }
        for ((who, keys) in grants) {
            KitCard {
                KitListRow(who.substringAfterLast('.').replaceFirstChar { it.uppercase() }, secondary = who, tag = who,
                    leading = who.substringAfterLast('.').take(1).uppercase(), pill = "${keys.size} keys" to KitState.OK) {
                    TextButton(modifier = Modifier.testTag(VaultTags.revokeAll(who)), onClick = { vault.grants.revoke(who); tick++ }) {
                        Text(stringResource(R.string.vault_revoke_all), color = p.accent)
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (k in keys) KitChip(fieldLabel(k), onRemove = { vault.grants.revoke(who, k); tick++ },
                        tag = VaultTags.grant(who, k), removeTag = VaultTags.revoke(who, k))
                }
            }
        }
        KitCard {
            OutlinedTextField(pkg, { pkg = it }, singleLine = true, label = { Text(stringResource(R.string.vault_grant_pkg)) }, modifier = Modifier.fillMaxWidth().testTag(VaultTags.GRANT_PKG))
            OutlinedTextField(key, { key = it }, singleLine = true, label = { Text(stringResource(R.string.vault_grant_key)) }, modifier = Modifier.fillMaxWidth().testTag(VaultTags.GRANT_KEY))
            KitActionBar(listOf(KitAction(stringResource(R.string.vault_grant_add), VaultTags.GRANT_ADD, pkg.isNotBlank() && key.isNotBlank()) {
                vault.grants.grant(pkg.trim(), key.trim()); pkg = ""; key = ""; tick++
            }))
        }
    }
}
