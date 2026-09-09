package app.sterna.core.data.account

import kotlinx.serialization.Serializable

/**
 * Which mail protocol an account speaks. Stored by NAME ([StoredAccount.protocol]): an unknown
 * name fails the whole account list to decode. Never rename or remove; retire from the UI.
 */
@Serializable
enum class MailProtocol { JMAP, IMAP }

/** Transport security for IMAP/SMTP; same by-name storage rule (imapSecurity/smtpSecurity). */
@Serializable
enum class ConnectionSecurity { TLS, STARTTLS, NONE }
