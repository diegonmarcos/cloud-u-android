package com.diegonmarcos.cloudagenda.agent

import android.content.Context
import android.net.Uri

/** One message as Cloud Mail's agent provider lists it. [ref] is the link a task stores: "mail:<account>/<id>". */
data class MailItem(
    val id: String,
    val accountId: String,
    val mailboxId: String,
    val subject: String,
    val fromName: String,
    val fromEmail: String,
    val receivedAt: Long,
    val seen: Boolean,
) {
    val ref: String get() = MailLink.build(accountId, id)
    val sender: String get() = fromName.ifBlank { fromEmail }
}

/** The task-side link to a mail: a string, not a copy. Cloud Mail keeps the message. */
object MailLink {
    private const val PREFIX = "mail:"

    fun build(account: String, id: String): String = PREFIX + Uri.encode(account) + "/" + Uri.encode(id)

    /** (account, id), or null when [ref] is not a mail link. */
    fun parse(ref: String): Pair<String, String>? {
        if (!ref.startsWith(PREFIX)) return null
        val rest = ref.removePrefix(PREFIX)
        val slash = rest.indexOf('/')
        if (slash <= 0 || slash == rest.length - 1) return null
        return Uri.decode(rest.substring(0, slash)) to Uri.decode(rest.substring(slash + 1))
    }
}

/** Where the agent and the editor's Mail picker read mail from. Read-only by construction. */
interface MailSource {
    /** Null when the source can be read, else the reason (not installed, provider refused...). */
    fun unavailableReason(): String?

    /** Newest first. [subject] filters by substring, [since] is epoch millis. */
    fun recent(since: Long, limit: Int, subject: String = ""): List<MailItem>

    /** The plain text of a message, "" when it has none or cannot be read. */
    fun bodyText(accountId: String, id: String): String
}

/**
 * Cloud Mail on this phone, through its fleet agent door (#913): a read-only ContentProvider at
 * content://<mail package>.agentmail, guarded by the constellation's signature permission, so the only apps that can
 * call it carry the same key. There is no sign-in to share: Cloud Mail keeps its accounts and credentials, and this
 * app never sees them.
 *   messages?from=&subject=&since=&limit=   id, account_id, mailbox_id, subject, from_name, from_email, received_at, sort_key, seen
 *   body?account=&id=                       text, html, truncated
 */
class CloudMailProvider(private val context: Context, private val mailPackage: String) : MailSource {

    private fun base(path: String): Uri.Builder = Uri.Builder().scheme("content").authority("$mailPackage.agentmail").appendPath(path)

    override fun unavailableReason(): String? {
        val installed = runCatching { context.packageManager.getPackageInfo(mailPackage, 0) }.isSuccess
        if (!installed) return "Cloud Mail is not installed"
        val provider = context.packageManager.resolveContentProvider("$mailPackage.agentmail", 0)
        return if (provider == null) "Cloud Mail has no agent provider (update Cloud Mail)" else null
    }

    override fun recent(since: Long, limit: Int, subject: String): List<MailItem> {
        val uri = base("messages").apply {
            if (subject.isNotBlank()) appendQueryParameter("subject", subject.trim())
            if (since > 0) appendQueryParameter("since", since.toString())
            appendQueryParameter("limit", limit.coerceIn(1, 200).toString())
        }.build()
        val out = ArrayList<MailItem>()
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val id = c.getColumnIndexOrThrow("id"); val acc = c.getColumnIndexOrThrow("account_id"); val box = c.getColumnIndexOrThrow("mailbox_id")
            val subj = c.getColumnIndexOrThrow("subject"); val fn = c.getColumnIndexOrThrow("from_name"); val fe = c.getColumnIndexOrThrow("from_email")
            val at = c.getColumnIndexOrThrow("received_at"); val seen = c.getColumnIndexOrThrow("seen")
            while (c.moveToNext()) {
                out.add(MailItem(c.getString(id), c.getString(acc), c.getString(box), c.getString(subj).orEmpty(), c.getString(fn).orEmpty(),
                    c.getString(fe).orEmpty(), c.getLong(at), c.getInt(seen) != 0))
            }
        }
        return out
    }

    override fun bodyText(accountId: String, id: String): String {
        val uri = base("body").appendQueryParameter("account", accountId).appendQueryParameter("id", id).build()
        return context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow("text")).orEmpty() else ""
        }.orEmpty()
    }
}
