package app.sterna.core.data.mail

/** The Sent folder to hand [ImapMailService.send] — whether it APPENDs a copy of what it has just
 *  submitted: the `sent` role folder when "Upload sent messages" is on, null when it is off. Null
 *  is not a new state: `send` skips the APPEND and the submission succeeds, so no new failure path. */
fun sentMailboxToUpload(uploadSentCopy: Boolean, sentMailboxOfRole: String?): String? =
    if (uploadSentCopy) sentMailboxOfRole else null
