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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.uikit.KitAction
import com.diegonmarcos.superapp.uikit.KitDates
import com.diegonmarcos.superapp.uikit.KitActionBar
import com.diegonmarcos.superapp.uikit.KitListRow
import com.diegonmarcos.superapp.uikit.KitState
import com.diegonmarcos.superapp.uikit.KitStatusBanner
import com.diegonmarcos.superapp.uikit.KitStep
import com.diegonmarcos.superapp.uikit.KitStepState
import com.diegonmarcos.superapp.uikit.KitStepper
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
 * Setup ▸ runbook (spec 4.6): the [SetupRunbook] steps in order on a [KitStepper] rail (glyph, title,
 * the step's detail line: names and counts, never a value) with one button that re-runs that step only.
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

    var checking by remember { mutableIntStateOf(0) }
    var checkedAt by remember { mutableStateOf("") }

    /**
     * Every step's check, one row at a time off the main thread (a slow check, e.g. verified diffing
     * every app's export, does not hold the others back). [keep]: a row the last run just answered
     * keeps that answer when its check only says TODO (a RUNNING step waits on the user).
     */
    suspend fun checkAll(keep: Set<String> = emptySet()) {
        for ((n, id) in ids.withIndex()) {
            checking = n + 1
            val c = withContext(Dispatchers.IO) { rb.check(id) }
            val prev = rows[id]
            rows[id] = if (id in keep && prev != null && c.optString("state") == "TODO") prev else c
        }
        checking = 0
        checkedAt = java.time.Instant.now().toString()
    }
    LaunchedEffect(Unit) { busy = true; checkAll(); busy = false }

    fun runOne(id: String) {
        busy = true
        rows[id] = JSONObject().put("id", id).put("state", "RUNNING").put("detail", "…")
        scope.launch {
            rows[id] = withContext(Dispatchers.IO) { rb.run(id) }
            checkAll(keep = setOf(id))
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().testTag(RunbookTags.PAGE)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val banner = runbookBanner(ids.map { rows[it]?.optString("state") ?: "TODO" }, checking,
                checkedAt.takeIf { it.isNotBlank() }?.let { KitDates.relative(it) }.orEmpty())
            KitStatusBanner(banner.text, banner.state, tag = "runbook")
            SaidBanner(result, "runbook:result")
            KitStepper(ids.map { id ->
                val r = rows[id]
                val state = r?.optString("state") ?: "TODO"
                KitStep(
                    id = id, title = stepTitle(id), tag = RunbookTags.row(id),
                    detail = r?.optString("detail").orEmpty(),
                    state = when (state) {
                        "RUNNING" -> KitStepState.RUNNING
                        "DONE", "ALREADY" -> KitStepState.DONE
                        "FAILED" -> KitStepState.FAILED
                        else -> KitStepState.TODO
                    },
                    action = KitAction(if (state == "FAILED") "Retry" else buttonLabel(id), RunbookTags.button(id), !busy) {
                        if (id == SetupRunbook.CONNECTED && state != "ALREADY") open("account", "connect") else runOne(id)
                    },
                )
            })
        }
        KitActionBar(listOf(KitAction("Run all", RunbookTags.RUN_ALL, !busy) {
            busy = true
            scope.launch { sheet = withContext(Dispatchers.IO) { runCatching { rb.plan() }.getOrNull() }; busy = false }
        }), Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
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
                    Text(if (act.isEmpty()) "Nothing to do: every step is already done." else "Will run, in order, stopping at the first that fails:")
                    for (id in act) KitListRow(stepTitle(id), leading = "○", tag = "plan:$id")
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
                        checkAll(keep = out.optJSONArray("steps")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("id") }.toSet() }.orEmpty())
                        busy = false
                    }
                }) { Text("Run") }
            },
            dismissButton = { TextButton(onClick = { sheet = null }) { Text("Cancel") } },
        )
    }
}

/**
 * The banner: it counts CHECKS (and runs, which replace a row until the next check), so a set-up
 * phone opens on "matches" rather than "0 done". [checking] = 1-based step being checked, 0 = idle.
 */
fun runbookBanner(states: List<String>, checking: Int, checkedAt: String = ""): Said {
    val n = states.size
    if (checking > 0) return Said(KitState.BUSY, "Checking step $checking of $n…")
    val failed = states.count { it == "FAILED" }
    val done = states.count { it == "DONE" || it == "ALREADY" }
    val at = if (checkedAt.isBlank()) "" else " · checked $checkedAt"
    return when {
        failed > 0 -> Said(KitState.BAD, "$failed of $n steps need attention · $done done$at")
        done == n && n > 0 -> Said(KitState.OK, "This phone matches the working profile · $n of $n done$at")
        else -> Said(KitState.WARN, "$done of $n steps done · run them in order$at")
    }
}

/** A step's title on the page: its declared id, capitalised (the id stays in the debug op's JSON). */
private fun stepTitle(id: String): String = fieldLabel(id)

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
