package com.diegonmarcos.superapp.mail

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import java.text.DateFormat
import java.util.Date

/** Test tags of the auth section (the Compose UI test and the account-form tester read these). */
object MailAuthTags {
    const val SECTION = "mail_auth_section"
    const val CHIP = "mail_auth_chip"
    const val TOKEN = "mail_auth_token"
    const val SHOW = "mail_auth_show"
    const val PASTE = "mail_auth_paste"
    const val TEST = "mail_auth_test"
    const val SIGN_IN = "mail_auth_sign_in"
    const val SIGN_OUT = "mail_auth_sign_out"
    const val RESULT = "mail_auth_result"
    fun mode(m: MailAuthMode) = "mail_auth_mode_${m.name.lowercase()}"
}

/** What the section draws: the account's credentials, its status, and a Test connection in flight. */
data class MailAuthSectionState(
    val mode: MailAuthMode = MailAuthMode.NONE,
    val bearer: String = "",
    val status: MailAuthStatus = MailAuthStatus.CONNECTED,
    val signedInAt: Long = 0L,
    val cookieExpiresAt: Long = 0L,
    val testing: Boolean = false,
    /** Result of the last Test connection, already worded; blank = none yet. */
    val testMessage: String = "",
    val testFailed: Boolean = false,
)

@Composable
fun mailAuthStatusLabel(s: MailAuthStatus): String = stringResource(when (s) {
    MailAuthStatus.CONNECTED -> R.string.mail_auth_status_connected
    MailAuthStatus.TOKEN -> R.string.mail_auth_status_token
    MailAuthStatus.SIGNED_IN -> R.string.mail_auth_status_signed_in
    MailAuthStatus.NEEDS_SIGN_IN -> R.string.mail_auth_status_needs_sign_in
    MailAuthStatus.ERROR -> R.string.mail_auth_status_error
})

