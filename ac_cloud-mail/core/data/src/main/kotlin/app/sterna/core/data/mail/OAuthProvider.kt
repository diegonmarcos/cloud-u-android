package app.sterna.core.data.mail

import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailEndpoint
import app.sterna.core.jmap.OAuthMetadata

/** A built-in OAuth provider with fixed endpoints (Microsoft, …). Not discovered via RFC 8414 like
 *  the JMAP path: the auth/token/device endpoints and the IMAP/SMTP servers are hard-coded. */
data class OAuthProvider(
    val id: String,
    val clientId: String,
    val scope: String,
    val metadata: OAuthMetadata,
    val imap: MailEndpoint,
    val smtp: MailEndpoint,
) {
    /** True once a real OAuth client id has been registered and dropped in below. */
    val isConfigured: Boolean get() = clientId.isNotBlank() && !clientId.startsWith("TODO")

    companion object {
        /** Outlook / Microsoft (personal + work/school) over IMAP+SMTP with XOAUTH2. Requires a
         *  registered public Azure app (Entra ID → App registrations): Authentication → "Allow
         *  that registration's (public, non-secret) client id into [clientId]. */
        val MICROSOFT = OAuthProvider(
            id = "microsoft",
            clientId = "46f1f544-a6df-45d0-9d26-3e4dad7a6c12",
            scope = "https://outlook.office.com/IMAP.AccessAsUser.All " +
                "https://outlook.office.com/SMTP.Send offline_access openid email",
            metadata = OAuthMetadata(
                issuer = "https://login.microsoftonline.com/common/v2.0",
                tokenEndpoint = "https://login.microsoftonline.com/common/oauth2/v2.0/token",
                deviceAuthorizationEndpoint = "https://login.microsoftonline.com/common/oauth2/v2.0/devicecode",
            ),
            imap = MailEndpoint("outlook.office365.com", 993, ConnectionSecurity.TLS),
            smtp = MailEndpoint("smtp.office365.com", 587, ConnectionSecurity.STARTTLS),
        )

        /** The built-in provider whose IMAP host matches [host], or null for an unknown XOAUTH2 server. */
        fun forImapHost(host: String): OAuthProvider? = when {
            host.isBlank() -> null
            host.equals(MICROSOFT.imap.host, ignoreCase = true) ||
                host.endsWith("office365.com", ignoreCase = true) ||
                host.endsWith("outlook.com", ignoreCase = true) -> MICROSOFT
            else -> null
        }
    }
}
