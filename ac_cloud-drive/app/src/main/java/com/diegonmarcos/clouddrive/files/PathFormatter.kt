package com.diegonmarcos.clouddrive.files

/**
 * #875 the ONE place a folder's path becomes the text "Copy path" puts on the clipboard. Pure
 * (no Android import) so the JVM test names every case:
 *  - a local volume copies its absolute filesystem path (no trailing slash, except the root `/`);
 *  - a SAF volume copies its content URI as granted — never a guessed filesystem path;
 *  - an rclone mount copies the remote spec `remote:path`, the form `rclone` itself takes.
 * It formats exactly what it is given: no credential, token or config is ever read here, so the
 * clipboard (and the snack, which does not echo the value) cannot leak a secret.
 */
object PathFormatter {

    sealed interface Target {
        data class Local(val path: String) : Target
        data class Saf(val uri: String) : Target
        data class Rclone(val remote: String, val path: String = "") : Target
    }

    fun format(target: Target): String = when (target) {
        is Target.Local -> target.path.trimEnd('/').ifEmpty { "/" }
        is Target.Saf -> target.uri.trim()
        is Target.Rclone -> target.remote.trim().trimEnd(':') + ":" + target.path.trim().trimStart('/').trimEnd('/')
    }

    /** What a pane location copies: a local folder its path, a folder inside a zip `zip!/inner`. */
    fun forLocation(location: Location): String = when (location) {
        is Location.Local -> format(Target.Local(location.path))
        is Location.Archive -> format(Target.Local(location.zipPath)) + "!/" + location.inner
    }

    /** One line per location, in the order given — a multi-selection pastes as a list. */
    fun forLocations(locations: List<Location>): String = locations.joinToString("\n") { forLocation(it) }
}
