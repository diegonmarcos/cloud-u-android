package app.sterna.core.jmap.model

/**
 * The `envelope` of an EmailSubmission (RFC 8621 §7.5): the SMTP `MAIL FROM` and `RCPT TO` the
 */
data class SubmissionEnvelope(val mailFrom: String, val rcptTo: List<String>)
