package app.sterna.ui.settings

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.sterna.BuildConfig
import app.sterna.R
import com.diegonmarcos.superapp.updater.AutoUpdatePrefs
import com.diegonmarcos.superapp.updater.BuildAge
import com.diegonmarcos.superapp.updater.Fleet
import com.diegonmarcos.superapp.updater.UpdateProgress
import com.diegonmarcos.superapp.updater.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Configs ▸ Update — what this build is, what is published, where it came from,
 * and the one button that goes and gets it.
 *
 * ## Nothing here is an update mechanism
 * Every fact and every action on this page comes from `:libs:updater`, the same
 * library Constellation (the owner's app store) drives its own updates with,
 * against the same image the store distributes for this app. The store itself
 * could not be asked to do this: it publishes no component another app can
 * invoke, so "update Cloud Mail now" has nothing to delegate to. The library is
 * COMPILED INTO this APK, so none of it needs the SuperApp to be installed,
 * running, or even building — which matters, because the SuperApp has spent
 * weeks failing to publish and an update path that waits on it is an update
 * path that is down for as long as it is.
 *
 * ## The page's job is to be true
 * Facts the owner cannot otherwise get at: which build is installed, what is
 * published and how big it is and when, what the last check found, and when
 * that check happened. All of them are read, never inferred — and "no check has
 * ever completed" renders as its own sentence rather than as a blank that looks
 * like "up to date".
 *
 * ## Where the addresses come from
 * `constellation-fleet.json`, by way of `BuildConfig.CONSTELLATION_FLEET_B64`
 * and [Fleet.parse]. The entry is matched on the running package id rather than
 * on the literal `"mail"`, because the package id is this app's own identity
 * and `build.json::forks.mail.app_id` is its single source of truth; matching on
 * a name would let a renamed entry hand this screen someone else's links. No
 * match means no link rows at all and a sentence saying why — see [Artefact].
 *
 * ## Baked-in, with the volatile half fetched
 * The manifest is BAKED IN. That is a deliberate choice and it has a cost worth
 * naming: change a URL in the repository and an already-installed copy keeps the
 * old one until it is rebuilt. It is still right here, because these four
 * addresses are IDENTITY — the repository, the rolling release, the asset name,
 * the package page — and identity that arrives over the network is identity an
 * attacker on the network gets to choose. The two facts that genuinely move on
 * every ship, the SIZE and the PUBLISH TIME, are fetched live instead: baking
 * those would be shipping a number guaranteed to be wrong by the next push.
 */
@Composable
internal fun UpdateScreen(onBack: () -> Unit, onOpenUrl: (String) -> Unit) {
    val context = LocalContext.current

    // The updater is a plain singleton with a callback, not a flow - it must not
    // drag coroutines into every app that links it. Mirror it into Compose state
    // through the OBSERVER slot, never setListener: that slot belongs to a host's
    // full-screen overlay, and taking it would silently switch the overlay off
    // for as long as this screen is open.
    var progress by remember { mutableStateOf(UpdateProgress.state) }
    DisposableEffect(Unit) {
        val observer: (UpdateProgress.State) -> Unit = { progress = it }
        UpdateProgress.addObserver(observer)   // replays the current state on register
        onDispose { UpdateProgress.removeObserver(observer) }
    }

    // Re-read on every state change rather than once: a completed check writes
    // this, so a page that read it only at first composition would keep showing
    // the previous answer for as long as it stayed open.
    val lastCheck = remember(progress) { AutoUpdatePrefs.lastCheck(context) }

    var autoUpdate by remember { mutableStateOf(AutoUpdatePrefs.enabled(context)) }
    var wifiOnly by remember { mutableStateOf(AutoUpdatePrefs.requireUnmetered(context)) }

    // THIS app's fleet entry, by package id. Parsed once: it is a constant of
    // the build and re-decoding base64 on every recomposition buys nothing.
    //
    // BuildConfig.MAIL_FLEET_B64, NOT the updater library's CONSTELLATION_FLEET_B64.
    // The library reads the manifest from the consuming app's own data/ dir, and
    // ac_cloud-mail has no data/ dir - so its copy is the empty string in every
    // cloud-mail build, and a page reading it would have shown no links while
    // looking exactly like one that worked. Mail bakes its OWN entry, and only
    // its own, in app/build.gradle.kts.
    val app = remember {
        Fleet.parse(BuildConfig.MAIL_FLEET_B64)
            .firstOrNull { it.pkg == BuildConfig.APPLICATION_ID }
    }

    // The published artefact, probed live. Null until the probe returns, and
    // null forever if there is no entry to probe.
    var artefact by remember { mutableStateOf<Fleet.ReleaseAsset?>(null) }
    var probing by remember { mutableStateOf(app != null) }
    // Pressing "Check for updates" is also a request to re-read this row: the
    // size and the timestamp are the evidence a ship actually landed, and a
    // stale pair of them is the thing that makes a finished ship look absent.
    var probeTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(app, probeTick) {
        if (app == null) return@LaunchedEffect
        probing = true
        artefact = withContext(Dispatchers.IO) { Fleet.releaseAsset(app) }
        probing = false
    }

    DetailScaffold(title = stringResource(R.string.settings_update_screen_title), onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            SettingsSection(stringResource(R.string.settings_update_this_build_section)) {
                Fact(
                    stringResource(R.string.settings_update_installed_label),
                    // BuildConfig of the RUNNING process, so this cannot go stale
                    // the way a cached lookup can: a completed self-update
                    // replaces the process, and the replacement reports itself.
                    "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · " +
                        BuildAge.describe(BuildConfig.VERSION_CODE.toLong()),
                )
                Fact(
                    stringResource(R.string.settings_update_available_label),
                    availableText(lastCheck),
                )
                Fact(
                    stringResource(R.string.settings_update_last_checked_label),
                    lastCheck?.let {
                        DateUtils.getRelativeTimeSpanString(
                            it.atMillis, System.currentTimeMillis(),
                            DateUtils.MINUTE_IN_MILLIS,
                        ).toString()
                    } ?: stringResource(R.string.settings_update_never_checked),
                )
            }

            Artefact(app, artefact, probing)

            SettingsSection(stringResource(R.string.settings_update_now_section)) {
                Help(stringResource(R.string.settings_update_now_help))
                Button(
                    // Deliberately NOT disabled while a check runs. Updater.checkNow
                    // enqueues with REPLACE, so a second press cancels the first and
                    // starts over - which is what someone pressing again wants. A
                    // button greyed out against a check that has silently died is the
                    // state with no way out.
                    onClick = { Updater.checkNow(context); probeTick++ },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) { Text(stringResource(R.string.settings_update_check_now)) }
                StatusLine(progress)
            }

            Links(app, onOpenUrl)

            SettingsSection(stringResource(R.string.settings_update_automatic_section)) {
                SettingSwitch(
                    title = stringResource(R.string.settings_update_auto_title),
                    subtitle = stringResource(R.string.settings_update_auto_subtitle),
                    checked = autoUpdate,
                    onCheckedChange = { on ->
                        AutoUpdatePrefs.setEnabled(context, on)
                        autoUpdate = on
                        // start() re-reads the toggle and CANCELS the schedule when it
                        // is off, so the same call is both the arm and the disarm.
                        // Without it, turning the switch off left the periodic worker
                        // running until the next launch.
                        Updater.start(context)
                    },
                )
                SettingSwitch(
                    title = stringResource(R.string.settings_update_wifi_only_title),
                    subtitle = stringResource(R.string.settings_update_wifi_only_subtitle),
                    checked = wifiOnly,
                    onCheckedChange = { on ->
                        AutoUpdatePrefs.setRequireUnmetered(context, on)
                        wifiOnly = on
                    },
                )
            }
        }
    }

    // THE ONE PLACE MOBILE DATA GETS SPENT ON PURPOSE.
    //
    // An automatic pass never downloads over a metered connection. A press of
    // "Check for updates" is a deliberate act and MAY, but the rule this fleet
    // learned by being billed for it is that it has to say the number first -
    // so the worker stops at the manifest, which is kilobytes, and publishes
    // the download size as UpdateAvailable. That is this dialog. Answering it
    // is what carries consent into Updater.downloadNow; dismissing it spends
    // nothing and leaves the update to arrive on Wi-Fi.
    (progress as? UpdateProgress.State.UpdateAvailable)?.let { available ->
        AlertDialog(
            onDismissRequest = { UpdateProgress.reset() },
            title = { Text(stringResource(R.string.settings_update_metered_title)) },
            text = {
                Text(stringResource(R.string.settings_update_metered_body, megabytes(available.totalBytes)))
            },
            confirmButton = {
                TextButton(onClick = {
                    // Clear the question before asking the worker, so the dialog goes
                    // away on the tap rather than lingering until the worker's first
                    // state lands. reset() is synchronous; the worker starts after it.
                    UpdateProgress.reset()
                    Updater.downloadNow(context)
                }) {
                    Text(stringResource(R.string.settings_update_metered_download))
                }
            },
            dismissButton = {
                TextButton(onClick = { UpdateProgress.reset() }) {
                    Text(stringResource(R.string.settings_update_metered_later))
                }
            },
        )
    }
}

