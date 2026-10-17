package com.diegonmarcos.superapp.browser

import androidx.compose.foundation.background
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.uikit.KitConfirmDialog
import com.diegonmarcos.superapp.uikit.KitListRow
import com.diegonmarcos.superapp.uikit.KitSectionHeader
import com.diegonmarcos.superapp.uikit.KitSwitchRow
import com.diegonmarcos.superapp.uikit.KitState
import com.diegonmarcos.superapp.uikit.KitStatusBanner
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/** The values remembered per site (Autocomplete > Sites); [onlyOrigin] narrows it to one site (the page dropdown's "Autocomplete for this site"). */
@Composable
fun AcSitesSection(
    entries: List<AcMemoryEntry>,
    onlyOrigin: String? = null,
    onForget: (String) -> Unit,
    onForgetSite: (String) -> Unit,
    onForgetAll: () -> Unit,
) {
    val p = LocalKitPalette.current
    var confirmAll by remember { mutableStateOf(false) }
    val shown = if (onlyOrigin == null) entries else AcSiteMemory.forSite(entries, onlyOrigin)
    Column(Modifier.fillMaxWidth().testTag("browser:ac:sites")) {
        KitSectionHeader(if (onlyOrigin == null) "Sites" else "Autocomplete for $onlyOrigin",
            "Values you typed on one site that are not generic personal data (a customer number, a member ID), offered on that site and form only.")
        if (shown.isEmpty()) Text("Nothing remembered" + (onlyOrigin?.let { " for $it" } ?: "") + ".", color = p.textSecondary,
            modifier = Modifier.padding(vertical = 6.dp).testTag("browser:ac:sites:empty"))
        shown.groupBy { it.origin }.toSortedMap().forEach { (origin, list) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(origin, color = p.accent, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                TextButton({ onForgetSite(origin) }, modifier = Modifier.testTag("browser:ac:sites:forget:$origin")) { Text("Forget site") }
            }
            list.forEach { e ->
                KitListRow(label = e.value, secondary = e.label.ifBlank { e.fieldKey } + " · used ${e.uses}x", tag = "ac-site-${e.id}",
                    trailing = { TextButton({ onForget(e.id) }, modifier = Modifier.testTag("browser:ac:sites:remove:${e.id}")) { Text("✕") } })
            }
        }
        if (onlyOrigin == null && entries.isNotEmpty()) TextButton({ confirmAll = true }, modifier = Modifier.testTag("browser:ac:sites:clear")) { Text("Forget every site's values") }
    }
    if (confirmAll) KitConfirmDialog("Forget every remembered value?", "All per-site values are removed from this phone. Profiles, passwords and cards stay.", "Forget", "Keep",
        onConfirm = { confirmAll = false; onForgetAll() }, onDismiss = { confirmAll = false })
}

/**
 * Saved passwords: site, user name and where each came from; the password itself is shown only after [reveal]
 * (fingerprint / screen lock) and never in the list. [available] false = no Keystore key: nothing is shown or saved.
 */
