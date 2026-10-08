package com.diegonmarcos.cloudsearch.core.agents

import com.diegonmarcos.cloudsearch.core.Templates

/**
 * The two fleet doors an agent READS through, as the agent sees them. Both are read-only: there is no method on
 * either that sends, posts, submits, clicks, fills, moves or deletes, and none can be added without changing this
 * file (test-agents-draft-only.sh holds that).
 */
data class MailHeader(
    val id: String, val accountId: String, val subject: String, val fromName: String, val fromEmail: String,
    val receivedAt: String, val sortKey: Long, val seen: Boolean,
)

data class MailBody(val text: String, val html: String, val truncated: Boolean)

interface MailSource {
    fun messages(from: String, subject: String, sinceMs: Long, limit: Int): List<MailHeader>
    fun body(accountId: String, id: String): MailBody?
}

/** The text of one page. [ok] false carries [error]; nothing is thrown across the door. */
data class PageText(val ok: Boolean, val url: String, val title: String, val text: String, val truncated: Boolean, val error: String)

interface PageSource {
    fun text(url: String, maxChars: Int): PageText
}

/**
 * The client side of the two contracts, declared once in build.json::search.agents.engines and held equal to the
 * engines' own constants by the JVM suite and by the engines' testers.
 */
object Ipc {
    /** `content://<authority>/messages?from=&subject=&since=&limit=` (empty filters are left out). */
    fun messagesUri(mail: AgentsConfig.MailEngine, mailPackage: String, from: String, subject: String, sinceMs: Long, limit: Int): String {
        val q = ArrayList<String>()
        if (from.isNotBlank()) q += "${mail.paramFrom}=${enc(from)}"
        if (subject.isNotBlank()) q += "${mail.paramSubject}=${enc(subject)}"
        q += "${mail.paramSince}=$sinceMs"
        q += "${mail.paramLimit}=${limit.coerceIn(1, mail.maxLimit)}"
        return "content://${authority(mailPackage, mail.authoritySuffix)}/${mail.pathMessages}?" + q.joinToString("&")
    }

    /** `content://<authority>/body?account=&id=` */
    fun bodyUri(mail: AgentsConfig.MailEngine, mailPackage: String, accountId: String, id: String): String =
        "content://${authority(mailPackage, mail.authoritySuffix)}/${mail.pathBody}?${mail.paramAccount}=${enc(accountId)}&${mail.paramId}=${enc(id)}"

    fun authority(pkg: String, suffix: String) = "$pkg.$suffix"

    /** The extras of the browser's fetch call. */
    fun fetchExtras(b: AgentsConfig.BrowserEngine, url: String, maxChars: Int): Map<String, Any> =
        mapOf(b.extraUrl to url, b.extraMaxChars to maxChars)

    /** The extras of the browser's open activity; the group is left out when blank. */
    fun openExtras(b: AgentsConfig.BrowserEngine, url: String, group: String): Map<String, String> =
        if (group.isBlank()) mapOf(b.extraUrl to url) else mapOf(b.extraUrl to url, b.extraGroup to group)

    /** The columns a cursor must carry for [mail]'s messages; the first missing one is named. */
    fun missingColumn(have: List<String>, want: List<String>): String? = want.firstOrNull { it !in have }

    private fun enc(s: String) = Templates.enc(s)
}
