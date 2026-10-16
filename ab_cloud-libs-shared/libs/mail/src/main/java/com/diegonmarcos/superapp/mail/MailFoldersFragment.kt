package com.diegonmarcos.superapp.mail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.os.bundleOf
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Slice B — Mailbox/get against the saved JMAP account.
 *
 * Reads `JmapPrefs` (set by [MailFragment]'s Connect button), runs the
 * RFC 8621 `Mailbox/get` method against `session.apiUrl`, and renders the
 * resulting tree as an indented list (a few dozen mailboxes: no lazy list needed).
 *
 * If creds are absent the user is told to log in via the Mail bottom-nav.
 *
 * 2026-10-16 owner decision: all apps must be Compose. A KitComposeFragment over the host theme's colours.
 */
class MailFoldersFragment : KitComposeFragment() {

    override fun palette(): KitPalette = requireContext().mailKitPalette()

    @Composable
    override fun Content() {
        val ctx = requireContext()
        val p = LocalKitPalette.current
        val prefs = remember { JmapPrefs(ctx) }
        val signedIn = prefs.email.isNotEmpty() && prefs.password.isNotEmpty()

        var status by remember { mutableStateOf(if (signedIn) ctx.getString(R.string.mail_folders_loading) else ctx.getString(R.string.mail_folders_login_first)) }
        var loading by remember { mutableStateOf(signedIn) }
        var mailboxes by remember { mutableStateOf<List<JmapClient.Mailbox>>(emptyList()) }

        LaunchedEffect(Unit) {
            if (!signedIn) return@LaunchedEffect
            val outcome = withContext(Dispatchers.IO) {
                val client = JmapClient(prefs.server, prefs.email, prefs.password)
                when (val s = client.discover()) {
                    is JmapClient.Result.Success     -> client.mailboxes(s.value) to null
                    is JmapClient.Result.HttpError   -> null to "HTTP ${s.code}: ${s.body.take(200)}"
                    is JmapClient.Result.NetworkError -> null to "net: ${s.message}"
                }
            }

            loading = false
            val (mailboxesResult, sessionErr) = outcome
            if (sessionErr != null) {
                status = ctx.getString(R.string.mail_folders_error, sessionErr)
                return@LaunchedEffect
            }
            when (val r = mailboxesResult!!) {
                is JmapClient.Result.Success -> {
                    if (r.value.isEmpty()) {
                        status = ctx.getString(R.string.mail_folders_empty)
                    } else {
                        status = ctx.resources.getQuantityString(R.plurals.mail_folders_count, r.value.size, r.value.size)
                        mailboxes = r.value
                    }
                }
                is JmapClient.Result.HttpError   ->
                    status = ctx.getString(R.string.mail_folders_error, "HTTP ${r.code}: ${r.body.take(200)}")
                is JmapClient.Result.NetworkError ->
                    status = ctx.getString(R.string.mail_folders_error, r.message)
            }
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp)) {
            MailStatus(loading, status)
            MailDivider()
            // Render the mailbox list as a top-down tree (parentId → child rows).
            val byParent = mailboxes.groupBy { it.parentId }
            fun rows(parentId: String?, depth: Int): List<Pair<JmapClient.Mailbox, Int>> =
                byParent[parentId].orEmpty().flatMap { mb -> listOf(mb to depth) + rows(mb.id, depth + 1) }
            for ((mb, depth) in rows(null, 0)) {
                Row(
                    // Tap a folder → host swaps the content pane to the
                    // per-folder messages view. Host (MainActivity) implements
                    // MailHost; we don't reach into app/-side R.ids ourselves.
                    Modifier.fillMaxWidth().clickable {
                        (activity as? MailHost)?.openMailPage(
                            pageId = MailPages.MESSAGES,
                            args = bundleOf(
                                MailMessagesFragment.ARG_FOLDER_ID   to mb.id,
                                MailMessagesFragment.ARG_FOLDER_NAME to mb.name,
                            ),
                        )
                    }.padding(start = (12 + depth * 20).dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(iconFor(mb.role), color = p.textPrimary, style = MaterialTheme.typography.titleSmall,
                        textAlign = TextAlign.Center, modifier = Modifier.width(24.dp))
                    Text(mb.name, color = p.textPrimary, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp).weight(1f))
                    MailDim(
                        if (mb.unreadEmails > 0) "${mb.unreadEmails} / ${mb.totalEmails}" else mb.totalEmails.toString(),
                        MaterialTheme.typography.labelSmall, Modifier.padding(start = 8.dp), mono = true,
                    )
                }
            }
        }
    }

    private fun iconFor(role: String?): String = when (role?.lowercase()) {
        "inbox"   -> "✉"   // ✉
        "drafts"  -> "✎"   // ✎
        "sent"    -> "➤"   // ➤
        "trash"   -> "✖"   // ✖
        "junk", "spam" -> "⚠"  // ⚠
        "archive" -> "⎘"   // ⎘ (printer-feed) — closest to a box glyph in stdlib
        else      -> "◯"   // ◯
    }

    companion object {
        fun newInstance() = MailFoldersFragment()
    }
}
