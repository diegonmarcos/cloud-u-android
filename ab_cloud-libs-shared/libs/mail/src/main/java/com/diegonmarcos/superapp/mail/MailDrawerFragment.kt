package com.diegonmarcos.superapp.mail

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * Mail section drawer page — placeholder. Reads JmapPrefs to show the
 * currently-configured account; folder list is hardcoded for now and will
 * grow into a Mailbox/get-driven list once Slice B lands.
 *
 * This is the slot where FairEmail's account/folder tree will eventually
 * cherry-pick; the drawer-pagination plumbing is ready for it.
 *
 * 2026-10-16 owner decision: all apps must be Compose. A KitComposeFragment over the host theme's colours.
 */
class MailDrawerFragment : KitComposeFragment() {

    override fun palette(): KitPalette = requireContext().mailKitPalette()

    @Composable
    override fun Content() {
        val p = LocalKitPalette.current
        val prefs = remember { JmapPrefs(requireContext()) }
        val accountLabel = prefs.email.ifEmpty { "(not signed in)" }
        val server = prefs.server.ifEmpty { BuildConfig.JMAP_DEFAULT_SERVER }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {
            val store = remember { MailAuthStore.of(requireContext()) }
            // Every stored account with its auth chip (the active login alone when no list is stored).
            val rows = prefs.accounts().map { it.email to it.jmap }.ifEmpty { listOf(accountLabel to server) }
            for ((mail, srv) in rows) {
                val chip = MailAuthStatuses.resolve(store.load(MailAuthStore.accountKey(srv.ifEmpty { server }, mail)), System.currentTimeMillis())
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(mail, color = p.textPrimary, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        MailDim(srv.ifEmpty { server }, MaterialTheme.typography.labelSmall)
                    }
                    MailAuthChip(chip)
                }
            }
            MailDivider()
            // Placeholder folder list. The labels live here so they read uniformly; eventually
            // replaced by a list driven by Mailbox/get.
            for (label in FOLDERS) {
                Text(label, color = p.accent, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp))
            }
        }
    }

    companion object {
        fun newInstance() = MailDrawerFragment()

        private val FOLDERS = listOf("📥  Inbox", "📝  Drafts", "📤  Sent", "📦  Archive", "🚫  Spam", "🗑  Trash")
    }
}
