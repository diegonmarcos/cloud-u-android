package app.sterna.core.jmap.model

import kotlinx.serialization.Serializable

/** A JMAP Mailbox (RFC 8621 §2). Only the fields we use so far. */
@Serializable
data class Mailbox(
    val id: String,
    val name: String,
    val role: String? = null,
    val parentId: String? = null,
    val sortOrder: Int = 0,
    val totalEmails: Int = 0,
    val unreadEmails: Int = 0,
        /** Unread count shown as the drawer badge. For JMAP accounts a LIVE local aggregate over the
         *  cached `emails` table, mode-appropriate and folder-scoped exactly like the collapsed list,
         *  so the badge equals the visible bold rows — but only for a folder that HAS cached mail to
         *  aggregate. A folder nobody has opened yet is absent from that aggregate, not zero in it,
         *  and keeps the stored server counter instead (#247). Not a server field. Distinct from
         *  [unreadEmails], which is that stored counter. */
    val unreadForList: Int = 0,
        /**
         * Whether the server reports this mailbox as subscribed (RFC 8621 §2, Codeberg #174).
         */
    val isSubscribed: Boolean = true,
        /**
         * Whether this folder CLAIMED a role and another folder of the account was elected to it — an
         */
    val lostRoleClaim: Boolean = false,
)