/** The per-account status chip: accent for a good state, the error role when it needs attention. */
@Composable
fun MailAuthChip(status: MailAuthStatus, modifier: Modifier = Modifier) {
    val p = LocalKitPalette.current
    val bad = status == MailAuthStatus.NEEDS_SIGN_IN || status == MailAuthStatus.ERROR
    val ink = if (bad) MaterialTheme.colorScheme.error else p.accent
    Surface(
        modifier = modifier.testTag(MailAuthTags.CHIP),
        shape = RoundedCornerShape(50), color = p.surfaceSelected, contentColor = ink,
        border = BorderStroke(1.dp, ink),
    ) {
        Text(mailAuthStatusLabel(status), style = MaterialTheme.typography.labelMedium, color = ink,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
    }
}

/**
 * Account ▸ Authentication: None / Bearer token / Authelia sign-in. Stateless, so the account form
 * hosts it and a test can drive it; [onSignIn] opens [AutheliaSignInDialog].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailAuthSection(
    state: MailAuthSectionState,
    onMode: (MailAuthMode) -> Unit,
    onBearer: (String) -> Unit,
    onTest: () -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalKitPalette.current
    val ctx = LocalContext.current
    var reveal by remember { mutableStateOf(false) }
    val modes = MailAuthMode.entries
    Column(modifier.fillMaxWidth().testTag(MailAuthTags.SECTION), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.mail_auth_title), color = p.textPrimary, style = MaterialTheme.typography.titleSmall)
            MailAuthChip(state.status)
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            modes.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = state.mode == m, onClick = { onMode(m) },
                    shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = p.accent, activeContentColor = p.tileInk,
                        inactiveContainerColor = p.surface, inactiveContentColor = p.textPrimary,
                        activeBorderColor = p.accent, inactiveBorderColor = p.hairline),
                    modifier = Modifier.testTag(MailAuthTags.mode(m)),
                ) {
                    Text(stringResource(when (m) {
                        MailAuthMode.NONE -> R.string.mail_auth_mode_none
                        MailAuthMode.BEARER -> R.string.mail_auth_mode_bearer
                        MailAuthMode.AUTHELIA -> R.string.mail_auth_mode_authelia
                    }), maxLines = 1)
                }
            }
        }
        when (state.mode) {
            MailAuthMode.NONE -> Text(stringResource(R.string.mail_auth_none_hint), color = p.textSecondary,
                style = MaterialTheme.typography.bodySmall)
            MailAuthMode.BEARER -> {
                OutlinedTextField(
                    value = state.bearer, onValueChange = onBearer, singleLine = true,
                    label = { Text(stringResource(R.string.mail_auth_token_hint)) },
                    visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    trailingIcon = {
                        TextButton(onClick = { reveal = !reveal }, modifier = Modifier.testTag(MailAuthTags.SHOW)) {
                            Text(stringResource(if (reveal) R.string.mail_auth_hide else R.string.mail_auth_show))
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag(MailAuthTags.TOKEN),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pasteFromClipboard(ctx)?.let(onBearer) }, modifier = Modifier.testTag(MailAuthTags.PASTE)) {
                        Text(stringResource(R.string.mail_auth_paste))
                    }
                    Button(onClick = onTest, enabled = !state.testing && state.bearer.isNotBlank(), modifier = Modifier.testTag(MailAuthTags.TEST)) {
                        Text(stringResource(if (state.testing) R.string.mail_auth_testing else R.string.mail_auth_test))
                    }
                }
            }
            MailAuthMode.AUTHELIA -> {
                val signed = state.status == MailAuthStatus.SIGNED_IN
                Text(authSessionLine(state), color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onSignIn, modifier = Modifier.testTag(MailAuthTags.SIGN_IN)) {
                        Text(stringResource(if (signed) R.string.mail_auth_sign_in_again else R.string.mail_auth_sign_in))
                    }
                    if (signed) OutlinedButton(onClick = onSignOut, modifier = Modifier.testTag(MailAuthTags.SIGN_OUT)) {
                        Text(stringResource(R.string.mail_auth_sign_out))
                    }
                    OutlinedButton(onClick = onTest, enabled = !state.testing, modifier = Modifier.testTag(MailAuthTags.TEST)) {
                        Text(stringResource(if (state.testing) R.string.mail_auth_testing else R.string.mail_auth_test))
                    }
                }
            }
        }
        if (state.testMessage.isNotBlank()) {
            Text(state.testMessage, style = MaterialTheme.typography.bodySmall,
                color = if (state.testFailed) MaterialTheme.colorScheme.error else p.textPrimary,
                modifier = Modifier.testTag(MailAuthTags.RESULT))
        }
    }
}

@Composable
private fun authSessionLine(s: MailAuthSectionState): String {
    if (s.status == MailAuthStatus.NEEDS_SIGN_IN || s.signedInAt == 0L) return stringResource(R.string.mail_auth_not_signed_in)
    val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    val since = stringResource(R.string.mail_auth_signed_in_since, fmt.format(Date(s.signedInAt)))
    val until = if (s.cookieExpiresAt > 0) stringResource(R.string.mail_auth_expires, fmt.format(Date(s.cookieExpiresAt)))
    else stringResource(R.string.mail_auth_expires_session)
    return "$since · $until"
}

private fun pasteFromClipboard(ctx: Context): String? {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim()?.ifEmpty { null }
}

/**
 * The Authelia web sign-in, in-app: the portal in a WebView confined to the fleet domain; Done
 * reads the `authelia_session` cookie out of the WebView jar (the way libs:auth's web way and
 * cloud-browser's auth mission do) and hands only that value to [onCaptured]. Never logged.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AutheliaSignInDialog(onCaptured: (String) -> Unit, onDismiss: () -> Unit) {
    var missing by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = LocalKitPalette.current.surface) {
            Column(Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { c ->
                        WebView(c).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            CookieManager.getInstance().setAcceptCookie(true)
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    val host = request?.url?.host.orEmpty()
                                    return !MailAuthScope.allows(AutheliaPortal.DOMAIN, host)   // stay on the fleet domain
                                }
                            }
                            loadUrl(AutheliaPortal.URL)
                        }
                    },
                    onRelease = { it.destroy() },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                if (missing) Text(stringResource(R.string.mail_auth_no_cookie), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
                    Button(onClick = {
                        val v = AutheliaPortal.sessionValue(CookieManager.getInstance().getCookie(AutheliaPortal.URL).orEmpty())
                        if (v.isEmpty()) missing = true else onCaptured(v)
                    }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.mail_auth_done)) }
                }
            }
        }
    }
}

/** Forget the session cookie in the WebView jar too, so Sign out is a real sign out. */
fun clearAutheliaWebSession() {
    runCatching {
        CookieManager.getInstance().setCookie(AutheliaPortal.URL,
            "${AutheliaPortal.COOKIE}=; Max-Age=0; Domain=${AutheliaPortal.DOMAIN}; Path=/")
        CookieManager.getInstance().flush()
    }
}
