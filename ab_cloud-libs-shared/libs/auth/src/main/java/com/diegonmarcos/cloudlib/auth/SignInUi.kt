package com.diegonmarcos.cloudlib.auth

import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.diegonmarcos.superapp.core.ConfigSyncClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * THE sign-in surface of the fleet (#587), in Compose, hosted by cloud-superapp
 * (Configs ▸ Profile ▸ Connect, step 1) and cloud-drive (Configs ▸ Sign in).
 *
 * One pill per declared provider the policy offers, dispatched on
 * [SignIn.Kind] alone — the bearer paste, the portal login in an embedded
 * browser, the device grant — each with its own dialog, and every successful
 * way ends in ONE call: [SignInHost.onSignedIn] with the artifact it fetched
 * (or the identity alone, for a provider that grants nothing more). The host
 * decides what to do with it; this surface writes NO store and keeps NO
 * credential beyond the request it was pasted for.
 *
 * The CHROME is the host's: [pill] draws a way in the host's own style, so
 * the cockpit and the drive card look like themselves. Nothing here names a
 * provider, an endpoint, a client id or a colour.
 */
interface SignInHost {
    /** A sign-in landed. Called on the main thread; the dialog has already closed. */
    fun onSignedIn(result: SignInResult)

    /** The cookie a browser login earned, for the host to reuse (memory only)
     *  for the vault route — one login, both fetches. */
    fun onWebSession(cookie: String) {}
}

data class SignInResult(
    val provider: SignIn.Provider,
    /** The address the sign-in proved, or "" when the provider answered none. */
    val identity: String,
    /** The config artifact, or null for an identity-only provider. */
    val artifact: JSONObject?,
    val bytes: Int,
    /** The bearer that just proved itself, for the host to store WITH the address
     *  it proved; "" for every other way. */
    val bearer: String = "",
)

object SignInTags {
    const val WAYS = "auth_sign_in_ways"
    const val STATUS = "auth_sign_in_status"
    fun way(id: String) = "auth_way:$id"
}

private sealed class Open {
    object None : Open()
    data class Bearer(val p: SignIn.Provider) : Open()
    data class Web(val p: SignIn.Provider) : Open()
    data class Device(val p: SignIn.Provider) : Open()
}

/**
 * @param policy the user's `auth_providers` off the artifact once known; empty offers every declared way.
 * @param pill how the host draws one way: its label and what a tap does.
 */
@Composable
fun SignInWays(
    host: SignInHost,
    policy: List<String> = emptyList(),
    modifier: Modifier = Modifier,
    pill: @Composable (label: String, tag: String, onClick: () -> Unit) -> Unit = { label, tag, onClick ->
        FilledTonalButton(onClick = onClick, modifier = Modifier.testTag(tag)) { Text(label) }
    },
) {
    var open by remember { mutableStateOf<Open>(Open.None) }
    var status by remember { mutableStateOf("") }
    Column(modifier.fillMaxWidth().testTag(SignInTags.WAYS)) {
        for (p in SignIn.offered(policy)) {
            if (!p.configured || p.kind == SignIn.Kind.UNKNOWN) {
                Text(stringResource(R.string.auth_not_configured, p.label), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
                continue
            }
            when (p.kind) {
                SignIn.Kind.AUTHELIA_BEARER -> pill(stringResource(R.string.auth_way_bearer, p.label), SignInTags.way(p.id)) { open = Open.Bearer(p) }
                SignIn.Kind.AUTHELIA_WEB -> pill(stringResource(R.string.auth_way_browser, p.label), SignInTags.way(p.id)) { open = Open.Web(p) }
                SignIn.Kind.DEVICE_FLOW -> pill(stringResource(R.string.auth_way_code, p.label), SignInTags.way(p.id)) { open = Open.Device(p) }
                SignIn.Kind.UNKNOWN -> Unit
            }
        }
        if (status.isNotBlank()) {
            Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp).testTag(SignInTags.STATUS))
        }
    }
    val identityOnly = stringResource(R.string.auth_identity_only)
    val landed: (SignInResult) -> Unit = { r ->
        open = Open.None
        // An identity-only way says so here, on the surface, since no artifact follows.
        status = if (r.artifact == null) identityOnly.format(r.provider.label, r.identity.ifBlank { "—" }) else ""
        host.onSignedIn(r)
    }
    when (val o = open) {
        Open.None -> Unit
        is Open.Bearer -> BearerDialog(o.p, onDismiss = { open = Open.None }, onLanded = landed)
        is Open.Web -> WebAuthDialog(o.p, host, onDismiss = { open = Open.None }, onLanded = landed)
        is Open.Device -> DeviceFlowDialog(o.p, onDismiss = { open = Open.None }, onLanded = landed)
    }
}