/**
 * The published APK, named the way the owner reasons about it: by filename, by
 * SIZE IN BYTES, and by WHEN IT WAS WRITTEN.
 *
 * Those last two are the pair that answers "did my ship actually land", which no
 * other screen in this app can answer at all. They come from one HEAD against
 * the release asset for this device's ABI — the same probe and the same URL the
 * downloader itself uses, so this row cannot quote a size the download then
 * disagrees with.
 *
 * Four ways this can have no answer, and each says which:
 *  - no fleet entry in this build, so there is nothing to probe;
 *  - the probe has not come back yet;
 *  - the probe could not reach anything at all;
 *  - the server answered and REFUSED, in which case the status code is on
 *    screen. 403 and 404 are different problems with different fixes and #257
 *    was a week spent on a generic "failed" that could have said which.
 */
@Composable
private fun Artefact(app: Fleet.App?, asset: Fleet.ReleaseAsset?, probing: Boolean) {
    SettingsSection(stringResource(R.string.settings_update_published_section)) {
        if (app == null) {
            Fact(
                stringResource(R.string.settings_update_asset_label),
                stringResource(R.string.settings_update_no_fleet_entry),
            )
            return@SettingsSection
        }
        Fact(
            stringResource(R.string.settings_update_asset_label),
            UpdateLinks.assetName(app.abiReleaseUrl)
                ?: stringResource(R.string.settings_update_asset_unknown),
        )
        val sizeLabel = stringResource(R.string.settings_update_size_label)
        when {
            probing ->
                Fact(sizeLabel, stringResource(R.string.settings_update_published_probing))
            asset == null ->
                Fact(sizeLabel, stringResource(R.string.settings_update_published_unreachable))
            !asset.ok ->
                Fact(sizeLabel, stringResource(R.string.settings_update_published_refused, asset.status))
            else -> {
                Fact(
                    sizeLabel,
                    UpdateLinks.size(asset.bytes)
                        ?: stringResource(R.string.settings_update_size_undeclared),
                )
                Fact(
                    stringResource(R.string.settings_update_published_at_label),
                    UpdateLinks.publishedAt(asset.publishedAtMillis)
                        ?: stringResource(R.string.settings_update_published_at_unknown),
                )
            }
        }
    }
}

