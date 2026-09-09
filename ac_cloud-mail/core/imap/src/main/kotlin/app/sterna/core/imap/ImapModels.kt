package app.sterna.core.imap

/** Transport security for an IMAP/SMTP connection. */
enum class MailSecurity {
    /** Implicit TLS from the first byte (IMAP 993 / SMTP 465). */
    TLS,

    /** Plain connection upgraded with STARTTLS (IMAP 143 / SMTP 587). */
    STARTTLS,

    /** No encryption (discouraged; for local testing only). */
    NONE,
}

/** Connection + credentials for an IMAP or SMTP server. */
data class MailServerConfig(
    val host: String,
    val port: Int,
    val security: MailSecurity,
    val username: String,
    val password: String,
        /** OAuth bearer access token. When non-null the client authenticates with SASL XOAUTH2
         *  instead of a password (Outlook/Microsoft etc.). */
    val accessToken: String? = null,
)

    /** The SASL XOAUTH2 initial client response (base64), per the Google/Microsoft spec:
     *  `base64("user=" + username + ^A + "auth=Bearer " + token + ^A^A)` where ^A is U+0001. */
internal fun xoauth2Payload(username: String, accessToken: String): String {
    val sep = Char(1) // SASL XOAUTH2 field separator (Ctrl-A / U+0001)
    val raw = "user=$username${sep}auth=Bearer $accessToken$sep$sep"
    return java.util.Base64.getEncoder().encodeToString(raw.toByteArray(Charsets.UTF_8))
}

/** A mailbox returned by LIST, with any role inferred from its name/attributes. */
data class ImapFolder(
    /** The display leaf, decoded and stripped of bidi/control characters. Never sent back. */
    val name: String,
        /** The mailbox path in UNICODE, decoded from modified UTF-7 (Codeberg #101) — the app's
         *  identifier for the folder, re-encoded at the socket, so it must stay a faithful decoding:
         *  nothing is filtered out of it. */
    val path: String,
    /** A normalised role ("inbox", "sent", "drafts", "trash", "junk", "archive") or null. */
    val role: String?,
    val delimiter: String,
        /**
         * The role this folder CLAIMED and did not get, another folder of the same listing carrying
         */
    val unelectedRole: String? = null,
        /** What the server answered about this folder's subscription (`LSUB` / `LIST (SUBSCRIBED)`),
         *  and nothing else. `true` when we do not know: a listing read without asking says nothing
         *  about it, and the unknown must never hide a folder. */
    val isSubscribed: Boolean = true,
)

/** One decoded envelope address (either part may be absent). */
data class ImapAddress(
    val name: String?,
    val email: String?,
)

/** One email's envelope + flags as fetched from IMAP (body fetched separately). */
data class ImapMessage(
    val uid: Long,
    val subject: String?,
    val fromName: String?,
    val fromEmail: String?,
    val to: List<ImapAddress>,
        /** Envelope `Reply-To` (index 4): where the SENDER said to answer, empty when the header is
         *  absent. Not a preference — a list or a `no-reply@` sender said the From is not read.
         *  Persisted like [to], the cache row being all an offline reply ever sees. */
    val replyTo: List<ImapAddress> = emptyList(),
        /** Envelope `Cc` (index 6): the people a "reply to all" has to keep. Persisted like [to],
         *  because the cache row is all a reopened draft and an offline reply-all ever see — an
         *  address that stops here is one they cannot address. */
    val cc: List<ImapAddress> = emptyList(),
        /**
         * Envelope `Bcc` (index 7): the blind copies. Practically always empty on a received
         */
    val bcc: List<ImapAddress> = emptyList(),
    /** Epoch millis from the envelope date (0 if unparseable). */
    val dateMillis: Long,
    val seen: Boolean,
    val flagged: Boolean,
    val answered: Boolean,
        /** The `\Deleted` flag: the server has been told this message is to go, and only an EXPUNGE
         *  (from any client) removes it. Sterna never shows such a message — see
         *  [ImapSession.messages], the one place that acts on this. */
    val deleted: Boolean = false,
    val hasAttachment: Boolean,
    val messageId: String?,
    val inReplyTo: String?,
        /** The raw value of the `References` header, unfolded onto one line (RFC 5322 §2.2.3), or null.
         *  The ENVELOPE carries `In-Reply-To` and `Message-ID` but not this one, so every list fetch
         * asks for it as `BODY.PEEK[HEADER.FIELDS (REFERENCES)]`. Raw and whole: nothing here reads
         *  it, it is carried so a thread rebuilt on the client can be rooted at its FIRST id. */
    val references: String? = null,
        /** Where this message's readable text is, per the BODYSTRUCTURE that came with the envelope,
         * or null when it has no `text/…` part. Additive and nullable: nothing that lists, searches
         *  or restores reads it. Kept ONLY so a later read — the notification preview — can name a
         *  section without asking the server to describe the message twice. */
    val textPart: ImapTextPart? = null,
)

/** Result of selecting a mailbox. */
data class ImapMailboxStatus(
    val exists: Int,
    val uidValidity: Long,
    val uidNext: Long,
        /**
         * Whether [exists] is a number THE SERVER STATED, or merely the value it is initialised to.
         */
    val existsObserved: Boolean = false,
)

    /**
     * What a move reported about where the mail landed — the answer to `UID MOVE`, or to the
     */
data class ImapMoved(
    val uids: Map<Long, Long>,
    val destinationUidValidity: Long?,
    val confirmedGone: Set<Long>,
) {
    companion object {
            /** Nothing moved, nothing stated, nothing proved — what a caller answers when the move never went out. */
        val NONE = ImapMoved(emptyMap(), null, emptySet())
    }
}
