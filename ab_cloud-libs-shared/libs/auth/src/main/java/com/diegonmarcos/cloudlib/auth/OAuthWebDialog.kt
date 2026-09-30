package com.diegonmarcos.cloudlib.auth

import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

object OAuthWebTags {
    const val DIALOG = "auth_oauth_web_dialog"
}

/**
 * #684 THE FALLBACK for the authorization-code web flow, when the fleet's browser is not on
 * the phone: the same small WebView the Authelia way has always used, loading the provider's
 * authorize page and INTERCEPTING the redirect landing before it is dialed. The host is told
 * it is the fallback ([AuthMission.Outcome.NotInstalled]) and says so; this dialog does not.
 *
 * Navigation is confined to [allowHosts] plus the landing itself: a link off the provider is
 * refused and worded, never followed. The landing URL goes to [onLanded] once and the dialog
 * closes; nothing here reads, stores or logs the code.
 */
@Composable
fun OAuthWebDialog(
    title: String,
    url: String,
    redirectPrefix: String,
    allowHosts: List<String>,
    onLanded: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var web by remember { mutableStateOf<WebView?>(null) }
    var blocked by remember { mutableStateOf("") }
    DisposableEffect(Unit) { onDispose { web?.destroy(); web = null } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.testTag(OAuthWebTags.DIALOG)) {
                Text(stringResource(R.string.auth_oauth_caption), style = MaterialTheme.typography.bodySmall)
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    val target = request?.url?.toString().orEmpty()
                                    if (OAuthWeb.isLanding(redirectPrefix, target)) { onLanded(target); return true }
                                    if (!OAuthWeb.allowed(allowHosts, redirectPrefix, target)) {
                                        blocked = target
                                        return true
                                    }
                                    return false
                                }
                            }
                            loadUrl(url)
                            web = this
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(380.dp).padding(top = 10.dp),
                )
                if (blocked.isNotBlank()) Text(
                    stringResource(R.string.auth_oauth_blocked, allowHosts.joinToString(", "), blocked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.auth_close)) } },
    )
}
