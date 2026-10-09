package app.sterna.core.data.mail

import app.sterna.core.data.db.DomainIndexRow
import app.sterna.core.data.db.FromDomainUpdate

/**
 * The sender's DOMAIN, as the list's "Group by Domain" keys it: the registrable domain of the From
 * address, so `notifications.github.com` and `github.com` are one group, and `shop.example.co.uk`
 * is `example.co.uk`, not `co.uk`.
 *
 * Decided once when a row is cached (schema v31, `emails.fromDomain`) and backfilled for older rows,
 * so the grouping SQL reads a column instead of parsing every address on every page.
 */
object SenderDomain {
    /** What a group whose senders have no usable address is called. */
    const val UNKNOWN = "(unknown)"

    /** The grouping key's prefix for a domain group, so a domain key can never equal a thread id. */
    const val DOMAIN_KEY_PREFIX = "\u0001domain\u0001"

    /**
     * The public suffixes of more than one label that real mail is commonly sent from. Not the whole
     * Public Suffix List: a suffix missing here only costs a group named one label too short
     * (`co.xx` instead of `example.co.xx`), never a message in the wrong account or folder.
     */
    val MULTI_LABEL_SUFFIXES: Set<String> = setOf(
        "co.uk", "org.uk", "ac.uk", "gov.uk", "me.uk", "ltd.uk", "plc.uk", "net.uk", "sch.uk", "nhs.uk",
        "com.br", "net.br", "org.br", "gov.br", "edu.br",
        "com.au", "net.au", "org.au", "edu.au", "gov.au", "id.au",
        "co.jp", "ne.jp", "or.jp", "ac.jp", "go.jp", "gr.jp",
        "co.nz", "net.nz", "org.nz", "govt.nz",
        "co.za", "org.za",
        "co.in", "net.in", "org.in", "gov.in",
        "co.kr", "or.kr",
        "co.id", "co.il", "co.th",
        "com.mx", "com.ar", "com.tr", "com.cn", "net.cn", "org.cn", "com.hk", "com.tw", "com.sg",
        "com.my", "com.ua", "com.pl", "com.es", "com.pt",
    )

    private val LABEL = Regex("""[\p{L}\p{N}](?:[\p{L}\p{N}-]*[\p{L}\p{N}])?""")

    /**
     * The host of [address]: the part after its last '@', trimmed, lower-cased, its trailing dot(s)
     * removed. Null when there is none, or it is not a host name (empty labels, spaces, an IP literal).
     */
    fun host(address: String?): String? {
        val raw = address?.trim() ?: return null
        val at = raw.lastIndexOf('@')
        if (at < 0) return null
        val host = raw.substring(at + 1).trim().lowercase().trimEnd('.')
        if (host.isEmpty()) return null
        return host.takeIf { h -> h.split('.').all { LABEL.matches(it) } }
    }

    /** The registrable domain of [address] (see the class doc), or null when it has no usable host. */
    fun registrable(address: String?): String? {
        val host = host(address) ?: return null
        val labels = host.split('.')
        if (labels.size <= 2) return host
        val lastTwo = labels.takeLast(2).joinToString(".")
        val keep = if (lastTwo in MULTI_LABEL_SUFFIXES) 3 else 2
        return labels.takeLast(keep).joinToString(".")
    }

    /** What a cache row stores: the registrable domain, or "" for an address without one ([UNKNOWN]). */
    fun indexed(fromEmail: String?): String = registrable(fromEmail).orEmpty()

    /** The group heading for a stored or computed domain: the domain, or [UNKNOWN] when blank. */
    fun label(domain: String?): String = domain?.takeIf { it.isNotEmpty() } ?: UNKNOWN

    /** The grouping key of a message from [fromEmail] when the list is grouped by domain. */
    fun domainKey(fromEmail: String?): String = DOMAIN_KEY_PREFIX + indexed(fromEmail)

    /**
     * The backfill loop for rows cached before the column existed, apart from Room so it can be run
     * on its own: take a batch of unindexed rows with [next], store each one's domain with [store],
     * until none is left. Returns how many it indexed.
     */
    suspend fun backfill(
        batch: Int,
        next: suspend (limit: Int) -> List<DomainIndexRow>,
        store: suspend (List<FromDomainUpdate>) -> Unit,
    ): Int {
        var total = 0
        while (true) {
            val rows = next(batch)
            if (rows.isEmpty()) return total
            store(rows.map { FromDomainUpdate(it.accountId, it.id, indexed(it.fromEmail)) })
            total += rows.size
        }
    }
}