/** A dialog's status line: the outcome in place. A dialog that vanished on tap is how
 *  "nothing happened" became the most common bug report on the bearer flow. */
@Composable
private fun StatusLine(text: String, failed: Boolean) {
    if (text.isBlank()) return
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp).testTag(SignInTags.STATUS),
    )
}

// ── Authelia · bearer ───────────────────────────────────────────────────

@Composable
private fun BearerDialog(p: SignIn.Provider, onDismiss: () -> Unit, onLanded: (SignInResult) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val endpoint = remember { ConfigArtifact.endpoint() }
    var token by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val emptyMsg = stringResource(R.string.auth_bearer_empty)
    val fetching = stringResource(R.string.auth_fetching, endpoint)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
        }.onSuccess { token = SignIn.extractToken(it) }
            .onFailure { status = ctx.getString(R.string.auth_file_unreadable, it.message ?: it.javaClass.simpleName); failed = true }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.auth_bearer_title, p.label)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.auth_bearer_caption, p.label, endpoint), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = token, onValueChange = { token = it },
                    placeholder = { Text(stringResource(R.string.auth_bearer_hint)) },
                    minLines = 2, maxLines = 4,
                    // Keep the token off the keyboard's learned-words / suggestion store.
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
                TextButton(onClick = { picker.launch("*/*") }) { Text(stringResource(R.string.auth_bearer_from_file)) }
                StatusLine(status, failed)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                val t = token.trim()
                if (t.isEmpty()) { status = emptyMsg; failed = true; return@TextButton }
                busy = true; failed = false; status = fetching
                scope.launch {
                    val outcome = withContext(Dispatchers.IO) { ConfigArtifact.fetchWithBearer(t) }
                    busy = false
                    when (outcome) {
                        is ConfigSyncClient.Outcome.Failed -> { failed = true; status = "✗ ${outcome.kind}\n${outcome.message}" }
                        is ConfigSyncClient.Outcome.Ok -> onLanded(SignInResult(p, identityOf(outcome.body), outcome.body, outcome.bytes, bearer = t))
                    }
                }
            }) { Text(stringResource(R.string.auth_bearer_go)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.auth_close)) } },
    )
}

// ── Authelia · browser login ────────────────────────────────────────────

/**
 * Why a WebView and not a Custom Tab: the whole point is to get the cookie
 * back, and a Custom Tab's cookie jar belongs to the browser, not to this
 * app. The WebView's jar is readable through [CookieManager], which is the
 * only reason this way can hand a credential to [ConfigArtifact.fetchWithCookie].
 * The cookie is never persisted by us — it lives in the WebView jar for as
 * long as the app keeps it and is dropped from memory after the request.
 */