/**
 * Everywhere this build came from, as four rows that leave the app.
 *
 * [onOpenUrl] is a parameter and NO Context is taken here, which is the whole
 * point: #156 counted six ad-hoc `startActivity(ACTION_VIEW)` sites scattered
 * through this app and treated them as the bug they are. A row added to this
 * section later has nothing to fire an intent WITH, so it has to go through the
 * one opener — which is also the one that carries the double-tap guard (#106).
 *
 * Every address is carried verbatim out of `constellation-fleet.json` except the
 * release page, derived by [UpdateLinks.releasePageUrl] from the download URL in
 * that same file. A row whose address is missing or underivable is NOT DRAWN:
 * this page may be short, and may not be wrong.
 */
@Composable
private fun Links(app: Fleet.App?, onOpenUrl: (String) -> Unit) {
    if (app == null) return
    SettingsSection(stringResource(R.string.settings_update_links_section)) {
        UpdateLinks.releasePageUrl(app.releaseUrl)?.let { page ->
            LinkRow(Icons.Filled.Link, stringResource(R.string.settings_update_link_release), page, onOpenUrl)
        }
        app.abiReleaseUrl.takeIf { it.isNotBlank() }?.let { apk ->
            LinkRow(Icons.Filled.SaveAlt, stringResource(R.string.settings_update_link_apk), apk, onOpenUrl)
        }
        app.repoUrl.takeIf { it.isNotBlank() }?.let { repo ->
            LinkRow(Icons.Filled.Code, stringResource(R.string.settings_update_link_repo), repo, onOpenUrl)
        }
        app.ghcrPage.takeIf { it.isNotBlank() }?.let { pkg ->
            LinkRow(Icons.Filled.Storage, stringResource(R.string.settings_update_link_ghcr), pkg, onOpenUrl)
        }
    }
}

