package com.diegonmarcos.superapp.mail

import android.os.Bundle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
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
 * Slice C — per-folder message list via JMAP Email/query → Email/get
 * back-reference (one HTTP round-trip). Last 50 messages, newest first.
 *
 * Args (set by [MailFoldersFragment] click handler):
 *  - ARG_FOLDER_ID    JMAP mailbox id passed into Email/query.filter.inMailbox
 *  - ARG_FOLDER_NAME  display title only
 *
 * 2026-10-16 owner decision: all apps must be Compose. A KitComposeFragment over the host theme's colours.
 */
class MailMessagesFragment : KitComposeFragment() {

    override fun palette(): KitPalette = requireContext().mailKitPalette()

    @Composable
    override fun Content() {
        val ctx = requireContext()
        val p = LocalKitPalette.current
        val args = arguments ?: Bundle.EMPTY
        val folderName = args.getString(ARG_FOLDER_NAME, "(unknown)")
        val folderId   = args.getString(ARG_FOLDER_ID,   "")
        val prefs = remember { JmapPrefs(ctx) }
        val signedIn = prefs.signedIn(ctx)

        var status by remember {
            mutableStateOf(
                when {
                    !signedIn -> ctx.getString(R.string.mail_messages_login_first)
                    folderId.isEmpty() -> ctx.getString(R.string.mail_messages_no_folder)
                    else -> ctx.getString(R.string.mail_messages_loading)
                },
            )
        }
        var loading by remember { mutableStateOf(signedIn && folderId.isNotEmpty()) }
        var emails by remember { mutableStateOf<List<JmapClient.Email>>(emptyList()) }

        LaunchedEffect(folderId) {
            if (!signedIn || folderId.isEmpty()) return@LaunchedEffect
            val outcome = withContext(Dispatchers.IO) {
                val client = prefs.jmapClient(ctx)
                when (val s = client.discover()) {
                    is JmapClient.Result.Success     -> client.emails(s.value, folderId) to null
                    is JmapClient.Result.HttpError   -> null to "HTTP ${s.code}: ${s.body.take(200)}"
                    is JmapClient.Result.NetworkError -> null to "net: ${s.message}"
                }
            }
            loading = false
            val (result, sessionErr) = outcome
            if (sessionErr != null) {
                status = ctx.getString(R.string.mail_messages_error, sessionErr)
                return@LaunchedEffect
            }
            when (val r = result!!) {
                is JmapClient.Result.Success ->
                    if (r.value.isEmpty()) {
                        status = ctx.getString(R.string.mail_messages_empty)
                    } else {
                        status = ctx.resources.getQuantityString(R.plurals.mail_messages_count, r.value.size, r.value.size)
                        emails = r.value
                    }
                is JmapClient.Result.HttpError ->
                    status = ctx.getString(R.string.mail_messages_error, "HTTP ${r.code}: ${r.body.take(200)}")
                is JmapClient.Result.NetworkError ->
                    status = ctx.getString(R.string.mail_messages_error, r.message)
            }
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(folderName, color = p.textPrimary, style = MaterialTheme.typography.titleMedium)
            MailStatus(loading, status, Modifier.padding(top = 4.dp))
            MailDivider()
            for (email in emails) {
                Column(
                    Modifier.fillMaxWidth().clickable {
                        (activity as? MailHost)?.openMailPage(
                            pageId = MailPages.MESSAGE,
                            args = bundleOf(
                                MailMessageBodyFragment.ARG_EMAIL_ID to email.id,
                                MailMessageBodyFragment.ARG_SUBJECT  to email.subject,
                                MailMessageBodyFragment.ARG_FROM     to email.from,
                                MailMessageBodyFragment.ARG_DATE     to email.receivedAt,
                            ),
                        )
                    }.padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(email.from.ifBlank { "(no sender)" }, color = p.textPrimary,
                            style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f))
                        MailDim(formatDate(email.receivedAt), MaterialTheme.typography.labelSmall,
                            Modifier.padding(start = 8.dp), mono = true)
                    }
                    Text(email.subject.ifBlank { "(no subject)" }, color = p.textPrimary,
                        style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp))
                    MailDim(email.preview, MaterialTheme.typography.bodySmall, Modifier.padding(top = 2.dp))
                }
            }
        }
    }

    /** Format "2026-05-29T19:01:23Z" → "May 29 19:01" in the device tz.
     *  Falls back to raw on any parse failure. */
    private fun formatDate(iso: String): String = try {
        val instant = Instant.parse(iso)
        DateTimeFormatter.ofPattern("MMM d HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(instant)
    } catch (_: Throwable) {
        iso
    }

    companion object {
        const val ARG_FOLDER_ID   = "folder_id"
        const val ARG_FOLDER_NAME = "folder_name"
        fun newInstance(folderId: String, folderName: String) = MailMessagesFragment().apply {
            arguments = bundleOf(ARG_FOLDER_ID to folderId, ARG_FOLDER_NAME to folderName)
        }
    }
}