@Composable
fun AcPasswordsSection(
    logins: List<AcLogin>,
    unreadable: Int,
    available: Boolean,
    onSave: (AcLogin) -> Unit,
    onDelete: (String) -> Unit,
    reveal: (AcLogin, (String) -> Unit) -> Unit,
) {
    val p = LocalKitPalette.current
    var editing by remember { mutableStateOf<AcLogin?>(null) }
    var shown by remember { mutableStateOf<Pair<String, String>?>(null) }
    var query by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().testTag("browser:ac:passwords")) {
        KitSectionHeader("Saved passwords", "Encrypted on this phone (Android Keystore); shown or filled only after fingerprint or screen lock.")
        if (!available) KitStatusBanner("The Android Keystore gave no key: saved passwords are not shown and nothing is saved. Nothing was deleted.", KitState.WARN, tag = "ac-keystore")
        if (unreadable > 0) KitStatusBanner("$unreadable saved item(s) cannot be opened right now (kept, not deleted).", KitState.WARN, tag = "ac-unreadable")
        if (logins.size > 6) OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().testTag("browser:ac:passwords:search"), singleLine = true, label = { Text("Search") })
        val list = logins.filter { query.isBlank() || it.site.contains(query, true) || it.username.contains(query, true) }
        if (list.isEmpty() && available) Text("No saved password.", color = p.textSecondary, modifier = Modifier.padding(vertical = 6.dp).testTag("browser:ac:passwords:empty"))
        list.forEach { l ->
            KitListRow(label = l.site, secondary = l.username.ifBlank { "(no user name)" } + " · " + l.source.label, tag = "ac-login-${l.id}",
                onClick = { reveal(l) { pw -> shown = l.id to pw } },
                trailing = { Row { TextButton({ editing = l }, modifier = Modifier.testTag("browser:ac:passwords:edit:${l.id}")) { Text("Edit") }
                    TextButton({ onDelete(l.id) }, modifier = Modifier.testTag("browser:ac:passwords:delete:${l.id}")) { Text("✕") } } })
        }
        if (available) TextButton({ editing = AcLogin(site = "", username = "", password = "") }, modifier = Modifier.testTag("browser:ac:passwords:add")) { Text("＋ Add password") }
    }
    shown?.let { (id, pw) ->
        AlertDialog(onDismissRequest = { shown = null }, title = { Text(logins.firstOrNull { it.id == id }?.site.orEmpty()) },
            text = { Text(pw, modifier = Modifier.testTag("browser:ac:passwords:revealed")) },
            confirmButton = { TextButton({ shown = null }) { Text("Hide") } })
    }
    editing?.let { l ->
        var site by remember(l.id) { mutableStateOf(l.site) }
        var user by remember(l.id) { mutableStateOf(l.username) }
        var pw by remember(l.id) { mutableStateOf(l.password) }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text(if (l.site.isBlank()) "Add password" else "Edit password") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(site, { site = it }, Modifier.testTag("browser:ac:passwords:site"), singleLine = true, label = { Text("Site (example.com)") })
                OutlinedTextField(user, { user = it }, Modifier.testTag("browser:ac:passwords:user"), singleLine = true, label = { Text("User name") })
                OutlinedTextField(pw, { pw = it }, Modifier.testTag("browser:ac:passwords:pw"), singleLine = true, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation())
            } },
            confirmButton = { TextButton({ onSave(l.copy(site = AcSite.registrable(site), username = user.trim(), password = pw, source = AcSource.LOCAL)); editing = null },
                enabled = site.isNotBlank() && pw.isNotEmpty(), modifier = Modifier.testTag("browser:ac:passwords:save")) { Text("Save") } },
            dismissButton = { TextButton({ editing = null }) { Text("Cancel") } })
    }
}

/** Saved cards: brand and last four in the list; number and code only after [reveal]. */
@Composable
fun AcCardsSection(
    cards: List<AcCard>,
    unreadable: Int,
    available: Boolean,
    onSave: (AcCard) -> Unit,
    onDelete: (String) -> Unit,
    reveal: (AcCard, (String) -> Unit) -> Unit,
) {
    val p = LocalKitPalette.current
    var editing by remember { mutableStateOf<AcCard?>(null) }
    var shown by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().testTag("browser:ac:cards")) {
        KitSectionHeader("Saved cards", "Encrypted on this phone (Android Keystore); shown or filled only after fingerprint or screen lock.")
        if (!available) KitStatusBanner("The Android Keystore gave no key: saved cards are not shown and nothing is saved. Nothing was deleted.", KitState.WARN, tag = "ac-keystore-cards")
        if (unreadable > 0) KitStatusBanner("$unreadable saved item(s) cannot be opened right now (kept, not deleted).", KitState.WARN, tag = "ac-unreadable-cards")
        if (cards.isEmpty() && available) Text("No saved card.", color = p.textSecondary, modifier = Modifier.padding(vertical = 6.dp).testTag("browser:ac:cards:empty"))
        cards.forEach { c ->
            KitListRow(label = c.title(), secondary = listOf(c.holder, if (c.expMonth.isNotBlank()) "${c.expMonth}/${c.expYear.takeLast(2)}" else "", c.source.label).filter { it.isNotBlank() }.joinToString(" · "),
                tag = "ac-card-${c.id}", onClick = { reveal(c) { n -> shown = n } },
                trailing = { Row { TextButton({ editing = c }, modifier = Modifier.testTag("browser:ac:cards:edit:${c.id}")) { Text("Edit") }
                    TextButton({ onDelete(c.id) }, modifier = Modifier.testTag("browser:ac:cards:delete:${c.id}")) { Text("✕") } } })
        }
        if (available) TextButton({ editing = AcCard(number = "") }, modifier = Modifier.testTag("browser:ac:cards:add")) { Text("＋ Add card") }
    }
    shown?.let { n ->
        AlertDialog(onDismissRequest = { shown = null }, title = { Text("Card number") },
            text = { Text(n.filter { it.isDigit() }.chunked(4).joinToString(" "), modifier = Modifier.testTag("browser:ac:cards:revealed")) },
            confirmButton = { TextButton({ shown = null }) { Text("Hide") } })
    }
    editing?.let { c ->
        var label by remember(c.id) { mutableStateOf(c.label) }
        var holder by remember(c.id) { mutableStateOf(c.holder) }
        var number by remember(c.id) { mutableStateOf(c.number) }
        var mm by remember(c.id) { mutableStateOf(c.expMonth) }
        var yy by remember(c.id) { mutableStateOf(c.expYear) }
        var cvc by remember(c.id) { mutableStateOf(c.cvc) }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text(if (c.number.isBlank()) "Add card" else "Edit card") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(label, { label = it }, Modifier.testTag("browser:ac:cards:label"), singleLine = true, label = { Text("Label (optional)") })
                OutlinedTextField(holder, { holder = it }, Modifier.testTag("browser:ac:cards:holder"), singleLine = true, label = { Text("Name on card") })
                OutlinedTextField(number, { number = it }, Modifier.testTag("browser:ac:cards:number"), singleLine = true, label = { Text("Card number") }, visualTransformation = PasswordVisualTransformation())
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(mm, { mm = it.filter(Char::isDigit).take(2) }, Modifier.weight(1f).testTag("browser:ac:cards:mm"), singleLine = true, label = { Text("MM") })
                    OutlinedTextField(yy, { yy = it.filter(Char::isDigit).take(4) }, Modifier.weight(1f).testTag("browser:ac:cards:yy"), singleLine = true, label = { Text("YYYY") })
                }
                OutlinedTextField(cvc, { cvc = it.filter(Char::isDigit).take(4) }, Modifier.testTag("browser:ac:cards:cvc"), singleLine = true,
                    label = { Text("Security code (optional, stored encrypted)") }, visualTransformation = PasswordVisualTransformation())
            } },
            confirmButton = { TextButton({ onSave(c.copy(label = label.trim(), holder = holder.trim(), number = number.filter { it.isDigit() || it == ' ' }.trim(),
                expMonth = mm.padStart(2, '0').takeIf { mm.isNotBlank() }.orEmpty(), expYear = yy.let { if (it.length == 2) "20$it" else it }, cvc = cvc, source = AcSource.LOCAL)); editing = null },
                enabled = AcProposals.luhn(number), modifier = Modifier.testTag("browser:ac:cards:save")) { Text("Save") } },
            dismissButton = { TextButton({ editing = null }) { Text("Cancel") } })
    }
}

