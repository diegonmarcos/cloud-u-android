package com.diegonmarcos.clouddrive.startup

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import com.diegonmarcos.clouddrive.DriveDebugLog
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.ui.DriveMetrics

/** What the user can do about a startup failure (pure, so every error maps to its remedies in a unit test). */
enum class StartupAction { INSTALL_TERMINAL, OPEN_TERMINAL, GRANT_ACCESS, RETRY, VIEW_LOG, CONTINUE_LOCAL }

fun actionsFor(e: StartupError): List<StartupAction> = when (e) {
    is StartupError.TermuxMissing -> listOf(StartupAction.INSTALL_TERMINAL, StartupAction.RETRY, StartupAction.CONTINUE_LOCAL)
    is StartupError.TermuxNotStarted -> listOf(StartupAction.OPEN_TERMINAL, StartupAction.RETRY, StartupAction.VIEW_LOG, StartupAction.CONTINUE_LOCAL)
    is StartupError.HomeUnavailable -> listOf(StartupAction.OPEN_TERMINAL, StartupAction.RETRY, StartupAction.VIEW_LOG, StartupAction.CONTINUE_LOCAL)
    is StartupError.PermissionDenied -> listOf(StartupAction.GRANT_ACCESS, StartupAction.RETRY, StartupAction.CONTINUE_LOCAL)
    is StartupError.MountFailed -> listOf(StartupAction.RETRY, StartupAction.VIEW_LOG, StartupAction.CONTINUE_LOCAL)
    is StartupError.Timeout -> listOf(StartupAction.OPEN_TERMINAL, StartupAction.RETRY, StartupAction.VIEW_LOG, StartupAction.CONTINUE_LOCAL)
}

object StartupTags {
    const val GATE = "startup_gate"
    const val ERROR = "startup_error"
}

/**
 * #1106 the screen for every state before the file list: "Starting terminal…" / "Mounting…" while the
 * sequence runs; on an error a clear message, its remedies, and "Continue with local files" (degraded mode:
 * the file list opens on what is available without the terminal). Ready is the caller's cue to draw the shell.
 */
@Composable
fun StartupGate(
    state: StartupState,
    terminalPackage: String,
    onRetry: () -> Unit,
    onContinueLocal: () -> Unit,
    onGrantAccess: () -> Unit,
) {
    val ctx = LocalContext.current
    var showLog by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(DriveMetrics.padWide).testTag(StartupTags.GATE),
        verticalArrangement = Arrangement.spacedBy(DriveMetrics.pad, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (state) {
            is StartupState.Failed -> {
                Text(stringResource(R.string.startup_error_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                Text(message(state.error), Modifier.testTag(StartupTags.ERROR), color = MaterialTheme.colorScheme.onSurfaceVariant)
                actionsFor(state.error).forEach { a ->
                    val label = stringResource(labelOf(a))
                    val go: () -> Unit = {
                        when (a) {
                            StartupAction.INSTALL_TERMINAL -> openInstall(ctx)
                            StartupAction.OPEN_TERMINAL -> openTerminal(ctx, terminalPackage)
                            StartupAction.GRANT_ACCESS -> onGrantAccess()
                            StartupAction.RETRY -> onRetry()
                            StartupAction.VIEW_LOG -> { showLog = !showLog }
                            StartupAction.CONTINUE_LOCAL -> onContinueLocal()
                        }
                    }
                    if (a == StartupAction.RETRY) Button(onClick = { go() }, Modifier.fillMaxWidth()) { Text(label) }
                    else OutlinedButton(onClick = { go() }, Modifier.fillMaxWidth()) { Text(label) }
                }
                if (showLog) {
                    SelectionContainer(Modifier.weight(1f, fill = false).fillMaxWidth()) {
                        Text(
                            remember(showLog) { DriveDebugLog.tail(ctx, 60) }.ifBlank { stringResource(R.string.startup_log_empty) },
                            Modifier.verticalScroll(rememberScrollState()),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                }
            }
            else -> {
                CircularProgressIndicator()
                Text(stringResource(progressLabel(state)), color = MaterialTheme.colorScheme.onBackground)
            }
        }
    }
}

private fun progressLabel(s: StartupState): Int = when (s) {
    is StartupState.Idle, is StartupState.CheckingTerminal -> R.string.startup_checking
    is StartupState.StartingTerminal -> R.string.startup_starting_terminal
    is StartupState.WaitingForHome -> R.string.startup_waiting_home
    is StartupState.Mounting -> R.string.startup_mounting
    else -> R.string.startup_mounting
}

private fun labelOf(a: StartupAction): Int = when (a) {
    StartupAction.INSTALL_TERMINAL -> R.string.startup_action_install
    StartupAction.OPEN_TERMINAL -> R.string.startup_action_open
    StartupAction.GRANT_ACCESS -> R.string.startup_action_grant
    StartupAction.RETRY -> R.string.startup_action_retry
    StartupAction.VIEW_LOG -> R.string.startup_action_log
    StartupAction.CONTINUE_LOCAL -> R.string.startup_action_local
}

@Composable
private fun message(e: StartupError): String = when (e) {
    is StartupError.TermuxMissing -> stringResource(R.string.startup_err_missing, e.pkg)
    is StartupError.TermuxNotStarted -> stringResource(R.string.startup_err_not_started, e.why)
    is StartupError.HomeUnavailable -> stringResource(R.string.startup_err_home, e.path)
    is StartupError.PermissionDenied -> stringResource(R.string.startup_err_permission, e.permission)
    is StartupError.MountFailed -> stringResource(R.string.startup_err_mount, e.stderr)
    is StartupError.Timeout -> stringResource(R.string.startup_err_timeout, e.at.name.lowercase(), e.afterMs / 1000)
}

private fun openTerminal(ctx: Context, pkg: String) {
    runCatching { ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}

private fun openInstall(ctx: Context) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(INSTALL_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private const val INSTALL_URL = "https://github.com/diegonmarcos/cloud-u-android/releases/tag/latest"
