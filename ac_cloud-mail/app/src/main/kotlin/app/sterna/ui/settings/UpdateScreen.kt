package app.sterna.ui.settings

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.sterna.BuildConfig
import app.sterna.R
import com.diegonmarcos.superapp.updater.AutoUpdatePrefs
import com.diegonmarcos.superapp.updater.BuildAge
import com.diegonmarcos.superapp.updater.UpdateProgress
import com.diegonmarcos.superapp.updater.Updater

/**
 * Configs ▸ Update — what this build is, what is published, and the one button
 * that goes and looks.
 *
 * ## Nothing here is an update mechanism
 * Every fact and every action on this page comes from `:libs:updater`, the same
 * library Constellation (the owner's app store) drives its own updates with,
 * against the same GHCR image the store distributes for this app. The store
 * itself could not be asked to do this: it publishes no component another app
 * can invoke, so "update Cloud Mail now" has nothing to delegate to. Linking
 * the store's own engine is what keeps this ONE update path instead of two.
 *
 * ## The page's job is to be true
 * Three facts the owner cannot otherwise get at: which build is installed, what
 * the last check found, and when that check happened. All three are read, never
 * inferred - and "no check has ever completed" renders as its own sentence
 * rather than as a blank that looks like "up to date".
 */
@Composable
internal fun UpdateScreen(onBack: () -> Unit) {
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

    DetailScaffold(title = stringResource(R.string.settings_update_screen_title), onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            SettingsSection(stringResource(R.string.settings_update_this_build_section)) {
                Fact(
                    stringResource(R.string.settings_update_installed_label),
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

            SettingsSection(stringResource(R.string.settings_update_now_section)) {
                Help(stringResource(R.string.settings_update_now_help))
                Button(
                    // Deliberately NOT disabled while a check runs. Updater.checkNow
                    // enqueues with REPLACE, so a second press cancels the first and
                    // starts over - which is what someone pressing again wants. A
                    // button greyed out against a check that has silently died is the
                    // state with no way out.
                    onClick = { Updater.checkNow(context) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) { Text(stringResource(R.string.settings_update_check_now)) }
                StatusLine(progress)
            }

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
 * [UpdateProgress.State.Done] is the ONLY thing here that may read as success,
 * and it has exactly one producer on this app's install path:
 * PackageInstallerReceiver, on STATUS_SUCCESS from the system's own callback.
 * Committing a PackageInstaller session proves nothing - it hands the session
 * over and returns - so no state written before that callback is allowed to say
 * "installed". [UpdateProgress.State.Failed] carries the reason the installer
 * or the refusal gave and is rendered in the error colour, never swallowed.
 */
@Composable
private fun StatusLine(state: UpdateProgress.State) {
    val text = when (state) {
        is UpdateProgress.State.Idle -> return
        is UpdateProgress.State.Cancelled -> return
        // Already a dialog. A second copy of the same question in the page body
        // would leave a stale line behind once the dialog is answered.
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
        color = if (state is UpdateProgress.State.Failed) {
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
