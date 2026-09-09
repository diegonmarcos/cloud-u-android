package app.sterna.core.data.account

import kotlinx.serialization.Serializable

/** Persisted account metadata (the password is stored separately, encrypted). */
@Serializable
data class StoredAccount(
    val id: String,
    val server: String,
    val username: String,
    /** JMAP account id this maps to (RFC 8620 §1.6.2); null = resolve via the session's primary account. */
    val jmapAccountId: String? = null,
    /** Groups sub-accounts sharing one login: points at the primary id whose secret this borrows; null = standalone. */
    val loginId: String? = null,
    val accountName: String = "",
    val inboxId: String? = null,
    val inboxName: String = "Inbox",
    val unread: Int = 0,
    val syncWindow: SyncWindow = SyncWindow.DAYS_90,
    /** Whether new-mail notifications fire for this account (per-account opt-out). */
    val notificationsEnabled: Boolean = true,
    /** Whether a sent copy is APPENDed into Sent (IMAP only) — off for a submission server that files its own. */
    val uploadSentCopy: Boolean = true,
    /** Whether folder lists hide unsubscribed folders (#174); defaults FALSE (rule 1: every record predates this
     *  field). */
    val showOnlySubscribedFolders: Boolean = false,
    /** Extra folders watched for new mail, by mailbox id; the Inbox is always watched and never stored here. */
    val watchedFolders: Set<String> = emptySet(),
    /** Per-folder collapse choice; absent = undecided. A `Map`, not a set: unfolding must WRITE `false`, or it folds
     *  back. */
    val collapsedFolders: Map<String, Boolean> = emptyMap(),
    /** User-chosen accent colour (ARGB); null = auto (derived from the address). */
    val color: Int? = null,
    /** Legacy account-level signature; seeds the default identity when none are set. */
    val signature: String = "",
    /** Sending identities; empty means use a default derived from the account. */
    val identities: List<StoredIdentity> = emptyList(),
    /** Identities discovered from the JMAP server; [identities] (manual) are merged on top in the composer's From
     *  picker. */
    val serverIdentities: List<StoredIdentity> = emptyList(),
    /** User-chosen default identity, keyed by [StoredIdentity.id]; unmatched degrades via [defaultIdentity]. */
    val defaultIdentityId: String? = null,
    /** True for a freshly imported account (K-9/backup) still needing sign-in; stays inert regardless of this flag. */
    val importPending: Boolean = false,
    val protocol: MailProtocol = MailProtocol.JMAP,
    // OAuth (authType == OAUTH); access token cached, refresh token in the password slot.
    val authType: AuthType = AuthType.BASIC,
    val oauthAccessToken: String = "",
    val oauthAccessExpiresAt: Long = 0,
    val oauthTokenEndpoint: String = "",
    val oauthClientId: String = "",
    // IMAP/SMTP connection details (used only when protocol == IMAP).
    val imapHost: String = "",
    val imapPort: Int = 993,
    val imapSecurity: ConnectionSecurity = ConnectionSecurity.TLS,
    val smtpHost: String = "",
    val smtpPort: Int = 587,
    val smtpSecurity: ConnectionSecurity = ConnectionSecurity.STARTTLS,
    // OpenPGP (via the OpenKeychain provider). The key id is not a secret.
    val pgpEnabled: Boolean = false,
    val pgpSignKeyId: Long = 0L,
    /** Cache of this account's OpenPGP public key for [pgpSignKeyId]; a key-id change empties it, or it'd announce the
     *  OLD key. */
    val pgpPublicKey: String = "",
    val pgpEncryptByDefault: Boolean = false,
) {
    /** The id whose stored secret/OAuth tokens back this record: its login (or itself if standalone). */
    fun loginKey(): String = loginId ?: id

    /** True for a sub-account discovered under another account's login (shares its credential). */
    val isLinked: Boolean get() = loginId != null

    /** True for a DELEGATED account, read from [loginId] alone — Stalwart's `isPersonal` flag separates nothing (#129).
     *  */
    val isShared: Boolean get() = !loginId.isNullOrBlank()

    /** Best label for the account in UI. */
    fun label(): String = accountName.ifBlank { username }

    /** Manual + server identities, deduped by address, manual FIRST. A linked sub-account can't fetch server identities
     *  (#31). */
    fun resolvedIdentities(): List<StoredIdentity> =
        (identities + serverIdentities)
            .distinctBy { it.email.trim().lowercase() }
            .ifEmpty {
                if (isLinked) {
                    // No signature to split on a delegate; trust only the session-advertised address (#31).
                    listOfNotNull(
                        accountName.trim()
                            .takeIf { it.matches(Regex("[^@\\s]+@[^@\\s]+")) }
                            ?.let { StoredIdentity(id = "delegated", name = "", email = it) },
                    )
                } else {
                    // Legacy signature may be raw HTML; split into plain text + optional HTML like any other.
                    listOf(
                        StoredIdentity(id = "default", name = accountName, email = username, signature = signature)
                            .withSplitSignature(),
                    )
                }
            }

    /** [defaultIdentityId] match, else this account's own identity, else the first — the username step matters (#78).
     *  */
    fun defaultIdentity(): StoredIdentity? {
        val resolved = resolvedIdentities()
        resolved.firstOrNull { it.id == defaultIdentityId }?.let { return it }
        val own = username.trim().lowercase()
        val ownIdentity = if (isLinked || own.isEmpty()) {
            null
        } else {
            resolved.firstOrNull { it.email.trim().lowercase() == own }
        }
        return ownIdentity ?: resolved.firstOrNull()
    }

    companion object {
        /** Every login, followed by its delegated mailboxes; an orphan delegate is emitted at the END rather than
         *  dropped. */
        fun accountsScreenRows(all: List<StoredAccount>): List<AccountRowEntry> {
            val visible = all.filter { !it.importPending }
            val delegates = visible.filter { it.isShared }
            val rows = mutableListOf<AccountRowEntry>()
            val hung = mutableSetOf<String>()
            visible.filter { !it.isShared }.forEach { login ->
                rows += AccountRowEntry(login, underLogin = false)
                delegates.filter { it.loginId == login.id }.forEach { delegate ->
                    rows += AccountRowEntry(delegate, underLogin = true)
                    hung += delegate.id
                }
            }
            delegates.filterNot { it.id in hung }.forEach { rows += AccountRowEntry(it, underLogin = false) }
            return rows
        }

        /** Heals manual identities polluted by an old merge-on-save fold: drops entries that only mirror a server
         *  identity. */
        fun normalizeManualIdentities(
            manual: List<StoredIdentity>,
            server: List<StoredIdentity>,
        ): List<StoredIdentity> {
            // signatureHtml is part of the key: differing only there is a genuine difference. So are
            // the named signatures and the default among them (#206) — an override whose ONLY edit
            // was adding a second signature is not a frozen copy of the server's identity, and
            // leaving them out of the key would delete that edit on the next save.
            fun key(i: StoredIdentity) = listOf(
                i.email.trim().lowercase(), i.name, i.signature, i.signatureHtml,
                i.signatures, i.defaultSignatureId,
            )
            val serverKeys = server.map(::key).toSet()
            return manual
                .filterNot { key(it) in serverKeys }
                .distinctBy(::key)
        }

        /** Server identities deduped by address for DISPLAY, matching [resolvedIdentities]'s own de-dup. */
        fun distinctServerIdentities(server: List<StoredIdentity>): List<StoredIdentity> =
            server.distinctBy { it.email.trim().lowercase() }
    }
}

/** One row of the accounts screen: [account], and whether it is drawn indented under a login. */
data class AccountRowEntry(val account: StoredAccount, val underLogin: Boolean)
