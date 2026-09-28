package com.diegonmarcos.superapp.updater

/**
 * THE identity line of a fleet row (#631), declared once here and composed
 * nowhere else.
 *
 * Every row used to build its own string, so the same three facts came out in
 * as many shapes as there were call sites: `v0.1.1-dev (sha-abc1234)  ·  41.2 MB`
 * on one line, `✓ up to date  ·  v… (29074012)  ·  sha …` on the next. Two of
 * those shapes were also WRONG about what they showed:
 *
 *  - the version was the INSTALLED APK's own versionName, which for a fork is
 *    upstream's string (Mattermost's, Element's) and not ours at all, and which
 *    for our own apps already carries `(sha-…)` inside it — so the row printed
 *    the sha twice in two different lengths;
 *  - the number in brackets was the APK's versionCode, and ours is a
 *    minutes-since-2026 WALL CLOCK from app/build.gradle::codeFinal. Printed
 *    bare beside a version name it reads as a release number, which it is not.
 *
 * So the pattern names every number it prints. OUR version is the declared one
 * carried in the fleet manifest (each app's build.json::android.version_name /
 * version_code, emitted by data/regen.sh); the wall-clock code is labelled
 * `apk build`; the sha is the short form of the published `<asset>.sha256`
 * sidecar (or the installed APK's own digest when that is what was asked for);
 * the size is the real published byte count.
 *
 * HONESTY IS PART OF THE PATTERN. A field that cannot be sourced on this device
 * says so — [NO_VERSION], [NO_SHA], [NO_SIZE] — because the three ways a row
 * used to hide a missing fact (print 0, print an unrelated field, print nothing
 * and leave a gap) are all indistinguishable from the fact being known.
 */
object FleetIdentity {

    /** The one separator. Every surface joins with this or with nothing. */
    const val SEP = "  ·  "

    const val NO_VERSION = "version not declared"
    const val NO_SHA = "sha not published"
    const val NO_SIZE = "size not published"

    /** The fields of the pattern, IN the order the pattern prints them. */
    enum class Field { VERSION, SHA, SIZE }

    /** The whole line: version, sha, size. */
    val FULL: Set<Field> = setOf(Field.VERSION, Field.SHA, Field.SIZE)

    /** What a collapsed row scans by. A SUBSET of [FULL], never a second shape:
     *  the sha is inspection and lives in the detail sheet. */
    val SCAN: Set<Field> = setOf(Field.VERSION, Field.SIZE)

    /**
     * The identity line for [app] in [state], restricted to [fields].
     * Iterates [Field] in declaration order, so no caller can reorder the
     * pattern by passing its set in a different order.
     */
    fun of(app: Fleet.App, state: Fleet.State, fields: Set<Field> = FULL): String =
        Field.values().filter { it in fields }
            .joinToString(SEP) { part(it, app, state) }

    private fun part(field: Field, app: Fleet.App, state: Fleet.State): String = when (field) {
        Field.VERSION -> version(app.declaredVersionName, app.declaredVersionCode, buildCodeOf(state))
        Field.SHA -> sha(sha12Of(state))
        Field.SIZE -> size(state.bytes)
    }

    /**
     * OUR version, and every number in it labelled.
     *
     * [name]/[code] are the DECLARED pair from the manifest; [buildCode] is the
     * installed APK's own versionCode, the wall clock. A row with no declared
     * version (the forks, which keep upstream's) says so rather than borrowing
     * upstream's string and presenting it as ours.
     */
    fun version(name: String?, code: Long, buildCode: Long): String {
        val head = if (name.isNullOrBlank()) NO_VERSION else "v$name"
        val inner = listOfNotNull(
            if (code > 0L) "declared code $code" else null,
            if (buildCode > 0L) "apk build $buildCode" else null,
        )
        return if (inner.isEmpty()) head else "$head (${inner.joinToString(", ")})"
    }

    /** Short sha of the published asset, or the honest marker. */
    fun sha(sha12: String?): String =
        if (sha12.isNullOrBlank()) NO_SHA else "sha $sha12"

    /** Real published size, or the honest marker. 0 bytes is NOT a size. */
    fun size(bytes: Long): String =
        if (bytes > 0L) "size ${human(bytes)}" else NO_SIZE

    /**
     * Bytes as MB/KB — decimal MB, matching what GitHub and the Play Store show
     * for the same APK; a binary-MiB figure here would read as a mismatch.
     * The one size format in the tree.
     */
    fun human(bytes: Long): String =
        if (bytes >= 1_000_000) String.format(java.util.Locale.US, "%.1f MB", bytes / 1_000_000.0)
        else String.format(java.util.Locale.US, "%.0f KB", bytes / 1000.0)

    /** The installed APK's wall-clock versionCode, where the state carries one. */
    private fun buildCodeOf(state: Fleet.State): Long =
        (state as? Fleet.State.Installed)?.versionCode ?: 0L

    /**
     * Whichever digest this state actually holds, never a re-derived one — and
     * only when it IS a digest. [Fleet.State.UpdateAvailable.remoteDigest12]
     * carries the literal `"release"` on the one path where no `.sha256`
     * sidecar answered and the size compare decided instead. Printing that
     * after the word "sha" would be a row claiming a digest it never saw, so
     * anything that is not hex reads as not published.
     */
    private fun sha12Of(state: Fleet.State): String? = when (state) {
        is Fleet.State.Installed -> state.sha12
        is Fleet.State.UpdateAvailable -> state.remoteDigest12
        else -> null
    }?.takeIf { it.isNotBlank() && it.all { c -> c in "0123456789abcdefABCDEF" } }
}
