package app.sterna.core.data.account

import app.sterna.core.data.text.htmlToText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Signatures leaving and entering the app: the `mail_signatures` store of the fleet setup contract,
 * and the file Cloud Account's Connections page exports and imports.
 *
 * KEYED BY ADDRESS, never by account id. Account ids are minted per install (and re-minted by an
 * import), so a record keyed by one lands on nothing on a new phone; an identity's address is what
 * the owner means by "this signature is for that mailbox".
 *
 * Pure: no Android, no store. [collect] reads accounts, [apply] returns the accounts to write, so
 * the handler and the tests share one definition of what moves and where it lands.
 */
object SignatureTransfer {
    /** The document header Cloud Account recognises, so a signature file is told from any other JSON. */
    const val FORMAT = "cloud-mail-signatures"
    const val VERSION = 1

    /** An entry addressed to this key applies to EVERY identity: how a bare .html file, which names no mailbox, arrives. */
    const val ALL_IDENTITIES = "*"

    /** One identity's signatures, as the value stored under its address. */
    @Serializable
    data class Entry(
        val default: String? = null,
        val signatures: List<StoredSignature> = emptyList(),
    )

    @Serializable
    data class Document(
        val format: String = FORMAT,
        val v: Int = VERSION,
        val identities: Map<String, Entry> = emptyMap(),
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun keyOf(email: String): String = email.trim().lowercase()

    /** Every identity that has a signature, by address; the first account that names an address wins. */
    fun collect(accounts: List<StoredAccount>): Map<String, Entry> {
        val out = linkedMapOf<String, Entry>()
        for (account in accounts) {
            for (identity in account.resolvedIdentities()) {
                val key = keyOf(identity.email)
                if (key.isEmpty() || key in out) continue
                val signatures = identity.resolvedSignatures()
                if (signatures.isEmpty()) continue
                out[key] = Entry(identity.defaultSignature()?.id, signatures)
            }
        }
        return out
    }

    fun encodeEntry(entry: Entry): String = json.encodeToString(Entry.serializer(), entry)

    /**
     * An entry from its wire form, or null when it does not decode. An HTML signature that arrives
     * without its plain-text half (a bare .html file handed over by Cloud Account, which has no
     * flattener of its own) gets it derived here, the way [StoredSignature.of] derives it.
     */
    fun decodeEntry(text: String): Entry? =
        runCatching { json.decodeFromString(Entry.serializer(), text) }.getOrNull()?.let { entry ->
            entry.copy(
                signatures = entry.signatures.map { it.withHttpsLinks() }.map {
                    if (it.html.isNotBlank() && it.text.isBlank()) it.copy(text = htmlToText(it.html, keepLinkTargets = true)) else it
                },
            )
        }

    fun toDocument(entries: Map<String, Entry>): String =
        json.encodeToString(Document.serializer(), Document(identities = entries))

    /** A signature file, or null when it is not one or comes from a newer format than this build reads. */
    fun parseDocument(text: String): Map<String, Entry>? {
        val doc = runCatching { json.decodeFromString(Document.serializer(), text) }.getOrNull() ?: return null
        if (doc.format != FORMAT || doc.v !in 1..VERSION) return null
        return doc.identities.mapValues { decodeEntry(encodeEntry(it.value)) ?: it.value }
    }

    /**
     * [accounts] with [entries] written onto the identities they address; the second value is how
     * many identities changed. An address no identity answers to is skipped; an address only the
     * server knows (or a legacy account's own) becomes a manual override carrying the signatures,
     * as the editor makes one.
     * [ALL_IDENTITIES] adds the entry's signatures to every identity, skipping any already present.
     */
    fun apply(accounts: List<StoredAccount>, entries: Map<String, Entry>): Pair<List<StoredAccount>, Int> {
        var changed = 0
        val updated = accounts.map { account ->
            var identities = account.identities
            for (candidate in account.resolvedIdentities()) {
                val key = keyOf(candidate.email)
                val exact = entries[key]
                val entry = exact ?: entries[ALL_IDENTITIES] ?: continue
                val base = identities.firstOrNull { keyOf(it.email) == key } ?: candidate
                val merged = if (exact != null) replace(base, entry) else append(base, entry)
                if (merged == base) continue
                changed++
                identities = if (identities.any { keyOf(it.email) == key }) {
                    identities.map { if (keyOf(it.email) == key) merged else it }
                } else {
                    identities + merged
                }
            }
            if (identities == account.identities) account else account.copy(identities = identities)
        }
        return updated to changed
    }

    /** The named signatures removed from every identity at [emails]; the legacy pair is blanked with them. */
    fun clear(accounts: List<StoredAccount>, emails: Set<String>): Pair<List<StoredAccount>, Int> {
        var changed = 0
        val keys = emails.map(::keyOf).toSet()
        val updated = accounts.map { account ->
            val next = account.identities.map { identity ->
                if (keyOf(identity.email) !in keys) identity
                else identity.copy(signature = "", signatureHtml = "", signatures = emptyList(), defaultSignatureId = null)
                    .also { if (it != identity) changed++ }
            }
            if (next == account.identities) account else account.copy(identities = next)
        }
        return updated to changed
    }

    private fun replace(identity: StoredIdentity, entry: Entry): StoredIdentity {
        if (entry.signatures.isEmpty()) return identity
        return identity.copy(signatures = entry.signatures, defaultSignatureId = entry.default).withLegacyMirror()
    }

    private fun append(identity: StoredIdentity, entry: Entry): StoredIdentity {
        val have = identity.resolvedSignatures()
        val fresh = entry.signatures.filter { s -> have.none { it.id == s.id || (it.text == s.text && it.html == s.html) } }
        if (fresh.isEmpty()) return identity
        return identity.copy(
            signatures = have + fresh,
            defaultSignatureId = identity.defaultSignatureId ?: (have + fresh).first().id,
        ).withLegacyMirror()
    }
}
