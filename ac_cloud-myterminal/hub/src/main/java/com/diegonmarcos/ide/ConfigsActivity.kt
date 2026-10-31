package com.diegonmarcos.ide

import com.diegonmarcos.superapp.fleetconfigs.FleetConfigsEntries
import com.diegonmarcos.superapp.fleetconfigs.FleetConfigsPage
import com.diegonmarcos.superapp.fleetconfigs.fleetConfigsEntryLabel
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.ide.update.UpdateProgress
import com.diegonmarcos.ide.update.Updater
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * Configs — Update + About, equal to Cloud-SuperApp. "Check for updates" runs
 * the same WorkManager flow as the periodic auto-updater and shows the fullscreen
 * UpdateOverlay (UpdateProgress-driven). "About" opens AboutActivity — the FULL
 * SuperApp About architecture (12 sections, long-press-copy, Copy All Infos).
 * Visual language from Ui (dark surface, purple accent, rounded ripple cards).
 *
 * 2026-10-16 owner decision: all apps must be Compose. The page is a ComposeView under
 * CloudKitTheme (Ui.composeView); the connection editor is a Compose dialog.
 */
class ConfigsActivity : AppCompatActivity() {

    private var overlayShown = false

    /** The terminal-status line under its header, and the colour it earned once probed. */
    private var statusSub by mutableStateOf("")
    private var statusColor by mutableStateOf<Color?>(null)

    /** The fallback sshd instructions: null until a probe says there is something to fall back from. */
    private var setupSteps by mutableStateOf<String?>(null)

