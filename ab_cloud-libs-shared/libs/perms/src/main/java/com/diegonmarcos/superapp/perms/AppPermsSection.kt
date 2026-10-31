package com.diegonmarcos.superapp.perms

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Colours of the host page, so the section reads as part of it (Configs ▸ General draws it with the app's theme). */
data class PermsStyle(
    val ink: Color = Color.Unspecified,
    val dim: Color = Color.Unspecified,
    val ok: Color = Color(0xFF2E7D32),
    val bad: Color = Color(0xFFC62828),
)

/**
 * #1360 THE Perms section of every app (Configs ▸ General ▸ Perms, the libs:perms page, first run): one row per grant
 * the app declares in build.json::perms (its own, then each helper app's), each with its live state and a Grant
 * (system dialog) or Open (the exact settings page) button, and Grant all on top. Re-read on every [tick] (the host
 * bumps it on resume), so a grant made in Settings shows on return. [highlight] = ids drawn first and bold.
 */
@Composable
fun AppPermsSection(tick: Int, style: PermsStyle = PermsStyle(), highlight: Set<String> = emptySet()) {
    val ctx = LocalContext.current
    var local by remember { mutableIntStateOf(0) }
    var walking by remember { mutableStateOf(false) }
    var walked by remember { mutableStateOf(emptySet<String>()) }
    var resumes by remember { mutableIntStateOf(0) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { local++ }
    val activity = remember(ctx) { ctx.findActivity() }
    DisposableEffect(activity) {
        val owner = activity as? androidx.lifecycle.LifecycleOwner
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumes++ }
        owner?.lifecycle?.addObserver(obs)
        onDispose { owner?.lifecycle?.removeObserver(obs) }
    }
    val own = remember(tick, local, resumes) { AppPerms.own(ctx, activity).sortedBy { if (it.entry.id in highlight) 0 else 1 } }
    val helpers = remember(tick, local, resumes) { AppPerms.helpers(ctx) }
    fun request(entries: List<PermEntry>) {
        val perms = entries.map { it.permission }.filter { it.isNotEmpty() }.distinct()
        if (perms.isEmpty()) return
        PermAskLog.markAsked(ctx, perms)
        PermAskLog.markAskedIds(ctx, entries.map { it.id })
        ask.launch(perms.toTypedArray())
    }
    val runtimeMissing = own.filter { it.status.missing && it.entry.kind == PermKind.RUNTIME && it.status.state != PermState.PERMANENTLY_DENIED && it.entry.autoRequest }.map { it.entry }
    val otherMissing = own.filter { it.status.missing && it.entry.kind != PermKind.ADB_ONLY }.map { it.entry } - runtimeMissing.toSet()
    val helperMissing = helpers.filter { it.status.missing || it.status.state == PermState.UNKNOWN }
    val missing = runtimeMissing.size + otherMissing.size + helperMissing.size
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("perms:section")) {
        if (own.isEmpty() && helpers.isEmpty()) Text("This app needs no permission.", color = style.dim, fontSize = 13.sp)
        // Grant all WALKS every missing grant: the runtime ones in one system dialog, then each special grant's exact settings
        // page in turn (Android has no batch for special access), advancing on each return until none is left; an entry the
        // user leaves denied is not opened twice in one walk.
        fun step(): Boolean {
            val next = when {
                runtimeMissing.any { it.id !in walked } -> { val r = runtimeMissing.filter { it.id !in walked }; walked += r.map { it.id }; request(r); return true }
                else -> otherMissing.firstOrNull { it.id !in walked }
            }
            if (next != null) { walked += next.id; PermsSettings.open(ctx, next); return true }
            val h = helperMissing.firstOrNull { "${it.helper.pkg}:${it.entry.id}" !in walked } ?: return false
            walked += "${h.helper.pkg}:${h.entry.id}"; AppPerms.openHelper(ctx, h); return true
        }
        // advance when the user comes back from the dialog or page (the host activity resumes), never on a refresh tick
        LaunchedEffect(resumes) { if (walking && !step()) walking = false }
        if (missing > 0) Button(
            onClick = { walked = emptySet(); walking = step() },
            modifier = Modifier.fillMaxWidth().height(56.dp).testTag("perms:grant-all"),
        ) { Text("Grant all ($missing missing)", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        else if (own.isNotEmpty() || helpers.isNotEmpty()) Text("Everything this app needs is granted.", color = style.ok, fontSize = 13.sp,
            modifier = Modifier.testTag("perms:all-granted"))
        for (r in own) PermRowView(
            label = r.entry.label, state = r.status, bold = r.entry.id in highlight, style = style, tag = r.entry.id,
            button = when {
                !r.status.missing || r.entry.kind == PermKind.ADB_ONLY -> null
                r.entry.kind == PermKind.RUNTIME && r.status.state != PermState.PERMANENTLY_DENIED && r.entry.autoRequest -> "Grant"
                else -> "Open"
            },
        ) { if (r.entry.kind == PermKind.RUNTIME && r.status.state != PermState.PERMANENTLY_DENIED && r.entry.autoRequest) request(listOf(r.entry)) else PermsSettings.open(ctx, r.entry) }
        if (helpers.isNotEmpty()) Text("Helper apps (they do this app's work, so they need these too)", color = style.dim, fontSize = 12.sp,
            fontWeight = FontWeight.Bold, modifier = Modifier.testTag("perms:helpers"))
        for (h in helpers) PermRowView(
            label = "${h.entry.label} — ${h.helper.label}", state = h.status, bold = h.entry.id in highlight, style = style,
            tag = "${h.helper.pkg}:${h.entry.id}", sub = h.helper.why,
            button = if (h.status.missing || h.status.state == PermState.UNKNOWN) "Open" else null,
        ) { AppPerms.openHelper(ctx, h) }
    }
}

@Composable
private fun PermRowView(label: String, state: PermStatus, bold: Boolean, style: PermsStyle, tag: String, sub: String = "", button: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().testTag("perms:row:$tag"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = style.ink, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium, fontSize = 14.sp)
            val color = when (state.state) { PermState.GRANTED -> style.ok; PermState.DENIED, PermState.PERMANENTLY_DENIED -> style.bad; else -> style.dim }
            Text(listOf(PermsActivity.stateText(state.state), state.reason).filter { it.isNotBlank() }.joinToString(" · "),
                color = color, fontSize = 12.sp, modifier = Modifier.testTag("perms:state:$tag"))
            if (sub.isNotBlank()) Text(sub, color = style.dim, fontSize = 11.sp)
        }
        if (button != null) OutlinedButton(onClick = onClick, modifier = Modifier.testTag("perms:grant:$tag")) { Text(button) }
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}
