package com.diegonmarcos.cloudaccount.hub

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.diegonmarcos.superapp.fleetendpoints.FleetHttp
import com.diegonmarcos.superapp.profile.ActivityFeed
import com.diegonmarcos.superapp.settings.AccountVault
import com.diegonmarcos.superapp.uikit.KitAction
import com.diegonmarcos.superapp.uikit.KitActionBar
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitConfirmDialog
import com.diegonmarcos.superapp.uikit.KitDensity
import com.diegonmarcos.superapp.uikit.KitEmptyState
import com.diegonmarcos.superapp.uikit.KitListRow
import com.diegonmarcos.superapp.uikit.KitSectionHeader
import com.diegonmarcos.superapp.uikit.KitState
import com.diegonmarcos.superapp.uikit.KitStatusBanner
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object TokensTags {
    const val PAGE = "tokens:page"
    const val LOADING = "tokens:loading"
    const val ERROR = "tokens:error"
    const val EMPTY = "tokens:empty"
    const val NEW_TOGGLE = "tokens:new:toggle"
    const val NEW_NAME = "tokens:new:name"
    const val NEW_AUDIENCE = "tokens:new:audience"
    const val NEW_SCOPES = "tokens:new:scopes"
    const val NEW_DAYS = "tokens:new:days"
    const val NEW_CREATE = "tokens:new:create"
    const val AUDIT_TOGGLE = "tokens:audit:toggle"
    fun row(id: String) = "tokens:row:$id"
    fun value(id: String) = "tokens:value:$id"
    fun reveal(id: String) = "tokens:reveal:$id"
    fun copy(id: String) = "tokens:copy:$id"
    fun revoke(id: String) = "tokens:revoke:$id"
}

/** The phone side of the engine: the vault's fleet bearer as `Authorization`, the fleet transport underneath. */
object TokensAndroid {
    fun client(ctx: Context): TokensClient = TokensClient(
        TokensClient.BASE,
        bearer = { AccountVault(ctx.applicationContext).connection(TokensClient.BEARER_KEY)?.toString() },
        transport = { m, u, h, b -> FleetHttp.call(FleetHttp.shared, m, u, h, b) },
    )
}

private fun day(epochSeconds: Long?): String = epochSeconds?.let { java.time.Instant.ofEpochSecond(it).toString().take(10) } ?: "never"

private fun stateOf(status: String) = when (status) { "active" -> KitState.OK; "revoked" -> KitState.BAD; else -> KitState.WARN }

/**
 * Data ▸ Secrets ▸ Tokens. Every token the fleet has emitted, masked; a value reaches the screen or the clipboard
 * only after device auth (biometric or screen lock) and only by a call to the engine at that moment (the phone keeps no
 * copy). Generate, reveal and revoke are audited by the engine; the phone's Activity records the action, never the value.
 */
@Composable
fun TokensPage(client: TokensClient = TokensAndroid.client(LocalContext.current)) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val gate = RevealGate(LocalDeviceAuth.current)
    var tick by remember { mutableStateOf(0) }
    var rows by remember { mutableStateOf<List<TokenRow>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    val shown = remember { mutableStateMapOf<String, String>() }
    var creating by remember { mutableStateOf(false) }
    var auditOpen by remember { mutableStateOf(false) }
    var audit by remember { mutableStateOf<List<AuditRow>>(emptyList()) }
    var confirmRevoke by remember { mutableStateOf<TokenRow?>(null) }

    LaunchedEffect(tick) {
        when (val r = withContext(Dispatchers.IO) { client.list() }) {
            is TokensResult.Ok -> { rows = r.value; error = null }
            is TokensResult.Fail -> { rows = rows ?: emptyList(); error = r.reason }
        }
        if (auditOpen) {
            val a = withContext(Dispatchers.IO) { client.audit() }
            if (a is TokensResult.Ok) audit = a.value
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = KitDensity.large, vertical = KitDensity.medium)
            .testTag(TokensTags.PAGE),
        verticalArrangement = Arrangement.spacedBy(KitDensity.small),
    ) {
        KitSectionHeader("Tokens", "Every fleet bearer token: reveal, copy, generate, revoke. Revoked tokens stop working within a minute.")
        val list = rows
        when {
            list == null -> KitStatusBanner("Reading the token list…", KitState.BUSY, tag = TokensTags.LOADING)
            error != null -> KitStatusBanner(error.orEmpty(), KitState.BAD, tag = TokensTags.ERROR)
        }
        if (note.isNotEmpty()) Text(note, color = LocalKitPalette.current.textSecondary, fontSize = KitDensity.caption)

        KitActionBar(listOf(
            KitAction(if (creating) "Close" else "New token", TokensTags.NEW_TOGGLE) { creating = !creating },
            KitAction("Refresh", "tokens:refresh") { tick++ },
            KitAction(if (auditOpen) "Hide audit" else "Audit log", TokensTags.AUDIT_TOGGLE) { auditOpen = !auditOpen; tick++ },
        ), filledFirst = false)

        if (creating) NewTokenCard { name, aud, scopes, days ->
            gate.revealOnIo("Generate token $name", scope, read = {
                when (val r = client.generate(name, aud, scopes, days)) {
                    is TokensResult.Ok -> { scope.launch { shown[r.value.first.id] = r.value.second }; ActivityFeed.record(ctx, "token", "generated ${r.value.first.id}"); r.value.second }
                    is TokensResult.Fail -> { scope.launch { note = r.reason }; null }
                }
            }, show = { creating = false; note = "generated · copy it now or reveal it later"; tick++ }, denied = { if (note.isEmpty()) note = "not generated" })
        }

        if (auditOpen) KitCard {
            KitSectionHeader("Audit", "generate, reveal, revoke: who and when, never a value", eyebrow = true)
            if (audit.isEmpty()) Text("No events yet.", color = LocalKitPalette.current.textSecondary, fontSize = KitDensity.caption)
            for (a in audit) KitListRow("${a.action} · ${a.tokenName}", secondary = "${day(a.at)} · ${a.actor}", tag = "tokens:audit:${a.at}:${a.action}")
        }

        if (list != null && list.isEmpty() && error == null) KitCard(Modifier.testTag(TokensTags.EMPTY)) {
            KitEmptyState("No tokens yet", "Generate one, or use a fleet client: Authelia tokens appear here the first time they are used.")
        }
        for (t in list.orEmpty()) TokenCard(t, shown[t.id], onReveal = {
            if (shown.containsKey(t.id)) shown.remove(t.id)
            else gate.revealOnIo("Show ${t.name}", scope, read = { (client.reveal(t.id) as? TokensResult.Ok)?.value },
                show = { shown[t.id] = it; ActivityFeed.record(ctx, "token", "revealed ${t.id}") }, denied = { note = "not shown: device auth refused or the engine said no" })
        }, onCopy = {
            gate.revealOnIo("Copy ${t.name}", scope, read = { (client.reveal(t.id) as? TokensResult.Ok)?.value },
                show = { SecretClipboard.copy(ctx, it); ActivityFeed.record(ctx, "token", "copied ${t.id}"); note = "copied · clears in ${SecretClipboard.CLEAR_AFTER_MS / 1000}s" },
                denied = { note = "not copied: device auth refused or the engine said no" })
        }, onRevoke = { confirmRevoke = t })
    }

    confirmRevoke?.let { t ->
        KitConfirmDialog("Revoke ${t.name}?", "Every client using it is refused within a minute. This cannot be undone; generate a new token instead.", "Revoke", "Keep",
            onConfirm = {
                confirmRevoke = null
                scope.launch {
                    when (val r = withContext(Dispatchers.IO) { client.revoke(t.id) }) {
                        is TokensResult.Ok -> { shown.remove(t.id); note = "revoked ${t.name}"; ActivityFeed.record(ctx, "token", "revoked ${t.id}") }
                        is TokensResult.Fail -> note = r.reason
                    }
                    tick++
                }
            }, onDismiss = { confirmRevoke = null })
    }
}

