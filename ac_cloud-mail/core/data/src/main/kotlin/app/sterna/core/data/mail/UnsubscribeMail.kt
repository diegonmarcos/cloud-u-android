package app.sterna.core.data.mail

internal data class UnsubscribeMail(
    val to: List<String>,
    val subject: String,
    val body: String,
    val fromName: String?,
    val fromEmail: String?,
)

/**
 * A fixed English word, NOT the reader's interface label: a robot at the other end matches on this
 * line, so a French phone must not send "Se désabonner" where a Russian one sends "Отписаться".
 */
internal const val UNSUBSCRIBE_SUBJECT = "Unsubscribe"

/** The very strings [unsubscribeMail] puts in the outbox, so the confirmation cannot name others. */
data class UnsubscribeMailPreview(
    val subject: String,
    val body: String,
)

/**
 * Verbatim from the sender, deliberately: some lists key the unsubscribe off the subject line. Since
 * `<mailto:hr@x?subject=I%20resign>` is legal, the confirmation shows what is about to leave.
 */
fun unsubscribePreview(mailto: MailtoUnsubscribe): UnsubscribeMailPreview = UnsubscribeMailPreview(
    subject = mailto.subject ?: UNSUBSCRIBE_SUBJECT,
    body = mailto.body.orEmpty(),
)

/**
 * [identityEmail] is explicit because a delegated sub-account is submitted through its login (#31):
 * the list would otherwise receive an unsubscribe from an address that is not the subscribed one.
 */
internal fun unsubscribeMail(
    mailto: MailtoUnsubscribe,
    identityName: String?,
    identityEmail: String?,
): UnsubscribeMail {
    val preview = unsubscribePreview(mailto)
    return UnsubscribeMail(
        to = listOf(mailto.address),
        subject = preview.subject,
        body = preview.body,
        fromName = identityName,
        fromEmail = identityEmail,
    )
}
