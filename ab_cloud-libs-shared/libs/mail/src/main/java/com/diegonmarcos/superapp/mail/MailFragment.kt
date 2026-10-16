package com.diegonmarcos.superapp.mail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Slice A — JMAP login + session display. Server / email / password persisted via
 * EncryptedSharedPreferences (JmapPrefs). On Connect: hits `<server>/.well-known/jmap`, parses the
 * session JSON, shows the api / download / upload / event-source URLs and the primary account.
 *
 * AUTHENTICATION (the gateway in front of the public endpoints, Authelia): None / Bearer token /
 * Authelia sign-in, per account, stored encrypted (MailAuthStore) and applied to EVERY request of the
 * account by its OkHttp interceptor + cookie jar (MailHttp). See MailAuthUi.
 *
 * Default server pulled from BuildConfig.JMAP_DEFAULT_SERVER which the gradle
 * eval injects from build.json::ui.sections[mail].jmap_default_server.
 *
 * 2026-10-16 owner decision: all apps must be Compose. A KitComposeFragment over the host theme's colours.
 */
class MailFragment : KitComposeFragment() {

    private val prettyJson = Json { prettyPrint = true; ignoreUnknownKeys = true }

    override fun palette(): KitPalette = requireContext().mailKitPalette()

    @Composable
    override fun Content() {
        val ctx = requireContext()
        val p = LocalKitPalette.current
        val prefs = remember { JmapPrefs(ctx) }
        val authStore = remember { MailAuthStore.of(ctx) }
        val scope = rememberCoroutineScope()

        var server by remember { mutableStateOf(prefs.server.ifEmpty { BuildConfig.JMAP_DEFAULT_SERVER }) }
        var email by remember { mutableStateOf(prefs.email) }
        var password by remember { mutableStateOf(prefs.password) }
        var showPassword by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf("") }
        var session by remember { mutableStateOf("") }
        var creds by remember { mutableStateOf(authStore.load(MailAuthStore.accountKey(server, email))) }
        var testing by remember { mutableStateOf(false) }
        var testMessage by remember { mutableStateOf("") }
        var testFailed by remember { mutableStateOf(false) }
        var signInOpen by remember { mutableStateOf(false) }

        fun key() = MailAuthStore.accountKey(server, email)
        fun keep(c: MailAuthCreds) { creds = c; authStore.save(key(), c) }

        /** Fold a failure into the stored credentials: a refusal under a gateway mode means sign in again. */
        fun failed(problem: ConnProblem, words: String) {
            val gated = creds.mode != MailAuthMode.NONE
            keep(creds.copy(lastOk = false, needsSignIn = gated && problem == ConnProblem.GATE_REJECTED,
                lastError = if (gated && problem == ConnProblem.GATE_REJECTED) "" else words))
            if (creds.mode == MailAuthMode.AUTHELIA && problem == ConnProblem.GATE_REJECTED) signInOpen = true
        }

        // A stored Authelia session that the server stopped honouring: ask for the sign-in straight away.
        LaunchedEffect(Unit) { if (creds.mode == MailAuthMode.AUTHELIA && creds.needsSignIn) signInOpen = true }

