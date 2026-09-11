package app.sterna.ui.settings

import com.diegonmarcos.superapp.updater.UpdateProgress
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Everything Configs ▸ Update derives rather than reads, in one pure place.
 *
 * ## Why these live away from the screen
 * A `@Composable` cannot be executed by this app's test suite - there is no
 * Robolectric here, `testReleaseUnitTest` is a plain JVM run. Anything left
 * inside [UpdateScreen] is therefore anything that ships unexecuted, and the
 * two things most worth executing on that page are exactly the two that can be
 * silently wrong: the URL it derives, and whether a failure renders as one.
 *
 * ## Nothing here invents an address
 * The four links the page shows come out of `constellation-fleet.json`, baked
 * into `BuildConfig.CONSTELLATION_FLEET_B64` and parsed by `Fleet`. Three of
 * them are carried verbatim (`release_url`, `repo_url`, `ghcr_page`). Only the
 * human-readable release PAGE is derived, because the manifest carries the
 * direct download and not the page around it - and [releasePageUrl] derives it
 * from that same string rather than composing a second copy of the repository
 * address out of literals. A literal here is a URL that keeps pointing at the
 * old place on the day the fleet moves.
 */
internal object UpdateLinks {

    /**
     * The release PAGE for a `…/releases/download/<tag>/<asset>` URL, or null
     * when the input is not one.
     *
     * FAILS CLOSED. A caller gets null for anything it does not recognise and
     * the row is not drawn, because a link built out of a URL this did not
     * understand is a link that lands somewhere unintended - and the one thing
     * worse than a missing link on this page is a confident wrong one.
     */
    fun releasePageUrl(releaseUrl: String): String? {
        val m = RELEASE_DOWNLOAD.matchEntire(releaseUrl.trim()) ?: return null
        val (origin, tag, _) = m.destructured
        return "$origin/releases/tag/$tag"
    }

    /**
     * The asset's filename - `cloud-comms-mail.apk` - taken from the same URL.
     *
     * Deliberately NOT `substringAfterLast('/')`: that answers something for
     * every string ever handed to it, including the empty one, so it can never
     * report "this is not a release URL". Same pattern, same refusal.
     */
    fun assetName(releaseUrl: String): String? =
        RELEASE_DOWNLOAD.matchEntire(releaseUrl.trim())?.groupValues?.get(3)

    /**
     * A published size, in bytes AND in MB.
     *
     * Bytes first because that is the figure the owner compares one build to
     * the next with, and rounding it away is how two different builds come to
     * look identical. MB second, decimal (10^6), because that is the unit a
     * data allowance is sold in - 2^20 would quote ~5% under what the
     * connection is billed for, on the one screen whose job is to be honest
     * about the cost.
     *
     * A negative [bytes] means the server declared no length; say so rather
     * than printing "-1 bytes".
     */
    fun size(bytes: Long): String? {
        if (bytes < 0) return null
        val mb = bytes.toDouble() / 1_000_000.0
        return String.format(Locale.US, "%,d bytes · %.1f MB", bytes, mb)
    }

    /**
     * When the published APK was last written, as UTC to the second.
     *
     * UTC and a fixed pattern on purpose: this timestamp gets compared against
     * a CI run's own clock and against the previous build's, and a value that
     * shifts with the phone's timezone or its locale cannot be compared to
     * either. 0 means the server sent no `Last-Modified`.
     */
    fun publishedAt(millis: Long): String? {
        if (millis <= 0L) return null
        val f = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(java.util.Date(millis))
    }

    /** How the status line under the buttons must READ. */
    enum class StatusKind {
        /** Draw nothing - there is no sentence to show for this state. */
        HIDDEN,

        /** Something is under way. Never a claim that anything finished. */
        PROGRESS,

        /** The install COMPLETED. Exactly one state may produce this. */
        SUCCESS,

        /** It did not work, and the message says why. Rendered in the error colour. */
        FAILURE,
    }

    /**
     * Classify a pipeline state for the status line.
     *
     * ## This is the trap this page exists not to fall into
     * The fleet's previous updater wrapped its install in `catch (Throwable)`
     * and painted "Installed" over the top of a failure. A button that always
     * says it worked is worse than no button, because it costs the owner the
     * trip to find out it did not.
     *
     * So: [StatusKind.SUCCESS] has ONE producer, [UpdateProgress.State.Done],
     * and Done itself is written in exactly one place on this app's install
     * path - `PackageInstallerReceiver`, on `STATUS_SUCCESS` from the system's
     * own callback. Committing a PackageInstaller session proves nothing; it
     * hands the session over and returns. [UpdateProgress.State.Installing] is
     * therefore PROGRESS and not SUCCESS, and [UpdateProgress.State.Failed] is
     * FAILURE carrying the installer's own words.
     *
     * The `when` is exhaustive over a sealed class with no `else`, so a state
     * added to the updater later cannot slip through as "whatever the fallback
     * said" - it stops the compiler and someone decides.
     */
    fun statusKind(state: UpdateProgress.State): StatusKind = when (state) {
        is UpdateProgress.State.Idle -> StatusKind.HIDDEN
        is UpdateProgress.State.Cancelled -> StatusKind.HIDDEN
        // Asked as a dialog. A second copy in the page body leaves a stale line
        // behind once the dialog has been answered.
        is UpdateProgress.State.UpdateAvailable -> StatusKind.HIDDEN
        is UpdateProgress.State.CheckingManifest -> StatusKind.PROGRESS
        is UpdateProgress.State.Downloading -> StatusKind.PROGRESS
        is UpdateProgress.State.Waiting -> StatusKind.PROGRESS
        is UpdateProgress.State.Installing -> StatusKind.PROGRESS
        is UpdateProgress.State.Done -> StatusKind.SUCCESS
        is UpdateProgress.State.Failed -> StatusKind.FAILURE
    }

    /**
     * `https://host/owner/repo` + `/releases/download/` + `<tag>` + `/` + `<asset>`.
     *
     * The tag and the asset may not contain a slash, which is what stops a
     * longer path from matching and being mistaken for a release URL.
     */
    private val RELEASE_DOWNLOAD =
        Regex("""(https://[^/]+/[^/]+/[^/]+)/releases/download/([^/]+)/([^/]+)""")
}
