package com.diegonmarcos.superapp.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitSectionHeader
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

object RunbookTags {
    const val PAGE = "setup:runbook"
    const val RUN_ALL = "runbook:runall"
    const val SHEET = "runbook:plan"
    const val SHEET_GO = "runbook:plan:go"
    fun row(id: String) = "runbook:row:$id"
    fun button(id: String) = "runbook:run:$id"
}

/** ○ todo · ● running · ✓ done (or already) · ✗ failed. */
fun runbookGlyph(state: String): String = when (state) {
    "RUNNING" -> "●"
    "DONE", "ALREADY" -> "✓"
    "FAILED" -> "✗"
    else -> "○"
}

/**
 * Setup ▸ runbook (spec 4.6): the [SetupRunbook] steps in order, one row each (glyph, id, the
 * step's detail line: names and counts, never a value) with one button that re-runs that row only.
 * **Run all** first shows the plan sheet ([SetupRunbook.plan]: which steps would act, by name) and
 * acts only on its confirm; then it walks the steps and stops at the first ✗. `connected` opens
 * Account ▸ connect instead of running (sign-in is a page, not a step).
 */
@Composable
fun RunbookPage(open: (section: String, page: String) -> Unit) {
    val ctx = LocalContext.current
    val p = LocalKitPalette.current
    val scope = rememberCoroutineScope()
    val rb = remember { SetupRunbook(ctx) }
    val ids = remember { rb.declaredIds() }
    val rows = remember { mutableStateMapOf<String, JSONObject>() }
    var busy by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<JSONObject?>(null) }
    var result by remember { mutableStateOf("") }

    fun refresh() {
        busy = true
        scope.launch {
            val d = withContext(Dispatchers.IO) { runCatching { rb.dry() }.getOrNull() }
            d?.optJSONArray("steps")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { rows[it.getString("id")] = it } }
            busy = false
        }
    }
    LaunchedEffect(Unit) { refresh() }

    fun runOne(id: String) {
        busy = true
        rows[id] = JSONObject().put("id", id).put("state", "RUNNING").put("detail", "…")
        scope.launch {
            rows[id] = withContext(Dispatchers.IO) { rb.run(id) }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag(RunbookTags.PAGE),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        KitSectionHeader("Runbook", "make this phone match the working profile, in order; a set-up phone answers ✓ already everywhere")
        OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth().testTag(RunbookTags.RUN_ALL), onClick = {
            busy = true
            scope.launch { sheet = withContext(Dispatchers.IO) { runCatching { rb.plan() }.getOrNull() }; busy = false }
        }) { Text("Run all") }
        if (result.isNotBlank()) Text(result, color = p.textSecondary, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        KitCard {
            for (id in ids) {
                val r = rows[id]
                val state = r?.optString("state") ?: "TODO"
                Column(Modifier.fillMaxWidth().testTag(RunbookTags.row(id))) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${runbookGlyph(state)} $id", color = p.textPrimary, style = MaterialTheme.typography.bodyMedium)
                        TextButton(enabled = !busy, modifier = Modifier.testTag(RunbookTags.button(id)), onClick = {
                            if (id == SetupRunbook.CONNECTED && state != "ALREADY") open("account", "connect") else runOne(id)
                        }) { Text(buttonLabel(id)) }
                    }
                    Text(r?.optString("detail").orEmpty(), color = if (state == "FAILED") p.accent else p.textSecondary,
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    sheet?.let { plan ->
        val steps = plan.optJSONArray("steps")
        val act = (0 until (steps?.length() ?: 0)).map { steps!!.getJSONObject(it) }
            .filter { it.optString("state") !in setOf("ALREADY", "DONE") }.map { it.optString("id") }
        AlertDialog(
            modifier = Modifier.testTag(RunbookTags.SHEET),
            onDismissRequest = { sheet = null },
            title = { Text("Run all: ${act.size} of ${plan.optInt("declared")} steps") },
            text = {
                Column {
                    Text(if (act.isEmpty()) "Nothing to do: every step is already done." else "Will run, in order, stopping at the first ✗:")
                    for (id in act) Text("  ○ $id", fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = {
                TextButton(modifier = Modifier.testTag(RunbookTags.SHEET_GO), onClick = {
                    sheet = null
                    busy = true
                    scope.launch {
                        val out = withContext(Dispatchers.IO) {
                            rb.runAll { row -> scope.launch { rows[row.getString("id")] = row } }
                        }
                        result = out.optString("result")
                        busy = false
                    }
                }) { Text("Run") }
            },
            dismissButton = { TextButton(onClick = { sheet = null }) { Text("Cancel") } },
        )
    }
}

private fun buttonLabel(id: String): String = when (id) {
    SetupRunbook.CONNECTED -> "Connect"
    SetupRunbook.PROFILE -> "Pick"
    SetupRunbook.SHELL -> "Pair"
    SetupRunbook.STORE -> "Install"
    SetupRunbook.APPS -> "Open Store"
    SetupRunbook.CONFIGS -> "Apply"
    SetupRunbook.PERMS -> "Grant all"
    SetupRunbook.VERIFIED -> "Re-check"
    else -> "Run"
}
