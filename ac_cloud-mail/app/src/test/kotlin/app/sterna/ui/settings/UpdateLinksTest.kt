package app.sterna.ui.settings

import com.diegonmarcos.superapp.updater.UpdateProgress.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What Configs ▸ Update derives, driven directly.
 *
 * Two things on that page can be silently wrong, and both are here: the address
 * a link row opens, and whether a FAILURE is allowed to read as a success.
 * Neither is tested by "the page exists" — a page with a dead button exists,
 * and the fleet's previous updater said "Installed" over the top of a failed
 * install for weeks while every screenshot of it looked right.
 */
class UpdateLinksTest {

    // -- 1. the release page, derived from the download URL ---------------------------------

    @Test fun `the release page is derived from the download URL`() {
        assertEquals(
            "the manifest carries the DIRECT download; the page around it is derived from that " +
                "same string. Composing it out of literals instead would be a second copy of " +
                "the repository address, free to point at the old place after a move",
            "https://github.com/diegonmarcos/cloud-u-android/releases/tag/latest",
            UpdateLinks.releasePageUrl(MAIL_RELEASE_URL),
        )
    }

    @Test fun `the tag is taken from the URL and never assumed to be latest`() {
        assertEquals(
            "an immutable per-build release has its own tag. Hardcoding 'latest' here would " +
                "send every one of them to the rolling release instead",
            "https://github.com/diegonmarcos/cloud-u-android/releases/tag/v2026.09.11",
            UpdateLinks.releasePageUrl(
                "https://github.com/diegonmarcos/cloud-u-android/releases/download/v2026.09.11/cloud-comms-mail.apk",
            ),
        )
    }

    @Test fun `anything that is not a release download URL derives nothing`() {
        // FAIL CLOSED. Every one of these must be null, because the row is drawn
        // only when a URL comes back — and the one thing worse than a missing
        // link on this page is a confident wrong one.
        val notReleaseUrls = listOf(
            "" to "the empty string",
            "   " to "whitespace",
            "not a url at all" to "prose",
            "https://github.com/diegonmarcos/cloud-u-android" to "the repository root",
            "https://github.com/diegonmarcos/cloud-u-android/releases/tag/latest"
                to "a release PAGE, which is the output and not the input",
            "http://github.com/o/r/releases/download/latest/x.apk" to "plain HTTP",
            "https://github.com/diegonmarcos/cloud-u-android/releases/download/latest"
                to "a tag with no asset after it",
            "https://github.com/diegonmarcos/cloud-u-android/releases/download/latest/nested/x.apk"
                to "an extra path segment, which a loose match would swallow",
        )
        val wrong = notReleaseUrls.mapNotNull { (url, why) ->
            UpdateLinks.releasePageUrl(url)?.let { "$why ('$url') derived '$it' instead of null" }
        }
        assertEquals("these are not release download URLs and must derive nothing", emptyList<String>(), wrong)
    }

    @Test fun `a plain HTTP address is refused, so the page cannot link one`() {
        // The APK row opens the same URL the downloader fetches. TLS is the whole
        // of what stands between the owner and a substituted binary, so a URL that
        // dropped to http:// must not be rendered as if it were the published one.
        assertNull(
            UpdateLinks.releasePageUrl("http://github.com/diegonmarcos/cloud-u-android/releases/download/latest/cloud-comms-mail.apk"),
        )
        assertNull(
            UpdateLinks.assetName("http://github.com/diegonmarcos/cloud-u-android/releases/download/latest/cloud-comms-mail.apk"),
        )
    }

    // -- 2. the asset name ------------------------------------------------------------------

    @Test fun `the asset name is the published file name`() {
        assertEquals("cloud-comms-mail.apk", UpdateLinks.assetName(MAIL_RELEASE_URL))
    }

    @Test fun `the asset name refuses what it does not recognise`() {
        // substringAfterLast('/') would answer something for every one of these,
        // including the empty string, and could therefore never report "this is
        // not a release URL". Same pattern, same refusal.
        val wrong = listOf("", "   ", "x.apk", "https://example.invalid/x.apk")
            .mapNotNull { url -> UpdateLinks.assetName(url)?.let { "'$url' -> '$it'" } }
        assertEquals(emptyList<String>(), wrong)
    }

    // -- 3. the numbers he reasons in -------------------------------------------------------

    @Test fun `a size is stated in bytes first and megabytes second`() {
        assertEquals(
            "bytes first, because that is the figure one build is compared to the next with; " +
                "rounding it away is how two different builds come to look identical",
            "5,016,156 bytes · 5.0 MB",
            UpdateLinks.size(5_016_156L),
        )
    }

    @Test fun `megabytes are decimal, not binary`() {
        // 10^6, not 2^20. A data allowance is sold in decimal MB, so 2^20 would
        // quote ~5% under what the connection is billed for — on the one screen
        // whose purpose is to be honest about the cost.
        assertEquals("1,000,000 bytes · 1.0 MB", UpdateLinks.size(1_000_000L))
        // 5 MiB exactly. Computed against 2^20 this reads "5.0 MB"; against 10^6
        // it reads 5.2, which is the true number of decimal megabytes and the one
        // a data allowance is counted in.
        assertEquals("5,242,880 bytes · 5.2 MB", UpdateLinks.size(5_242_880L))
        assertNotEquals(
            "5,242,880 bytes is 5.0 MiB. Quoting that as MB understates the download by 5%",
            "5,242,880 bytes · 5.0 MB",
            UpdateLinks.size(5_242_880L),
        )
    }