/** Sync & Vault: the order (local first, then Cloud Vault, vault wins), the live state and what each side holds. */
@Composable
fun AcVaultSection(
    state: AcVaultState,
    localCount: Int,
    vaultCount: Int,
    onUseVault: ((Boolean) -> Unit)? = null,
    onMakeService: (() -> Unit)? = null,
) {
    val p = LocalKitPalette.current
    Column(Modifier.fillMaxWidth().testTag("browser:ac:vault")) {
        KitSectionHeader("Cloud Vault", "")
        if (onUseVault != null) KitSwitchRow("Use Cloud Vault for passwords and cards", "Fill from this phone first, then let Cloud Vault overwrite it",
            checked = state != AcVaultState.OFF, onCheckedChange = onUseVault, modifier = Modifier.testTag("browser:ac:vault:use"))
        if (onMakeService != null && state == AcVaultState.NOT_SERVICE)
            TextButton(onMakeService, modifier = Modifier.testTag("browser:ac:vault:service")) { Text("Make Cloud Vault the autofill service") }
        KitStatusBanner(state.reason, if (state.usable) KitState.OK else if (state == AcVaultState.OFF) KitState.IDLE else KitState.WARN, tag = "ac-vault-state")
        Text("Fill order: this phone's own store first, then Cloud Vault through Android's autofill. When Cloud Vault fills an entry, its value " +
            "replaces the local copy (the vault wins) and the entry is marked vault. A new password or card is saved here and Cloud Vault's own save flow is opened for it. " +
            "Cloud Vault gives a secret to nothing but an autofill dataset you pick, so the browser only learns what you let it fill.",
            color = p.textSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 6.dp))
        Text("Saved on this phone: $localCount from the local store, $vaultCount taken from Cloud Vault.", color = p.textSecondary,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("browser:ac:vault:counts"))
    }
}

/** One site's remembered values as a full page over the browser (page dropdown > This site > Autocomplete for this site). */
@Composable
fun AcSitePage(
    entries: List<AcMemoryEntry>,
    origin: String,
    onForget: (String) -> Unit,
    onForgetSite: (String) -> Unit,
    onClose: () -> Unit,
) {
    val p = LocalKitPalette.current
    Column(Modifier.fillMaxSize().background(p.surface).verticalScroll(rememberScrollState()).padding(12.dp).testTag("browser:ac:sitepage")) {
        TextButton(onClose) { Text("← Back") }
        AcSitesSection(entries, onlyOrigin = origin, onForget = onForget, onForgetSite = onForgetSite, onForgetAll = {})
    }
}
