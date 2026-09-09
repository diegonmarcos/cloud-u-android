package app.sterna.core.data.account

import org.w3c.dom.Element
import java.io.InputStream

/** Imports account setup from a K-9 Mail / Thunderbird-for-Android `.k9s` export (Apache-2.0).
 *  Carries NO secrets: every imported account lands inert until re-authenticated through
 *  Sterna. Only IMAP is supported — others reported as skips, never silently dropped; a single
 *  malformed account never aborts the whole import. */
object K9SettingsImporter {

    /** Parse a .k9s settings export. Throws [IllegalArgumentException] if the root element is not <k9settings>. */
    fun parse(input: InputStream): K9ImportResult {
        val document = safeXmlDocumentBuilderFactory().newDocumentBuilder().parse(input)
        val root = document.documentElement
        require(root != null && root.tagName == "k9settings") { "Not a K-9 settings file" }

        val accounts = mutableListOf<StoredAccount>()
        val skipped = mutableListOf<K9Skip>()
        val securityUnverified = mutableListOf<String>()

        val accountsNode = root.childElements().firstOrNull { it.tagName == "accounts" }
        for (accountEl in accountsNode?.childElements()?.filter { it.tagName == "account" }.orEmpty()) {
            val label = accountLabel(accountEl)
            runCatching { parseAccount(accountEl) }
                .onSuccess { outcome ->
                    when (outcome) {
                        is AccountOutcome.Imported -> {
                            accounts += outcome.account
                            if (outcome.securityUnverified) securityUnverified += label
                        }
                        is AccountOutcome.Skipped -> skipped += K9Skip(label, outcome.reason)
                    }
                }
                .onFailure { skipped += K9Skip(label, K9SkipReason.MALFORMED) }
        }
        return K9ImportResult(accounts, skipped, securityUnverified)
    }

    private sealed interface AccountOutcome {
        data class Imported(
            val account: StoredAccount,
            /** The file did not state a connection security we map, so ours is a safe guess. */
            val securityUnverified: Boolean,
        ) : AccountOutcome
        data class Skipped(val reason: K9SkipReason) : AccountOutcome
    }

    private fun parseAccount(account: Element): AccountOutcome {
        val accountName = account.childText("name")

        val incoming = account.childElements().firstOrNull { it.tagName == "incoming-server" }
            ?: return AccountOutcome.Skipped(K9SkipReason.MALFORMED)

        when (incoming.getAttribute("type").uppercase()) {
            "IMAP" -> Unit
            "POP3" -> return AccountOutcome.Skipped(K9SkipReason.POP3)
            else -> return AccountOutcome.Skipped(K9SkipReason.UNSUPPORTED)
        }

        val authType = when (incoming.childText("authentication-type").uppercase()) {
            "XOAUTH2" -> AuthType.OAUTH
            "EXTERNAL" -> return AccountOutcome.Skipped(K9SkipReason.EXTERNAL_AUTH)
            else -> AuthType.BASIC // PLAIN, CRAM_MD5, LOGIN, AUTOMATIC, missing…
        }

        val username = incoming.childText("username")
        val imapHost = incoming.childText("host")
        val imapPort = incoming.childText("port").toIntOrNull() ?: 993
        val rawImapSecurity = incoming.childText("connection-security")
        val imapSecurity = connectionSecurity(rawImapSecurity)

        if (username.isBlank() || imapHost.isBlank()) {
            return AccountOutcome.Skipped(K9SkipReason.MALFORMED)
        }

        val outgoing = account.childElements().firstOrNull { it.tagName == "outgoing-server" }
        val smtpHost = outgoing?.childText("host").orEmpty()
        val smtpPort = outgoing?.childText("port")?.toIntOrNull() ?: 587
        val rawSmtpSecurity = outgoing?.childText("connection-security")
        val smtpSecurity = rawSmtpSecurity
            ?.let { connectionSecurity(it, fallback = ConnectionSecurity.STARTTLS) }
            ?: ConnectionSecurity.STARTTLS

        return AccountOutcome.Imported(
            // Either end falling back tells the user the guess needs checking: the file did not
            // say (or said something we do not map), so the ports may not match the guess.
            securityUnverified = !isKnownSecurity(rawImapSecurity) ||
                (rawSmtpSecurity != null && !isKnownSecurity(rawSmtpSecurity)),
            account = StoredAccount(
                id = "",
                server = "",
                username = username,
                accountName = accountName,
                protocol = MailProtocol.IMAP,
                authType = authType,
                imapHost = imapHost,
                imapPort = imapPort,
                imapSecurity = imapSecurity,
                smtpHost = smtpHost,
                smtpPort = smtpPort,
                smtpSecurity = smtpSecurity,
                identities = parseIdentities(account),
            ),
        )
    }

    private fun parseIdentities(account: Element): List<StoredIdentity> {
        val identitiesNode = account.childElements().firstOrNull { it.tagName == "identities" }
            ?: return emptyList()
        return identitiesNode.childElements()
            .filter { it.tagName == "identity" }
            .mapIndexed { i, identity ->
                val settings = identity.childElements().firstOrNull { it.tagName == "settings" }
                val signature = if (settings.valueForKey("signatureUse").equals("true", ignoreCase = true)) {
                    settings.valueForKey("signature")
                } else {
                    ""
                }
                StoredIdentity(
                    id = "identity-$i",
                    name = identity.childText("name"),
                    email = identity.childText("email"),
                    signature = signature,
                )
            }
    }

    /** Maps K-9's `<connection-security>` onto ours, falling back to encrypted [fallback] for
     * anything unrecognised. Falling back to NONE would configure the account in the clear
     *  — a wrong encrypted guess fails visibly, a wrong NONE guess succeeds and leaks. */
    private fun connectionSecurity(
        raw: String,
        fallback: ConnectionSecurity = ConnectionSecurity.TLS,
    ): ConnectionSecurity = when (raw.uppercase()) {
        "SSL_TLS_REQUIRED" -> ConnectionSecurity.TLS
        "STARTTLS_REQUIRED" -> ConnectionSecurity.STARTTLS
        else -> fallback
    }

    /** Whether [raw] is a value we actually understood (vs. one that took the safe fallback). */
    private fun isKnownSecurity(raw: String): Boolean =
        raw.uppercase() == "SSL_TLS_REQUIRED" || raw.uppercase() == "STARTTLS_REQUIRED"

    /** Best-effort label for skip reporting: the account's <name>, else its uuid, else "(unknown)". */
    private fun accountLabel(account: Element): String {
        val name = account.childText("name")
        if (name.isNotBlank()) return name
        val uuid = account.getAttribute("uuid")
        return uuid.ifBlank { "(unknown)" }
    }

    // ---- DOM helpers ----
    // childElements()/childText() moved to SafeXml.kt, shared with the autoconfig cascade.

    /** Trimmed text of the `<value key="...">` child with the given key, or "" if absent. */
    private fun Element?.valueForKey(key: String): String =
        this?.childElements()
            ?.firstOrNull { it.tagName == "value" && it.getAttribute("key") == key }
            ?.textContent?.trim()
            .orEmpty()
}

data class K9ImportResult(
    val accounts: List<StoredAccount>,
    val skipped: List<K9Skip>,
    /** Labels of imported accounts whose connection security wasn't stated in terms we map — a
     *  safe guess (TLS in, STARTTLS out) rather than confirmed. */
    val securityUnverified: List<String> = emptyList(),
)

data class K9Skip(val account: String, val reason: K9SkipReason)

enum class K9SkipReason { POP3, EXTERNAL_AUTH, UNSUPPORTED, MALFORMED }
