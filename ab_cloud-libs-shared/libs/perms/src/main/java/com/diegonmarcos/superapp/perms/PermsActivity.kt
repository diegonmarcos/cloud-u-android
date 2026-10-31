package com.diegonmarcos.superapp.perms

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * #1274 the Permissions page libs:perms hosts in any app without a [PermsHost] page of its own (Calc, Browser, Nav,
 * Keyboard, Drive, Store, ...), and for contexts with no activity at all (an IME, a plugin, a notification). It is the one
 * place in that app that asks: opened by [PermsLink.open] it lists the requesting feature's entries first and highlighted,
 * then every other applicable entry this app declares. Runtime entries go through the system dialog, the rest through
 * [PermsSettings.open] (the exact settings page). Re-read on every resume, so a grant made in Settings shows on return.
 */
class PermsActivity : ComponentActivity() {
    private var tick by mutableIntStateOf(0)

    private val ask = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val feature = intent.getStringExtra(EXTRA_FEATURE).orEmpty()
        val ids = intent.getStringArrayExtra(EXTRA_IDS)?.toSet().orEmpty()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface { Page(feature, ids, tick) }
            }
        }
    }

    override fun onResume() { super.onResume(); tick++ }

    @Composable
    private fun Page(feature: String, ids: Set<String>, tick: Int) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                when (feature) { "" -> "Permissions"; FIRST_RUN -> "Before you start: grant what this app needs"; else -> "Permissions needed by $feature" },
                style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("perms:title"))
            // #1360 the one shared section: this app's declared grants and its helper apps', live, Grant / Open, Grant all.
            AppPermsSection(tick, PermsStyle(ink = MaterialTheme.colorScheme.onSurface, dim = MaterialTheme.colorScheme.onSurfaceVariant), ids)
            if (feature == FIRST_RUN) OutlinedButton(onClick = { finish() }, modifier = Modifier.testTag("perms:later")) { Text("Later") }
        }
    }

    private fun request(entries: List<PermEntry>) {
        val perms = entries.map { it.permission }.filter { it.isNotEmpty() }.distinct()
        if (perms.isEmpty()) return
        PermAskLog.markAsked(this, perms)
        PermAskLog.markAskedIds(this, entries.map { it.id })
        ask.launch(perms.toTypedArray())
    }

    data class Row(val entry: PermEntry, val status: PermStatus, val wanted: Boolean)

    companion object {
        const val EXTRA_FEATURE = "com.diegonmarcos.perms.FEATURE"
        const val EXTRA_IDS = "com.diegonmarcos.perms.IDS"
        /** The feature name first run opens the page with (PermsFirstRun). */
        const val FIRST_RUN = "first-run"

        fun intent(ctx: Context, feature: String, ids: Collection<String>): Intent =
            Intent(ctx, PermsActivity::class.java)
                .putExtra(EXTRA_FEATURE, feature)
                .putExtra(EXTRA_IDS, ids.toTypedArray())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** The page's rows: the requested entries first (catalogue order), then the rest; not-applicable ones dropped. */
        fun rows(entries: List<PermEntry>, wanted: Set<String>, status: (PermEntry) -> PermStatus): List<Row> =
            entries.map { Row(it, status(it), it.id in wanted) }
                .filter { it.status.state != PermState.NOT_APPLICABLE }
                .sortedBy { if (it.wanted) 0 else 1 }

        fun stateText(s: PermState): String = when (s) {
            PermState.GRANTED -> "Granted"
            PermState.DENIED -> "Not granted"
            PermState.PERMANENTLY_DENIED -> "Denied: grant it in settings"
            PermState.NOT_APPLICABLE -> "Not applicable"
            PermState.UNKNOWN -> "Unknown"
        }
    }
}
