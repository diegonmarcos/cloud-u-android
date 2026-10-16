package com.diegonmarcos.superapp.mail

import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.os.bundleOf
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Slice C2 — single-message view. Fetches Email/get with bodyValues and
 * fetchTextBodyValues=true (one round-trip) and renders the plain-text
 * body. HTML body deferred (needs WebView); `hasHtml` is exposed so the
 * UI can later add a "view HTML" toggle.
 *
 * 2026-10-16 owner decision: all apps must be Compose. A KitComposeFragment over the host theme's colours.
 */
class MailMessageBodyFragment : KitComposeFragment() {

    override fun palette(): KitPalette = requireContext().mailKitPalette()

    @Composable
    override fun Content() {
        val ctx = requireContext()
        val p = LocalKitPalette.current
        val args = arguments ?: Bundle.EMPTY
        val emailId = args.getString(ARG_EMAIL_ID, "")
        val prefs = remember { JmapPrefs(ctx) }
        val ready = prefs.signedIn(ctx) && emailId.isNotEmpty()

        // Use args as fast initial render so the user sees headers immediately
        // while we fetch the body.
        var subject by remember { mutableStateOf(args.getString(ARG_SUBJECT, "")) }
        var from by remember { mutableStateOf(args.getString(ARG_FROM, "")) }
        var to by remember { mutableStateOf("") }
        var date by remember { mutableStateOf(args.getString(ARG_DATE, "")) }
        var body by remember { mutableStateOf("") }
        var status by remember {
            mutableStateOf(if (ready) ctx.getString(R.string.mail_message_loading) else ctx.getString(R.string.mail_message_login_first))
        }
        var loading by remember { mutableStateOf(ready) }

        LaunchedEffect(emailId) {
            if (!ready) return@LaunchedEffect
            val outcome = withContext(Dispatchers.IO) {
                val client = prefs.jmapClient(ctx)
                when (val s = client.discover()) {
                    is JmapClient.Result.Success     -> client.emailBody(s.value, emailId) to null
                    is JmapClient.Result.HttpError   -> null to "HTTP ${s.code}: ${s.body.take(200)}"
                    is JmapClient.Result.NetworkError -> null to "net: ${s.message}"
                }
            }
            loading = false
            val (result, sessionErr) = outcome
            if (sessionErr != null) {
                status = ctx.getString(R.string.mail_message_error, sessionErr)
                return@LaunchedEffect
            }
            when (val r = result!!) {
                is JmapClient.Result.Success -> {
                    val b = r.value
                    // Refresh headers with the server-canonical values
                    if (b.subject.isNotBlank()) subject = b.subject
                    if (b.from.isNotBlank())    from    = b.from
                    if (b.to.isNotBlank())      to      = "to: " + b.to
                    date = formatDate(b.receivedAt)
                    body = b.text
                    status = if (b.hasHtml) ctx.getString(R.string.mail_message_has_html) else ""
                }
                is JmapClient.Result.HttpError ->
                    status = ctx.getString(R.string.mail_message_error, "HTTP ${r.code}: ${r.body.take(200)}")
                is JmapClient.Result.NetworkError ->
                    status = ctx.getString(R.string.mail_message_error, r.message)
            }
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(subject, color = p.textPrimary, style = MaterialTheme.typography.titleMedium)
            Text(from, color = p.textPrimary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
            MailDim(to, MaterialTheme.typography.labelSmall, Modifier.padding(top = 2.dp))
            MailDim(date, MaterialTheme.typography.labelSmall, Modifier.padding(top = 2.dp), alpha = 0.6f, mono = true)
            MailStatus(loading, status, Modifier.padding(top = 6.dp), MaterialTheme.typography.labelSmall)
            MailDivider(10.dp)
            SelectionContainer {
                Text(body, color = p.textPrimary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth())
            }
        }
    }

    private fun formatDate(iso: String): String = try {
        DateTimeFormatter.ofPattern("EEE d MMM yyyy HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.parse(iso))
    } catch (_: Throwable) { iso }

    companion object {
        const val ARG_EMAIL_ID = "email_id"
        const val ARG_SUBJECT  = "subject"
        const val ARG_FROM     = "from"
        const val ARG_DATE     = "date"

        fun newInstance(emailId: String, subject: String, from: String, date: String) =
            MailMessageBodyFragment().apply {
                arguments = bundleOf(
                    ARG_EMAIL_ID to emailId,
                    ARG_SUBJECT  to subject,
                    ARG_FROM     to from,
                    ARG_DATE     to date,
                )
            }
    }
}