        fun test() {
            testing = true; testMessage = ""
            val snapshot = creds
            scope.launch {
                val out = withContext(Dispatchers.IO) { MailConnTest.run(ctx, server, email, password, snapshot) }
                testing = false
                testMessage = out.message; testFailed = !out.ok
                if (out.ok) keep(creds.copy(lastOk = true, needsSignIn = false, lastError = ""))
                else failed(out.problem ?: ConnProblem.OTHER, out.message)
            }
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            OutlinedTextField(
                value = server, onValueChange = { server = it }, singleLine = true,
                label = { Text(stringResource(R.string.mail_server_hint)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = email, onValueChange = { email = it }, singleLine = true,
                label = { Text(stringResource(R.string.mail_email_hint)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it }, singleLine = true,
                label = { Text(stringResource(R.string.mail_password_hint)) },
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "🙈" else "👁") }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            MailDivider(vertical = 12.dp)
            MailAuthSection(
                state = MailAuthSectionState(
                    mode = creds.mode, bearer = creds.bearer,
                    status = MailAuthStatuses.resolve(creds, System.currentTimeMillis()),
                    signedInAt = creds.signedInAt, cookieExpiresAt = creds.cookieExpiresAt,
                    testing = testing, testMessage = testMessage, testFailed = testFailed,
                ),
                onMode = { m -> keep(creds.copy(mode = m, needsSignIn = false, lastError = "")); testMessage = "" },
                onBearer = { t -> keep(creds.copy(bearer = t.trim(), needsSignIn = false, lastError = "")) },
                onTest = ::test,
                onSignIn = { signInOpen = true },
                onSignOut = {
                    clearAutheliaWebSession()
                    keep(creds.copy(sessionCookie = "", cookieExpiresAt = 0L, signedInAt = 0L, needsSignIn = false, lastOk = false))
                },
            )
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val srv = server.trim()
                        val mail = email.trim()
                        prefs.server = srv
                        prefs.email = mail
                        prefs.password = password
                        authStore.save(MailAuthStore.accountKey(srv, mail), creds)
                        status = ctx.getString(R.string.mail_status_connecting)
                        session = ""
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { prefs.jmapClient(ctx).discover() }
                            when (result) {
                                is JmapClient.Result.Success -> {
                                    val s = result.value
                                    keep(creds.copy(lastOk = true, needsSignIn = false, lastError = ""))
                                    status = ctx.getString(R.string.mail_status_ok, s.primaryAccountId)
                                    session = buildString {
                                        appendLine("${ctx.getString(R.string.mail_session_apiurl)}: ${s.apiUrl}")
                                        appendLine("downloadUrl: ${s.downloadUrl}")
                                        appendLine("uploadUrl:   ${s.uploadUrl}")
                                        appendLine("eventSrcUrl: ${s.eventSourceUrl}")
                                        appendLine()
                                        appendLine(ctx.getString(R.string.mail_session_accounts) + ":")
                                        s.accounts.forEach { (id, name) -> appendLine("  $id  → $name") }
                                        appendLine()
                                        appendLine("── full session ──")
                                        appendLine(prettyJson.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), s.raw))
                                    }
                                }
                                is JmapClient.Result.HttpError -> {
                                    val prob = ConnErrors.classify(result.code, null)
                                    status = if (prob == ConnProblem.HTTP) ctx.getString(R.string.mail_status_http, result.code, result.body.take(200))
                                    else MailConnTest.describe(ctx, prob, result.code, "")
                                    failed(prob, status)
                                }
                                is JmapClient.Result.NetworkError -> {
                                    val prob = ConnErrors.classify(null, result.cause)
                                    status = MailConnTest.describe(ctx, prob, null, result.message)
                                    failed(prob, status)
                                }
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.mail_connect)) }
                TextButton(onClick = {
                    authStore.forget(key())
                    clearAutheliaWebSession()
                    prefs.clear()
                    server = BuildConfig.JMAP_DEFAULT_SERVER
                    email = ""
                    password = ""
                    status = ""
                    session = ""
                    creds = MailAuthCreds()
                    testMessage = ""
                }) { Text(stringResource(R.string.mail_logout)) }
            }
            Text(status, color = p.textPrimary, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
            SelectionContainer {
                Text(session, color = p.textPrimary, style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        }
        if (signInOpen) AutheliaSignInDialog(
            onDismiss = { signInOpen = false },
            onCaptured = { v ->
                signInOpen = false
                keep(creds.copy(mode = MailAuthMode.AUTHELIA, sessionCookie = v, signedInAt = System.currentTimeMillis(),
                    cookieExpiresAt = 0L, needsSignIn = false, lastError = ""))
                test()
            },
        )
    }

    companion object { fun newInstance() = MailFragment() }
}