    @Test fun `a size the server never declared is not printed as minus one`() {
        assertNull("HttpURLConnection answers -1 for an absent Content-Length", UpdateLinks.size(-1L))
        assertEquals("0 bytes · 0.0 MB", UpdateLinks.size(0L))
    }

    @Test fun `a publish time is UTC to the second, and fixed`() {
        // The real cloud-comms-mail.apk, as the release served it on 2026-09-11:
        // Last-Modified: Fri, 11 Sep 2026 10:05:31 GMT.
        assertEquals(
            "UTC and a fixed pattern, because this gets compared against a CI run's clock and " +
                "against the previous build's. A value that shifts with the phone's timezone " +
                "cannot be compared to either",
            "2026-09-11 10:05:31 UTC",
            UpdateLinks.publishedAt(1_789_121_131_000L),
        )
    }

    @Test fun `no publish time is said rather than shown as the epoch`() {
        assertNull("0 is what HttpURLConnection returns for an absent Last-Modified — not 1970", UpdateLinks.publishedAt(0L))
        assertNull(UpdateLinks.publishedAt(-1L))
    }

    // -- 4. the trap: a failure may never read as a success -----------------------------------

    @Test fun `a failed install is classified as a failure`() {
        assertEquals(
            "THE TRAP. The fleet's previous updater wrapped its install in catch (Throwable) " +
                "and painted 'Installed' over a failure. A button that always says it worked is " +
                "worse than no button",
            UpdateLinks.StatusKind.FAILURE,
            UpdateLinks.statusKind(State.Failed("INSTALL_FAILED_VERSION_DOWNGRADE")),
        )
        assertNotEquals(
            UpdateLinks.StatusKind.SUCCESS,
            UpdateLinks.statusKind(State.Failed("INSTALL_FAILED_VERSION_DOWNGRADE")),
        )
    }

    @Test fun `committing a session is progress, and is not success`() {
        assertEquals(
            "Installing means the PackageInstaller session was handed over, which proves " +
                "nothing: commit() returns immediately and the outcome arrives later, at the " +
                "receiver. Calling this SUCCESS is how an install that then fails is reported " +
                "as one that worked",
            UpdateLinks.StatusKind.PROGRESS,
            UpdateLinks.statusKind(State.Installing),
        )
    }

    @Test fun `exactly one state may read as success`() {
        val succeeding = ALL_STATES.filter { UpdateLinks.statusKind(it) == UpdateLinks.StatusKind.SUCCESS }
        assertEquals(
            "Done is the only state that may say the update is installed, and Done has ONE " +
                "producer on this app's install path: PackageInstallerReceiver, on the system's " +
                "own STATUS_SUCCESS. Found instead: " + succeeding.map { it::class.simpleName },
            listOf("Done"),
            succeeding.map { it::class.simpleName },
        )
    }

    @Test fun `every kind the page can draw is reachable from some state`() {
        // A `StatusKind` no state produces is a branch of the renderer nothing can
        // ever show — dead UI that reads, from the source, exactly like live UI.
        val reachable = ALL_STATES.map { UpdateLinks.statusKind(it) }.toSet()
        assertEquals(
            "every StatusKind must be produced by at least one state",
            UpdateLinks.StatusKind.entries.toSet(),
            reachable,
        )
        assertEquals("the states exercised here must all be distinct", ALL_STATES.size, ALL_STATES.distinct().size)
    }

    @Test fun `an idle page shows no status line at all`() {
        assertEquals(UpdateLinks.StatusKind.HIDDEN, UpdateLinks.statusKind(State.Idle))
        assertEquals(UpdateLinks.StatusKind.HIDDEN, UpdateLinks.statusKind(State.Cancelled))
        assertEquals(
            "UpdateAvailable is asked as a dialog. A second copy in the page body leaves a " +
                "stale line behind once the dialog has been answered",
            UpdateLinks.StatusKind.HIDDEN,
            UpdateLinks.statusKind(State.UpdateAvailable(5_016_156L)),
        )
    }

    private companion object {
        /** The address constellation-fleet.json carries for this app. Pinned against
         *  the file itself by [MailFleetEntryTest]; repeated here as the input these
         *  derivations are exercised with. */
        const val MAIL_RELEASE_URL =
            "https://github.com/diegonmarcos/cloud-u-android/releases/download/latest/cloud-comms-mail.apk"

        /** Every state the updater can publish, one of each. */
        val ALL_STATES: List<State> = listOf(
            State.Idle,
            State.CheckingManifest,
            State.UpdateAvailable(5_016_156L),
            State.Downloading(42, 2_000_000L, 5_016_156L),
            State.Waiting("waiting for Wi-Fi"),
            State.Installing,
            State.Done,
            State.Failed("INSTALL_FAILED_VERSION_DOWNGRADE"),
            State.Cancelled,
        )
    }
}