/** One leaving row: what it is, and the address it goes to, shown in full. */
@Composable
private fun LinkRow(
    icon: ImageVector,
    title: String,
    url: String,
    onOpenUrl: (String) -> Unit,
) {
    // The URL IS the summary. He reads these to check where they point before
    // tapping, so an abbreviated one would defeat the row.
    SettingsCategoryRow(icon, title, url.removePrefix("https://")) { onOpenUrl(url) }
}

/** A read-only line: what it is, and what it currently says. */
@Composable
private fun Fact(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Help(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/**
 * What the pipeline is doing, in a sentence.
 *
 * WHICH states may read as success is decided by [UpdateLinks.statusKind] and
 * not here, because that is the decision worth executing in a test and a
 * `@Composable` cannot be executed by this suite. This function only turns the
 * classification into words and a colour.
 */
@Composable
private fun StatusLine(state: UpdateProgress.State) {
    if (UpdateLinks.statusKind(state) == UpdateLinks.StatusKind.HIDDEN) return
    val text = when (state) {
        is UpdateProgress.State.Idle -> return
        is UpdateProgress.State.Cancelled -> return
        is UpdateProgress.State.UpdateAvailable -> return
        is UpdateProgress.State.CheckingManifest -> stringResource(R.string.settings_update_status_checking)
        is UpdateProgress.State.Downloading ->
            // total <= 0 means the server declined to declare a length. A hard 0%
            // is indistinguishable from stuck, so say the bytes and no percentage.
            if (state.total > 0) {
                stringResource(
                    R.string.settings_update_status_downloading,
                    state.percent, megabytes(state.bytes), megabytes(state.total),
                )
            } else {
                stringResource(R.string.settings_update_status_downloading_unsized, megabytes(state.bytes))
            }
        is UpdateProgress.State.Waiting -> state.reason
        is UpdateProgress.State.Installing -> stringResource(R.string.settings_update_status_installing)
        is UpdateProgress.State.Done -> stringResource(R.string.settings_update_status_done)
        is UpdateProgress.State.Failed -> state.message
    }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (UpdateLinks.statusKind(state) == UpdateLinks.StatusKind.FAILURE) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/**
 * What the last completed check found.
 *
 * Three different answers, and none of them may render as another: never
 * checked, checked and current, checked and something newer is published. The
 * published build is named by the first 12 hex of its manifest digest - the same
 * identifier the Constellation store shows for a pending update - because the
 * manifest carries no version number to show. Its versionCode only becomes
 * knowable once the APK is on disk, which is also where it is checked against
 * the installed one before anything is staged.
 */
@Composable
private fun availableText(last: AutoUpdatePrefs.LastCheck?): String = when {
    last == null -> stringResource(R.string.settings_update_available_unknown)
    last.upToDate -> stringResource(R.string.settings_update_available_current)
    else -> stringResource(
        R.string.settings_update_available_new,
        last.remoteDigest12, megabytes(last.remoteBytes),
    )
}

/**
 * Megabytes, decimal - 10^6, not 2^20.
 *
 * The number on this page exists so the owner can weigh it against a data
 * allowance, and an allowance is sold in decimal MB. Rendering 2^20 here would
 * quote a figure ~5% under what the connection is billed for, on the one screen
 * whose whole purpose is to be honest about the cost.
 */
private fun megabytes(bytes: Long): String = "${bytes / 1_000_000} MB"