    /** The backend whose connection override is being edited (null = dialog closed). */
    private var editingConn by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.configs_title)

        // ── Terminal status, probed on open ───────────────────────────────
        // Zero setup is the normal case: the selected terminal is probed through its
        // session service (TerminalSessions.probe), and turns green on its own. The
        // manual sshd steps below are added ONLY when that probe failed, under the
        // precise reason (TerminalRoute.explain) — never as the first thing a new phone
        // shows.
        val currentBackend = TerminalSessions.activeBackend(this)
        val probeTarget = TerminalTargets.effectiveTarget(this, currentBackend)
        statusSub = getString(R.string.cfg_terminal_status_checking, probeTarget.label)
        Thread {
            val p = TerminalSessions.probe(this, SshBackend(this), probeTarget)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                statusSub = when {
                    p.native -> getString(R.string.cfg_terminal_status_native, probeTarget.label)
                    p.usable -> getString(R.string.cfg_terminal_status_ssh, probeTarget.label)
                    else -> getString(R.string.cfg_terminal_status_failed, p.message)
                }
                statusColor = Color(getColor(if (p.usable) R.color.ide_ok else R.color.ide_bad))
                // The fallback instructions, GENERATED from data/terminal-targets.json (one
                // block per declared backend, so ports/users/commands are the values the app
                // dials) — and only when there is something to fall back from.
                if (!p.native) setupSteps = getString(R.string.cfg_terminal_setup_steps, terminalSetupBlocks())
            }
        }.also { it.isDaemon = true }.start()

        setContent { Page(currentBackend) }
    }

    @Composable
    private fun Page(currentBackend: String) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 24.dp),
        ) {
            Ui.Header(
                getString(R.string.configs_title),
                getString(
                    R.string.cfg_autoupdate,
                    if (BuildConfig.AUTO_UPDATE_ENABLED) "on" else "off",
                    BuildConfig.AUTO_UPDATE_INTERVAL_HOURS,
                    BuildConfig.AUTO_UPDATE_TAG,
                ),
            )

            // ── Update ────────────────────────────────────────────────────
            Ui.AppCard(
                "⟳",
                getString(R.string.cfg_check_updates),
                "${BuildConfig.GHCR_NAMESPACE}/${BuildConfig.GHCR_IMAGE}:${BuildConfig.AUTO_UPDATE_TAG}",
            ) { startUpdateCheck() }

            // ── Terminal status ───────────────────────────────────────────
            Ui.Header(getString(R.string.cfg_terminal_status_title), statusSub, statusColor)
            setupSteps?.let { Ui.Header(getString(R.string.cfg_terminal_setup_title), it) }

            // ── Terminal backend switcher ─────────────────────────────────
            val backendTarget = TerminalTargets.forBackend(currentBackend)
            Ui.AppCard(
                "⌨",
                getString(R.string.cfg_terminal_backend),
                getString(R.string.cfg_terminal_backend_sub, backendTarget.label,
                    backendTarget.host, backendTarget.port),
            ) {
                // Advance to the NEXT declared backend, wrapping. Cycling the
                // declared list is what keeps the choice DATA: this used to be an
                // if/else over the two IdePrefs constants, so a third entry in
                // terminal-targets.json would have been switcher-unreachable.
                val keys = TerminalTargets.all().map { it.key }
                if (keys.size > 1) {
                    val at = keys.indexOf(backendTarget.key)
                    IdePrefs.setTerminalBackend(applicationContext, keys[(at + 1) % keys.size])
                    recreate()
                }
            }

            // ── Terminal connection overrides ─────────────────────────────
            val effectiveTarget = TerminalTargets.effectiveTarget(this@ConfigsActivity, currentBackend)
            Ui.AppCard(
                "⚙",
                getString(R.string.cfg_terminal_conn),
                getString(R.string.cfg_terminal_conn_sub, effectiveTarget.label),
            ) { editingConn = currentBackend }

            // ── #787 wake lock while a shell is open (ON by default) ──────
            val wakeWanted = IdePrefs.wakeLockWanted(this@ConfigsActivity)
            Ui.AppCard(
                "⏻",
                getString(R.string.cfg_wake_lock),
                getString(if (wakeWanted) R.string.cfg_wake_lock_on else R.string.cfg_wake_lock_off),
            ) { toggleWakeLock(wakeWanted) }

            // ── Terminal SSH key — copy to authorized_keys ────────────────
            Ui.AppCard(
                "🔑",
                getString(R.string.cfg_terminal_ssh_key),
                getString(R.string.cfg_terminal_ssh_key_sub),
            ) { copySshKey() }

            // ── #1352 Configs ▸ General and ▸ About: the fleet's shared pages (libs:ui-nav-bars) ──
            Ui.AppCard("⚙", fleetConfigsEntryLabel(FleetConfigsPage.General), "Perms, update, battery, storage, notifications, language") {
                FleetConfigsEntries.open(this@ConfigsActivity, FleetConfigsPage.General)
            }
            Ui.AppCard("ℹ", fleetConfigsEntryLabel(FleetConfigsPage.About), "v${BuildConfig.VERSION_NAME} · ${BuildConfig.GIT_SHORT_SHA}") {
                FleetConfigsEntries.open(this@ConfigsActivity, FleetConfigsPage.About)
            }

            // ── Diagnostics: the hub's runtime facts, copyable, and the log push (DiagnosticsPush) ──
            Ui.AppCard(
                "⚕",
                "Diagnostics",
                "Runtime facts, logs and the diagnostics push",
            ) { startActivity(android.content.Intent(this@ConfigsActivity, AboutActivity::class.java)) }
        }
        editingConn?.let { ConnDialog(it) { editingConn = null } }
    }

    /** #787 flip the wake-lock choice and re-sync the lock. */
    private fun toggleWakeLock(wakeWanted: Boolean) {
        IdePrefs.setWakeLockWanted(this, !wakeWanted)
        TerminalWakeLock.sync(this)
        recreate()
    }

    private fun copySshKey() {
        val pubKey = SshBackend(this).publicKeyOpenSsh()
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("cloud-myterminal-terminal pubkey", pubKey))
        Toast.makeText(this, getString(R.string.cfg_terminal_ssh_key_copied), Toast.LENGTH_LONG).show()
    }

    private fun startUpdateCheck() {
        if (!overlayShown) {
            overlayShown = true
            UpdateProgress.update(UpdateProgress.State.CheckingManifest)
            UpdateOverlay.newInstance().show(supportFragmentManager, UpdateOverlay.TAG)
        }
        Updater.checkNow(this)
    }

    override fun onResume() {
        super.onResume()
        // Reset the gate when the overlay is gone so a second tap re-opens it.
        if (supportFragmentManager.findFragmentByTag(UpdateOverlay.TAG) == null) overlayShown = false
    }

    /**
     * One sshd-setup block per DECLARED backend, built from
     * data/terminal-targets.json so the ports, users and install command in the
     * instructions are the same values the app dials. Nothing here names a
     * backend; a new entry in the JSON gets a block for free.
     */
    private fun terminalSetupBlocks(): String =
        TerminalTargets.all().joinToString("\n") { t ->
            getString(
                R.string.cfg_terminal_setup_env_block,
                t.label.uppercase(), t.user, t.host, t.port, t.sshdInstall,
            )
        }

    /** Host / port / user override for [backend], with a live connection test. */
    @Composable
    private fun ConnDialog(backend: String, onClose: () -> Unit) {
        val p = LocalKitPalette.current
        val activity = this@ConfigsActivity
        val effective = remember(backend) { TerminalTargets.effectiveTarget(activity, backend) }
        val ssh = remember { SshBackend(activity) }
        var host by remember { mutableStateOf(effective.host) }
        var port by remember { mutableStateOf(effective.port.toString()) }
        var user by remember { mutableStateOf(effective.user) }
        var status by remember { mutableStateOf("") }
        var statusTone by remember { mutableStateOf<Color?>(null) }
        val ok = colorResource(R.color.ide_ok)
        val bad = colorResource(R.color.ide_bad)

        AlertDialog(
            onDismissRequest = onClose,
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Hint line
                    Text("Connecting to ${effective.label} — SSH", color = p.textSecondary, fontSize = 13.sp)
                    OutlinedTextField(
                        value = host, onValueChange = { host = it }, singleLine = true,
                        label = { Text(activity.getString(R.string.term_conn_host)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = port, onValueChange = { port = it }, singleLine = true,
                        label = { Text(activity.getString(R.string.term_conn_port)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = user, onValueChange = { user = it }, singleLine = true,
                        label = { Text(activity.getString(R.string.term_conn_user)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // Test button + status label
                    Button(
                        onClick = {
                            val h = host.trim(); val u = user.trim()
                            val pt = port.trim().toIntOrNull() ?: -1
                            if (h.isBlank() || pt <= 0 || u.isBlank()) {
                                statusTone = bad
                                status = "Fill in valid host, port and user first"
                            } else {
                                val probe = TerminalTargets.Target(
                                    key   = effective.key,
                                    label = effective.label,
                                    host  = h,
                                    port  = pt,
                                    user  = u,
                                )
                                statusTone = null
                                status = activity.getString(R.string.term_conn_testing)
                                Thread {
                                    val err = ssh.testConnection(probe)
                                    activity.runOnUiThread {
                                        if (err == null) {
                                            statusTone = ok
                                            status = activity.getString(R.string.term_conn_ok)
                                        } else {
                                            statusTone = bad
                                            status = "✗ $err"
                                        }
                                    }
                                }.also { it.isDaemon = true }.start()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(activity.getString(R.string.term_conn_test)) }
                    Text(status, color = statusTone ?: p.textSecondary, fontSize = 13.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val h = host.trim(); val u = user.trim()
                    val pt = port.trim().toIntOrNull()
                    if (h.isBlank() || pt == null || pt <= 0 || u.isBlank()) {
                        Toast.makeText(activity, "Invalid host, port or user — not saved", Toast.LENGTH_LONG).show()
                    } else {
                        IdePrefs.setTerminalConn(activity, backend, h, pt, u)
                        Toast.makeText(activity, activity.getString(R.string.term_conn_saved), Toast.LENGTH_SHORT).show()
                        onClose()
                        recreate()
                    }
                }) { Text(activity.getString(R.string.term_conn_save)) }
            },
            dismissButton = { TextButton(onClick = onClose) { Text(activity.getString(R.string.term_conn_cancel)) } },
        )
    }
}
