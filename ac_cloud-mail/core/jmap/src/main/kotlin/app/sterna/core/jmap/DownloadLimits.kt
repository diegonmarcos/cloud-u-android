package app.sterna.core.jmap

    /** Something a message asked us to load is bigger than we are willing to hold in memory. Carries
     *  the sizes so the caller can say which limit was hit; the user-facing wording is the caller's
     *  business. */
class ContentTooLargeException(
    message: String,
    /** Announced size in bytes, or -1 when the server didn't say. */
    val bytes: Long = -1,
    val maxBytes: Long = -1,
) : Exception(message)

    /**
     * Ceilings for what a single message may pull into memory over JMAP. Downloads have no framing of
     */
object DownloadLimits {

    /** Inline (`cid:`) image fetched without the user asking: 10 MB. */
    const val INLINE_IMAGE_MAX_BYTES = 10L * 1024 * 1024

    /** Attachment the user explicitly opened: 50 MB. */
    const val ATTACHMENT_MAX_BYTES = 50L * 1024 * 1024

        /** Calendar invite fetched on open to draw the event card: 2 MB. Real invitations are a few
         *  kilobytes and the reader refuses to parse one over 1 MiB anyway, so a bigger download only
         *  buys the buffer that would run the app out of memory. */
    const val CALENDAR_MAX_BYTES = 2L * 1024 * 1024

        /** Whether a part announcing [size] bytes may be downloaded under [maxBytes]. A size of 0 (or
         *  negative) means the server did not say — those pass here and are caught while reading, by
         *  the same ceiling. */
    fun allows(size: Long, maxBytes: Long): Boolean = size <= 0L || size <= maxBytes

        /** Refuse a part announcing [size] bytes when it is over [maxBytes]. The single choke point for
         *  the cap, so it cannot be applied on one protocol and skipped on another: it was skipped on
         *  IMAP, whose BODYSTRUCTURE reports the same size, because the check sat behind an early
         *  return. */
    fun enforce(size: Long, maxBytes: Long) {
        if (!allows(size, maxBytes)) {
            throw ContentTooLargeException(
                "Part is $size bytes, over the $maxBytes limit.",
                bytes = size,
                maxBytes = maxBytes,
            )
        }
    }
}
