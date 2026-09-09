package app.sterna.push

import app.sterna.core.data.settings.NotificationContent

/** What a mail notification is allowed to put on screen, per the notification-content setting (#25).
 *  Pure and resource-free, so the rule is unit-testable. Every path that posts a mail notification
 *  goes through here, the snooze wake-up (#84) included — it used to post with the defaults and
 *  showed sender and subject to a user who asked for neither. */
object MailNotificationText {

    /** [title] is always shown; [text] is the collapsed line, [bigText] the expanded one, and null
     *  draws neither. [bigText] carries a body preview at one position only (#57). */
    data class Reveal(val title: String, val text: String?, val bigText: String?)

    /**
     * [sender] and [subject] are the mail's own strings, already defaulted by the caller; [generic]
     */
    fun resolve(
        content: NotificationContent,
        sender: String,
        subject: String,
        generic: String,
        preview: String? = null,
    ): Reveal = Reveal(
        // A `when` rather than a test against NONE: with an `if`, a position added later takes the
        // sender silently.
        title = when (content) {
            NotificationContent.BODY_PREVIEW,
            NotificationContent.SENDER_AND_SUBJECT,
            NotificationContent.SENDER_ONLY,
            -> sender
            NotificationContent.NONE -> generic
        },
        // The collapsed line NEVER carries the preview: Notifications.kt rebuilds the group summary
        // by copying EXTRA_TITLE/EXTRA_TEXT off the live children, so it would come back on its own
        // — lock screen included, in a line nobody can collapse.
        text = when (content) {
            NotificationContent.BODY_PREVIEW,
            NotificationContent.SENDER_AND_SUBJECT,
            -> subject
            NotificationContent.SENDER_ONLY -> generic
            NotificationContent.NONE -> null
        },
        // The expanded line is the only place the body may show; with no usable preview it is the
        // subject alone. The newline is the SEPARATOR — shapePreview flattens the preview, not this.
        bigText = when (content) {
            NotificationContent.BODY_PREVIEW ->
                shapePreview(preview)?.let { "$subject\n$it" } ?: subject
            NotificationContent.SENDER_AND_SUBJECT -> subject
            NotificationContent.SENDER_ONLY, NotificationContent.NONE -> null
        },
    )

    /**
     * The line a send-failure banner may put under its title, or null. That banner was the one
     */
    fun sendFailureLine(
        content: NotificationContent,
        subject: String,
        noSubject: String,
    ): String? = when (content) {
        NotificationContent.BODY_PREVIEW,
        NotificationContent.SENDER_AND_SUBJECT,
        -> subject.ifBlank { noSubject }
        NotificationContent.SENDER_ONLY, NotificationContent.NONE -> null
    }

    /**
     * One row of the latest-mail widget. Two lines and not three — there is no expanded state on a
     */
    data class WidgetRow(val primary: String, val secondary: String?, val namesSender: Boolean)

    /**
     * What one row of the latest-mail widget may say, per the same setting and the app lock — no
     */
    fun widgetRow(
        content: NotificationContent,
        appLockEnabled: Boolean,
        sender: String,
        subject: String,
        generic: String,
    ): WidgetRow {
        val effective = if (appLockEnabled) NotificationContent.NONE else content
        return WidgetRow(
            primary = when (effective) {
                NotificationContent.BODY_PREVIEW,
                NotificationContent.SENDER_AND_SUBJECT,
                NotificationContent.SENDER_ONLY,
                -> sender
                NotificationContent.NONE -> generic
            },
            secondary = when (effective) {
                NotificationContent.BODY_PREVIEW,
                NotificationContent.SENDER_AND_SUBJECT,
                -> subject
                NotificationContent.SENDER_ONLY -> generic
                NotificationContent.NONE -> null
            },
            // The same exhaustive `when` a third time, and NOT `effective != NONE`: a position added
            // later would be given the sender's initial silently, and a name on a home screen cannot
            // be taken back.
            namesSender = when (effective) {
                NotificationContent.BODY_PREVIEW,
                NotificationContent.SENDER_AND_SUBJECT,
                NotificationContent.SENDER_ONLY,
                -> true
                NotificationContent.NONE -> false
            },
        )
    }

    /** How much of a body a notification may carry: the length of the preview the JMAP server
     *  already returns, so two accounts on two protocols look alike side by side. */
    const val PREVIEW_MAX_CHARS = 256

    /** Every run of space, tab, newline or carriage return, as one. */
    private val BLANK_RUN = Regex("""\s+""")

    /** A raw body opening made fit for one expanded notification line: every run of blank becomes one
     *  space, the ends are trimmed, and the rest is cut to [PREVIEW_MAX_CHARS] with nothing appended.
     * Flatten THEN cap: a body starting with forty blank lines would otherwise spend its whole
     *  allowance on nothing. */
    fun shapePreview(preview: String?): String? =
        preview?.replace(BLANK_RUN, " ")?.trim()?.take(PREVIEW_MAX_CHARS)?.takeIf { it.isNotEmpty() }
}