@Composable
private fun WebAuthDialog(p: SignIn.Provider, host: SignInHost, onDismiss: () -> Unit, onLanded: (SignInResult) -> Unit) {
    val scope = rememberCoroutineScope()
    val endpoint = remember { ConfigArtifact.endpoint() }
    var status by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf<WebView?>(null) }
    val noCookie = stringResource(R.string.auth_web_no_cookie, endpoint)
    val fetching = stringResource(R.string.auth_fetching, endpoint)
    // Free the WebView the moment the dialog leaves the tree.
    DisposableEffect(Unit) { onDispose { web?.destroy(); web = null } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.auth_web_title, p.label)) },
        text = {
            Column {
                Text(stringResource(R.string.auth_web_caption, endpoint), style = MaterialTheme.typography.bodySmall)
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true      // Authelia's portal is a JS app
                            settings.domStorageEnabled = true      // and keeps its state in DOM storage
                            webViewClient = WebViewClient()
                            CookieManager.getInstance().setAcceptCookie(true)
                            loadUrl(endpoint)
                            web = this
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(380.dp).padding(top = 10.dp),
                )
                StatusLine(status, failed)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                val cookie = CookieManager.getInstance().getCookie(endpoint).orEmpty()
                if (cookie.isBlank()) { status = noCookie; failed = true; return@TextButton }
                busy = true; failed = false; status = fetching
                scope.launch {
                    val outcome = withContext(Dispatchers.IO) { ConfigArtifact.fetchWithCookie(cookie) }
                    busy = false
                    when (outcome) {
                        is ConfigSyncClient.Outcome.Failed -> { failed = true; status = "✗ ${outcome.kind}\n${outcome.message}" }
                        is ConfigSyncClient.Outcome.Ok -> {
                            // The same session serves the vault route (#573): one login, both fetches.
                            host.onWebSession(cookie)
                            onLanded(SignInResult(p, identityOf(outcome.body), outcome.body, outcome.bytes))
                        }
                    }
                }
            }) { Text(stringResource(R.string.auth_web_go)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.auth_close)) } },
    )
}

// ── OAuth device grant · any declared provider ──────────────────────────

/**
 * Approve a short code in a browser, then either read the artifact out of the
 * vault repo with the token (a provider that grants `repo_artifact`) or keep
 * only the identity the token proves (every other provider). Closing the
 * dialog cancels the polling: the loop runs on this composable's scope.
 */
@Composable
private fun DeviceFlowDialog(p: SignIn.Provider, onDismiss: () -> Unit, onLanded: (SignInResult) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val readsRepo = p.grants(SignIn.GRANT_REPO_ARTIFACT)
    val cs = AuthDeclaration.configSource
    var status by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    fun word(phase: DeviceGrant.Phase): String = when (phase) {
        DeviceGrant.Phase.Asking -> ctx.getString(R.string.auth_asking, p.label)
        is DeviceGrant.Phase.Prompt -> ctx.getString(
            R.string.auth_code_prompt, phase.code.verificationUri, phase.code.userCode,
            phase.pending.ifBlank { ctx.getString(R.string.auth_code_expiry, phase.code.expiresInSeconds / 60) },
        )
        is DeviceGrant.Phase.Approved -> ctx.getString(R.string.auth_approved, phase.identity.ifBlank { p.label })
        is DeviceGrant.Phase.Failed -> "✗ ${phase.message}"
        DeviceGrant.Phase.Expired -> ctx.getString(R.string.auth_code_expired)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.auth_device_title, p.label)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    if (readsRepo) stringResource(R.string.auth_device_caption_repo, p.label, cs.gitRepo, cs.gitPath)
                    else stringResource(R.string.auth_device_caption_identity, p.label),
                    style = MaterialTheme.typography.bodySmall,
                )
                StatusLine(status, failed)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true; failed = false
                scope.launch {
                    val approval = DeviceGrant.run(
                        p,
                        report = { phase -> status = word(phase); failed = phase is DeviceGrant.Phase.Failed || phase is DeviceGrant.Phase.Expired },
                        openUrl = { uriHandler.openUri(it) },
                    )
                    if (approval == null) { busy = false; return@launch }
                    if (!readsRepo) {
                        busy = false
                        onLanded(SignInResult(p, approval.identity, null, 0))
                        return@launch
                    }
                    status = ctx.getString(R.string.auth_fetching, cs.gitRepo)
                    val outcome = withContext(Dispatchers.IO) { ConfigArtifact.fetchFromRepo(approval.accessToken) }
                    busy = false
                    when (outcome) {
                        is ConfigSyncClient.Outcome.Failed -> { failed = true; status = "✗ ${outcome.kind}\n${outcome.message}" }
                        is ConfigSyncClient.Outcome.Ok -> onLanded(SignInResult(p, approval.identity.ifBlank { identityOf(outcome.body) }, outcome.body, outcome.bytes))
                    }
                }
            }) { Text(stringResource(R.string.auth_start)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.auth_close)) } },
    )
}

/** The address a fetched artifact says it is for: its primary identity, else "". */
private fun identityOf(artifact: JSONObject): String =
    UserRegistry.parse(artifact)?.primaryIdentity?.email.orEmpty()
