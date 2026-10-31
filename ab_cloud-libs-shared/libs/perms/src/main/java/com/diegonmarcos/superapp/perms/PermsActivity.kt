package com.diegonmarcos.superapp.perms

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * #1274 the Permissions page libs:perms hosts in any app without a [PermsHost] page of its own (Calc, Browser, Nav,
 * Keyboard, Drive, Store, ...), and for contexts with no activity at all (an IME, a plugin, a notification). It is the one
 * place in that app that asks: opened by [PermsLink.open] it lists the requesting feature's entries first and highlighted,
 * then every other applicable entry this app declares. Runtime entries go through the system dialog, the rest through
 * [PermsSettings.open] (the exact settings page). Re-read on every resume, so a grant made in Settings shows on return.
 * #1372 first run ([FIRST_RUN]) is a bottom sheet over the app (the window is translucent): what is missing in plain
 * words, Grant all and Later; it closes by itself once nothing is missing.
 */
class PermsActivity : ComponentActivity() {
    private var tick by mutableIntStateOf(0)

    private val ask = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val feature = intent.getStringExtra(EXTRA_FEATURE).orEmpty()
        val ids = intent.getStringArrayExtra(EXTRA_IDS)?.toSet().orEmpty()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                if (feature == FIRST_RUN) Sheet(ids, tick) else Surface(Modifier.fillMaxSize()) { Page(feature, ids, tick) }
            }
        }
    }

    override fun onResume() { super.onResume(); tick++ }

    @Composable
    private fun Page(feature: String, ids: Set<String>, tick: Int) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                when (feature) { "" -> "Permissions"; else -> "Permissions needed by $feature" },
                fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp).semantics { heading() }.testTag("perms:title"))
            // #1360 the one shared section: this app's declared grants and its helper apps', live, Grant / Open, Grant all.
            AppPermsSection(tick, highlight = ids)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Sheet(ids: Set<String>, tick: Int) {
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val perms = rememberAppPerms(tick, ids)
        val name = remember { applicationInfo.loadLabel(packageManager).toString() }
        // nothing left to ask (granted from the sheet, or in Settings meanwhile): the sheet has done its job
        LaunchedEffect(perms.missing) { if (perms.missing == 0) finish() }
        ModalBottomSheet(onDismissRequest = { finish() }, sheetState = state, containerColor = MaterialTheme.colorScheme.surface) {
            FirstRunSheetBody(name, perms.items, onGrantAll = perms.grantAll, onLater = { finish() })
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
