package app.sterna.ui.inbox

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.sterna.core.jmap.model.Mailbox
import app.sterna.R
import app.sterna.ui.mailNav
import com.diegonmarcos.superapp.bottomnav.NavGroup
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.bottomnav.NavSection
import com.diegonmarcos.superapp.bottomnav.SecondRowMode
import com.diegonmarcos.superapp.navbartop2.NavSectionLayout

/** The icon (a catalog name of libs:ui-icons) a mailbox of this JMAP [role] is drawn with in the side menu. */
internal fun mailFolderIcon(role: String?): String = when (role) {
    "inbox" -> "inbox"
    "sent" -> "send"
    "drafts" -> "drafts"
    "trash" -> "delete"
    "junk" -> "report"
    "archive" -> "archive"
    null -> "label"
    else -> "folder"
}

/**
 * Cloud Mail's side menu as a [NavSection], built from the account's own mailboxes (never hardcoded): the standard folders (those
 * with a JMAP role) form the group "Folders", the account's own folders and labels (no role) the group "Labels", each with its
 * subfolders nested under it. [declared] is the section's declared navigation mode (build.json `mail` section). Pure.
 */
internal fun mailFolderSection(
    mailboxes: List<Mailbox>,
    displayName: (Mailbox) -> String,
    foldersLabel: String,
    labelsLabel: String,
    declared: SecondRowMode,
): NavSection {
    val nodes = mailboxTree(mailboxes, emptySet(), displayName)
    // Re-nest the depth-first flat list: each node's children are the following nodes one level deeper.
    fun build(from: Int, depth: Int): Pair<List<NavPage>, Int> {
        val out = ArrayList<NavPage>()
        var i = from
        while (i < nodes.size && nodes[i].depth >= depth) {
            val n = nodes[i]
            if (n.depth > depth) { i++; continue }
            val (kids, next) = build(i + 1, depth + 1)
            out += NavPage(n.mailbox.id, displayName(n.mailbox), mailFolderIcon(n.mailbox.role), pages = kids)
            i = next
        }
        return out to i
    }
    val roots = build(0, 0).first
    val roleOf = mailboxes.associate { it.id to it.role }
    val folders = roots.filter { roleOf[it.id] != null }
    val labels = roots.filter { roleOf[it.id] == null }
    val groups = buildList {
        if (folders.isNotEmpty()) add(NavGroup("folders", foldersLabel, folders, "folder"))
        if (labels.isNotEmpty()) add(NavGroup("labels", labelsLabel, labels, "label"))
    }
    return NavSection("mail", foldersLabel, "mail", folders + labels, rows = declared, groups = groups)
}

/**
 * [content] (the message list) with the mail account's folders and labels as the section's child-page navigation: the side menu by
 * default (build.json `mail` declares vertical_side_menu), or the horizontal rows when the user chose them in Settings. A pick
 * opens that mailbox, exactly as the drawer row does.
 */
@Composable
internal fun MailFolderNav(ui: MailUi, onSelect: (Mailbox) -> Unit, content: @Composable () -> Unit) {
    val context: Context = LocalContext.current
    val folders = androidx.compose.ui.res.stringResource(R.string.home_stat_folders)
    val labels = androidx.compose.ui.res.stringResource(R.string.side_menu_labels)
    val declared = remember { mailNav.section("mail")?.rows ?: SecondRowMode.SideMenu }
    val section = remember(ui.visibleMailboxes, folders, labels) {
        mailFolderSection(ui.visibleMailboxes, { mailboxLabel(context, it.role, it.name) }, folders, labels, declared)
    }
    NavSectionLayout(
        section = section,
        selectedId = ui.selectedMailboxId.takeIf { !ui.unified && !ui.unreadView },
        onSelect = { page -> ui.visibleMailboxes.firstOrNull { it.id == page.id }?.let(onSelect) },
        content = content,
    )
}
