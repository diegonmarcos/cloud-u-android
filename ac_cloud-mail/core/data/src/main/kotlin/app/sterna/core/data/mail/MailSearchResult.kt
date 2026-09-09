package app.sterna.core.data.mail

import app.sterna.core.jmap.model.Email

/** What a search answered. [complete] is false when it stopped before seeing everything — a full
 *  page, a scan cap, a failed account — and the screen then says "at least N", never a total. */
data class MailSearchResult(
    val emails: List<Email>,
    val complete: Boolean = true,
)

/** Merge the per-account answers of a unified search into one capped list: fair by account, then
 *  newest-first. Keeping the newest [limit] of everything dropped whole accounts in silence, so one
 *  hit per account per round is taken instead. De-duplicated on (accountId, id): a same-server
 *  sibling can hold the same JMAP id, and those are two different messages. */
internal fun mergeAccountSearches(perAccount: List<MailSearchResult>, limit: Int): MailSearchResult {
    // receivedAt is an ISO-8601 UTC string, so lexicographic sort == chronological.
    val lists = perAccount.map { result ->
        result.emails.distinctBy { it.accountId to it.id }.sortedByDescending { it.receivedAt ?: "" }
    }
    val picked = LinkedHashMap<Pair<String?, String>, Email>()
    var round = 0
    while (picked.size < limit && lists.any { it.size > round }) {
        for (list in lists) {
            if (picked.size >= limit) break
            val email = list.getOrNull(round) ?: continue
            val key = email.accountId to email.id
            if (!picked.containsKey(key)) picked[key] = email
        }
        round++
    }
    val available = lists.flatten().distinctBy { it.accountId to it.id }.size
    return MailSearchResult(
        emails = picked.values.sortedByDescending { it.receivedAt ?: "" },
        complete = perAccount.all { it.complete } && picked.size == available,
    )
}
