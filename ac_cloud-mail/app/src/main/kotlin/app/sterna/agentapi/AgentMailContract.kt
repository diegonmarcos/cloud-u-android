package app.sterna.agentapi

/**
 * #913 THE FLEET AGENT DOOR TO MAIL, as a contract with no Android in it so a plain JVM test holds it.
 *
 * Cloud Search's Agents tab reads the owner's mail to draft replies. It does that through
 * [AgentMailProvider], a ContentProvider guarded by the constellation's signature permission
 * (CONSTELLATION_DATA): only an app signed with the one constellation key can call it.
 *
 *   content://<applicationId>.agentmail/messages?from=&subject=&since=&limit=
 *       rows of [MESSAGE_COLUMNS], newest first. `from` matches the sender's address OR name, `subject`
 *       the subject, both as a case-insensitive substring; `since` is epoch millis (default: 0);
 *       `limit` is 1..[MAX_LIMIT] (default [DEFAULT_LIMIT]).
 *   content://<applicationId>.agentmail/body?account=<account_id>&id=<id>
 *       one row of [BODY_COLUMNS]: the plain text, the html part (when the message has one) and whether
 *       either was cut at [MAX_BODY_CHARS].
 *
 * READ ONLY BY CONSTRUCTION: the provider implements query and nothing else; insert, update and delete
 * answer null / 0. Reading a body never marks the message read. Every filter value reaches SQLite as a
 * bound argument with its LIKE wildcards escaped, never spliced into the statement.
 */
object AgentMailContract {
    const val AUTHORITY_SUFFIX = "agentmail"
    const val PATH_MESSAGES = "messages"
    const val PATH_BODY = "body"

    const val P_FROM = "from"
    const val P_SUBJECT = "subject"
    const val P_SINCE = "since"
    const val P_LIMIT = "limit"
    const val P_ACCOUNT = "account"
    const val P_ID = "id"

    const val DEFAULT_LIMIT = 50
    const val MAX_LIMIT = 200
    const val MAX_FILTER_CHARS = 200
    const val MAX_BODY_CHARS = 200_000

    /** The one permission both ends name: the constellation's signature permission. */
    const val PERMISSION = "com.diegonmarcos.cloud.permission.CONSTELLATION_DATA"

    val MESSAGE_COLUMNS: Array<String> = arrayOf(
        "id", "account_id", "mailbox_id", "subject", "from_name", "from_email", "received_at", "sort_key", "seen",
    )
    val BODY_COLUMNS: Array<String> = arrayOf("text", "html", "truncated")

    /** A validated question. */
    data class Criteria(val from: String?, val subject: String?, val since: Long, val limit: Int)

    sealed interface Parsed {
        data class Ok(val criteria: Criteria) : Parsed
        data class Refused(val why: String) : Parsed
    }

    /** The raw query parameters (any may be null) → a [Criteria], or the reason it is refused. */
    fun criteria(from: String?, subject: String?, since: String?, limit: String?): Parsed {
        val f = from?.trim()?.takeIf { it.isNotEmpty() }
        val s = subject?.trim()?.takeIf { it.isNotEmpty() }
        if ((f?.length ?: 0) > MAX_FILTER_CHARS) return Parsed.Refused("from is longer than $MAX_FILTER_CHARS chars")
        if ((s?.length ?: 0) > MAX_FILTER_CHARS) return Parsed.Refused("subject is longer than $MAX_FILTER_CHARS chars")
        val sinceMs = if (since.isNullOrBlank()) 0L else since.trim().toLongOrNull()
            ?: return Parsed.Refused("since is not epoch millis: $since")
        if (sinceMs < 0) return Parsed.Refused("since is negative: $sinceMs")
        val lim = if (limit.isNullOrBlank()) DEFAULT_LIMIT else limit.trim().toIntOrNull()
            ?: return Parsed.Refused("limit is not a number: $limit")
        if (lim < 1 || lim > MAX_LIMIT) return Parsed.Refused("limit must be 1..$MAX_LIMIT: $lim")
        return Parsed.Ok(Criteria(f, s, sinceMs, lim))
    }

    /** `%`, `_` and the escape character itself made literal, for `LIKE ? ESCAPE '\'`. */
    fun likeEscape(s: String): String = buildString {
        for (c in s) {
            if (c == '\\' || c == '%' || c == '_') append('\\')
            append(c)
        }
    }

    /** The statement and its bound arguments for [c]. The column list is spelled out (never `*`). */
    fun sql(c: Criteria): Pair<String, Array<Any>> {
        val args = ArrayList<Any>()
        val where = StringBuilder("sortKey >= ?")
        args += c.since
        if (c.from != null) {
            where.append(" AND (fromEmail LIKE ? ESCAPE '\\' OR fromName LIKE ? ESCAPE '\\')")
            val p = "%" + likeEscape(c.from) + "%"
            args += p; args += p
        }
        if (c.subject != null) {
            where.append(" AND subject LIKE ? ESCAPE '\\'")
            args += "%" + likeEscape(c.subject) + "%"
        }
        args += c.limit
        val sql = "SELECT id, accountId, mailboxId, subject, fromName, fromEmail, receivedAt, sortKey, seen " +
            "FROM emails WHERE $where ORDER BY sortKey DESC LIMIT ?"
        return sql to args.toTypedArray()
    }

    /** What a body answer carries: the text, the html part (empty when there is none), and the cut flag. */
    data class Body(val text: String, val html: String, val truncated: Boolean)

    /** [text] and [html] each cut to [MAX_BODY_CHARS]; [truncated] when either was. */
    fun body(text: String, html: String): Body {
        val t = text.length > MAX_BODY_CHARS
        val h = html.length > MAX_BODY_CHARS
        return Body(if (t) text.take(MAX_BODY_CHARS) else text, if (h) html.take(MAX_BODY_CHARS) else html, t || h)
    }
}
