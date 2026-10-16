package com.diegonmarcos.cloudagenda.data

/** Where the agent reads mail from. */
enum class MailSourceKind(val id: String, val label: String) {
    /** Cloud Mail on this phone, through its read-only agent provider (its accounts and sign-in stay in Cloud Mail). */
    CLOUD_MAIL("cloud-mail", "Cloud Mail on this phone"),
    /** A JMAP server reached directly. */
    JMAP("jmap", "JMAP URL");

    companion object {
        fun of(id: String?): MailSourceKind = entries.firstOrNull { it.id == id } ?: CLOUD_MAIL
    }
}

/** How a direct JMAP request authenticates: nothing, a Bearer token, or the Authelia session cookie (as Cloud Mail's accounts do). */
enum class JmapAuth(val id: String, val label: String) {
    NONE("none", "None"),
    BEARER("bearer", "Bearer token"),
    AUTHELIA("authelia", "Authelia");

    companion object {
        fun of(id: String?): JmapAuth = entries.firstOrNull { it.id == id } ?: NONE
    }
}

/**
 * The mail agent's declaration: what to read, how often, with which model, and what to look for. Saved as typed;
 * whether a backend exists to act on it is the worker's business and is reported in [lastStatus].
 * The JMAP token / Authelia session is a secret and lives in the encrypted store, never in this row.
 */
data class AgentConfig(
    val enabled: Boolean = false,
    val source: MailSourceKind = MailSourceKind.CLOUD_MAIL,
    /** Cloud Mail account id to read, blank = every account. For JMAP: the account id on the server, blank = the primary one. */
    val account: String = "",
    val jmapUrl: String = "",
    val jmapAuth: JmapAuth = JmapAuth.NONE,
    /** Comma-separated mailbox ids (or names, for JMAP); blank = all mailboxes. */
    val mailboxes: String = "",
    /** 0 = only when asked (Run now). Android's periodic work floor is 15 minutes. */
    val frequencyMinutes: Int = 360,
    /** libs:ai gateway agent id (X-Agent-Mode choice); blank = the gateway's default. */
    val agent: String = "",
    val model: String = "",
    val extractEvents: Boolean = true,
    val extractBookings: Boolean = true,
    val extractInvoices: Boolean = true,
    val extractDeadlines: Boolean = true,
    val targetListId: String = "",
    val lastRunAt: Long = 0,
    val lastStatus: String = "",
    val lastDetail: String = "",
) {
    val mailboxSet: Set<String> get() = mailboxes.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    fun wants(kind: ProposalKind): Boolean = when (kind) {
        ProposalKind.EVENT -> extractEvents
        ProposalKind.BOOKING -> extractBookings
        ProposalKind.INVOICE -> extractInvoices
        ProposalKind.DEADLINE -> extractDeadlines
    }

    val kinds: List<ProposalKind> get() = ProposalKind.entries.filter { wants(it) }

    companion object {
        /** The frequencies the page offers, in minutes; 0 is "only when I tap Run now". */
        val FREQUENCIES: List<Pair<Int, String>> = listOf(0 to "Manual", 60 to "Hourly", 360 to "6 hours", 1440 to "Daily")
    }
}

enum class ProposalKind(val id: String, val label: String) {
    EVENT("event", "Event"),
    BOOKING("booking", "Booking"),
    INVOICE("invoice", "Invoice"),
    DEADLINE("deadline", "Deadline");

    companion object {
        fun of(id: String?): ProposalKind? = entries.firstOrNull { it.id == id }
    }
}

enum class ProposalStatus(val id: String) {
    PENDING("pending"), APPROVED("approved"), REJECTED("rejected");

    companion object {
        fun of(id: String?): ProposalStatus = entries.firstOrNull { it.id == id } ?: PENDING
    }
}

/** What the extraction produced, before it is stored. [sourceRef] is "mail:<account>/<id>" so the task can link back. */
data class ProposalDraft(
    val kind: ProposalKind,
    val title: String,
    val details: String = "",
    val dateTarget: String? = null,
    val dateDeadline: String? = null,
    val location: String = "",
    val sourceRef: String = "",
    val sourceSubject: String = "",
    val confidence: Double = 0.0,
)

/** A stored proposal waiting in the review inbox (or decided). */
data class Proposal(
    val id: String,
    val kind: ProposalKind,
    val title: String,
    val details: String,
    val dateTarget: String?,
    val dateDeadline: String?,
    val location: String,
    val sourceRef: String,
    val sourceSubject: String,
    val confidence: Double,
    val status: ProposalStatus,
    val createdAt: Long,
    val taskId: String,
)
