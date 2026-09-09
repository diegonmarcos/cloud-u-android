package app.sterna.core.jmap.model

import app.sterna.core.jmap.Jmap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

    /** Ids per request for a server that advertises no usable per-call object limit. Deliberately
     *  smaller than any `maxObjectsInSet`/`maxObjectsInGet` measured (Stalwart: 500): too few is
     *  merely slow, too many is the server rejecting the WHOLE request (RFC 8620 §5.1). */
const val JMAP_BATCH_FALLBACK = 100

    /** Hard cap on the ids we put in one request, whatever the server advertises: a server announcing
     *  an absurd limit still gets requests we can build, hold and retry in a sane time. */
const val JMAP_BATCH_CEILING = 500

    /** The JMAP Session resource (RFC 8620 §2): capabilities and the URLs/accounts the
     *  authenticated user can use. */
@Serializable
data class JmapSession(
    val capabilities: Map<String, JsonObject> = emptyMap(),
    val accounts: Map<String, JmapAccount> = emptyMap(),
    val primaryAccounts: Map<String, String> = emptyMap(),
    val username: String = "",
    val apiUrl: String,
    val downloadUrl: String? = null,
    val uploadUrl: String? = null,
    val eventSourceUrl: String? = null,
    val state: String? = null,
) {
    /** The primary mail account id, falling back to the first account available. */
    fun mailAccountId(): String? =
        primaryAccounts[Jmap.MAIL_CAPABILITY] ?: accounts.keys.firstOrNull()

        /** Every mail-capable account in the session (RFC 8620 §1.6.2), primary first — a single login
         *  can expose several (delegated / shared mailboxes, issue #31). A server that omits
         *  accountCapabilities falls back to the primary mail account, degrading to single-account. */
    fun mailAccountIds(): List<String> {
        val advertised = accounts.filterValues { it.accountCapabilities.containsKey(Jmap.MAIL_CAPABILITY) }.keys
        val ids = advertised.ifEmpty { listOfNotNull(mailAccountId()).toSet() }
        val primary = mailAccountId()
        return (listOfNotNull(primary?.takeIf { it in ids }) + ids.filterNot { it == primary }).distinct()
    }

        /** The server's VAPID application key (RFC 9749) when advertised, passed to the UnifiedPush
         *  registration; null for servers without VAPID (e.g. Stalwart today). */
    fun vapidPublicKey(): String? =
        (capabilities[Jmap.WEBPUSH_VAPID_CAPABILITY]?.get("applicationServerKey") as? JsonPrimitive)
            ?.contentOrNull

        /** Ids one `Email/set` may carry: the server's `maxObjectsInSet` (RFC 8620 §2, CORE
         *  capability), guarded and capped by [advertisedLimit]. */
    fun setBatchSize(): Int = advertisedLimit("maxObjectsInSet")

        /**
         * Ids one `Email/get` may ask for: `maxObjectsInGet`, read exactly like [setBatchSize] but
         */
    fun getBatchSize(): Int = advertisedLimit("maxObjectsInGet")

        /** A per-call object limit of the CORE capability, guarded: absent, non-numeric, zero or
         *  negative falls back to [JMAP_BATCH_FALLBACK], anything above [JMAP_BATCH_CEILING] is capped,
         *  and a value SMALLER than the fallback is respected — that is the server's limit. Read as a
         *  `Long`, so a value past `Int.MAX_VALUE` is capped rather than wrapped. */
    private fun advertisedLimit(property: String): Int {
        val announced = (capabilities[Jmap.CORE_CAPABILITY]?.get(property) as? JsonPrimitive)
            ?.longOrNull?.takeIf { it > 0 }
        return (announced ?: JMAP_BATCH_FALLBACK.toLong()).coerceAtMost(JMAP_BATCH_CEILING.toLong()).toInt()
    }
}

@Serializable
data class JmapAccount(
    val name: String,
        /** The data types this account exposes (RFC 8620 §2), used to tell mail accounts apart from
         *  contacts/calendar-only ones in a shared session. */
    val accountCapabilities: Map<String, JsonObject> = emptyMap(),
)