@Composable
private fun TokenCard(t: TokenRow, value: String?, onReveal: () -> Unit, onCopy: () -> Unit, onRevoke: () -> Unit) {
    KitCard(Modifier.testTag(TokensTags.row(t.id))) {
        KitListRow(t.name, tag = "tokens:name:${t.id}",
            secondary = listOf(t.kind, t.audience.takeIf { it != "*" } ?: "any host", "expires ${day(t.expiresAt)}", t.lastUsedAt?.let { "used ${day(it)}" } ?: "unused").joinToString(" · "),
            pill = t.status to stateOf(t.status), onClick = onReveal)
        Text(value ?: t.masked, color = LocalKitPalette.current.textPrimary, fontSize = KitDensity.body, modifier = Modifier.testTag(TokensTags.value(t.id)))
        if (t.scopes.isNotEmpty()) Text(t.scopes.joinToString(" "), color = LocalKitPalette.current.textSecondary, fontSize = KitDensity.caption)
        KitActionBar(listOf(
            KitAction(if (value == null) "Reveal" else "Hide", TokensTags.reveal(t.id)) { onReveal() },
            KitAction("Copy", TokensTags.copy(t.id), enabled = true) { onCopy() },
            KitAction("Revoke", TokensTags.revoke(t.id), enabled = t.active) { onRevoke() },
        ), filledFirst = false)
    }
}

@Composable
private fun NewTokenCard(create: (String, String, List<String>, Int?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var audience by remember { mutableStateOf("*") }
    var scopes by remember { mutableStateOf("") }
    var days by remember { mutableStateOf("30") }
    val ttl = days.trim().toIntOrNull()
    val daysOk = days.isBlank() || (ttl != null && ttl in 1..3650)
    KitCard {
        KitSectionHeader("New token", "name, who may use it (host globs), what it may do, how long it lives; empty days = never expires", eyebrow = true)
        OutlinedTextField(name, { name = it }, label = { Text("Name", fontSize = KitDensity.caption) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(TokensTags.NEW_NAME))
        OutlinedTextField(audience, { audience = it }, label = { Text("Audience (hosts, comma separated, * = any)", fontSize = KitDensity.caption) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(TokensTags.NEW_AUDIENCE))
        OutlinedTextField(scopes, { scopes = it }, label = { Text("Scopes (space separated)", fontSize = KitDensity.caption) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(TokensTags.NEW_SCOPES))
        OutlinedTextField(days, { days = it }, label = { Text("Expires in days (1-3650)", fontSize = KitDensity.caption) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = !daysOk, modifier = Modifier.fillMaxWidth().testTag(TokensTags.NEW_DAYS))
        KitActionBar(listOf(KitAction("Generate", TokensTags.NEW_CREATE, enabled = name.isNotBlank() && daysOk) {
            create(name, audience, scopes.split(' ', ',').filter { it.isNotBlank() }, if (days.isBlank()) null else ttl)
        }))
    }
}
