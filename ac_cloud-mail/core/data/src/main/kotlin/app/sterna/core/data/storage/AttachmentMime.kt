package app.sterna.core.data.storage

/**
 * Which MIME type an attachment is handed to another application under.
 *
 * THE SENDER'S CLAIM IS NOT THE ANSWER. A `Content-Type` on a received message is attacker
 * controlled, and the type is what Android matches an intent filter against -- it is the field that
 * chooses WHICH app opens the file and therefore what happens next. Deciding that from the claim
 * alone lets a stranger pick the handler for their own file.
 *
 * So the FILE decides, and the claim is only consulted when the file has nothing to say. The
 * caller resolves [fromExtension] from the saved name through the platform's own extension map --
 * a name that has already been through [SafeFileName], so the extension is one this app wrote.
 */
object AttachmentMime {

        /** What is handed over when nothing trustworthy is known. `*` / `*` asserts NOTHING: the
         *  chooser offers everything and the user picks, which is the honest state. An
         *  `application/octet-stream` here would look more precise and be worse -- on many devices
         *  nothing claims it, so the tap would do nothing at all. */
    const val UNKNOWN = "*/*"

        /**
         * Types never handed to a chooser, whatever the file or the sender says.
         *
         * An APK's type is a request that the package installer open it. Reaching that from a tap on
         * a chip in a message list -- a surface where a brush of a finger is a real way to arrive --
         * is not a thing a mail application should offer, and the sender is a stranger. Such a file
         * is still downloadable and still savable; it is handed over as [UNKNOWN], which means the
         * user chooses what looks at it rather than the installer being summoned by a MIME type.
         */
    private val NEVER = setOf(
        "application/vnd.android.package-archive",
    )

        /** A media type is `type/subtype` of restricted characters (RFC 6838 §4.2). Anything else is
         *  not a type this app will repeat to the system, whoever produced it. */
    private val WELL_FORMED = Regex("^[a-z0-9][a-z0-9!#\$&^_.+-]*/[a-z0-9][a-z0-9!#\$&^_.+-]*$")

    fun of(declared: String?, fromExtension: String?): String {
        // The file first, the claim second. `type` may carry parameters (`text/plain; charset=…`),
        // which are not part of what an intent filter matches.
        val candidate = (fromExtension?.takeIf { it.isNotBlank() } ?: declared)
            ?.substringBefore(';')?.trim()?.lowercase()
            ?: return UNKNOWN
        if (candidate in NEVER) return UNKNOWN
        return if (WELL_FORMED.matches(candidate)) candidate else UNKNOWN
    }
}
