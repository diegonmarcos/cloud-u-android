package app.sterna.core.data.storage

/**
 * The name an attachment is written to disk under.
 *
 * A filename on a received message is a string a STRANGER chose. It is not a name until this
 * function has finished with it. The rule here is not "escape the dangerous characters" -- it is
 * that the result must be a single, plain, visible file name, and everything that is not one is
 * replaced by [FALLBACK]. A caller cannot use the result to address anything but a file directly
 * inside the directory it hands it to.
 *
 * Pure, and in its own file, so that "a hostile name cannot escape its directory" is a claim a test
 * can EXECUTE. It used to be one expression inside a suspend function that also created directories
 * and wrote bytes, which meant the only way to check it was to read it.
 */
object SafeFileName {

    /** What a name that cannot be made safe becomes. Deliberately extension-less: nothing should
     *  open it automatically, and the user is about to be shown a chooser anyway. */
    const val FALLBACK = "attachment"

        /** Longest name written. Not cosmetic: most Android filesystems reject a component over 255
         *  BYTES, and a name near the limit in ASCII goes over it in UTF-8. 100 leaves room and is
         *  still longer than any filename a person types. */
    private const val MAX_LENGTH = 100

    /** Everything outside this alphabet becomes `_`. An allowlist, so a separator or a control
     *  character that some future filesystem treats specially is excluded by DEFAULT rather than by
     *  someone having thought of it. */
    private val DISALLOWED = Regex("[^A-Za-z0-9._-]")

    fun of(declared: String?): String {
        // Take the last segment on BOTH separators first. The reduction below already destroys `/`
        // and `\`, so this changes no outcome today -- it is here so that "a/b/../../etc/passwd"
        // reduces to `passwd` rather than to one long underscore-run that merely happens to be
        // harmless. A name that survives should be the name the sender meant, and a rule whose
        // safety depends on a mangling side effect is one nobody can safely edit later.
        val leaf = declared.orEmpty().substringAfterLast('/').substringAfterLast('\\')
        val reduced = leaf.replace(DISALLOWED, "_")
        // `.` and `..` are DIRECTORIES, and a name of nothing but dots is one of them however many
        // there are. `File(dir, "..")` is the parent, and writing bytes to it throws rather than
        // escaping -- so this is not a traversal hole, it is a crash where a file should be.
        if (reduced.isBlank() || reduced.all { it == '.' }) return FALLBACK
        // A leading dot hides the file from every file manager the user might go looking in. The
        // rest of the name is kept: `.bashrc` becomes `bashrc`, not [FALLBACK].
        val visible = reduced.trimStart('.').ifBlank { return FALLBACK }
        return truncate(visible)
    }

        /** Shorten from the MIDDLE of the stem, never the end. The extension is what decides which
         *  app opens the file, so a plain `take(MAX_LENGTH)` on a long name would silently turn a
         *  `.pdf` into an extension-less blob -- a security-relevant change made by a length limit. */
    private fun truncate(name: String): String {
        if (name.length <= MAX_LENGTH) return name
        val dot = name.lastIndexOf('.')
        // No extension, or one so long it is not an extension: a plain cut is all there is to do.
        if (dot <= 0 || name.length - dot > 16) return name.take(MAX_LENGTH)
        val extension = name.substring(dot)
        return name.take(MAX_LENGTH - extension.length) + extension
    }
}
