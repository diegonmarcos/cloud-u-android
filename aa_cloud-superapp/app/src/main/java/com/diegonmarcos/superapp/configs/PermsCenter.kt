package com.diegonmarcos.superapp.configs

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.core.perms.AdbLine
import com.diegonmarcos.superapp.core.perms.AdbScript
import com.diegonmarcos.superapp.core.perms.PermEntry
import com.diegonmarcos.superapp.core.perms.PermKind
import com.diegonmarcos.superapp.core.perms.PermSection
import com.diegonmarcos.superapp.core.perms.PermState
import com.diegonmarcos.superapp.core.perms.PermStatus
import com.diegonmarcos.superapp.core.perms.PermStatusReader
import com.diegonmarcos.superapp.core.perms.Perms
import com.diegonmarcos.superapp.core.perms.PermsLink
import com.diegonmarcos.superapp.core.perms.PermsSettings
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/** One row's resolved state for the catalogue section (#1274). */
data class PermRowState(val entry: PermEntry, val status: PermStatus)

/** Result of one ADB line run through the on-device channel. */
data class AdbRunResult(val line: AdbLine, val ok: Boolean, val output: String)

/** Pure: whether a shell answer means the line worked. pm grant / appops / cmd answer nothing on success. */
object AdbResult {
    private val BAD = Regex("(?i)exception|error|not allowed|unknown|failed|denied|bad argument|usage:|not found|invalid")
    fun ok(output: String?): Boolean = output != null && !BAD.containsMatchIn(output)
}

/** Reads every catalogue entry's state; the page calls it on open and on every resume. */
fun readPermRows(ctx: Context, activity: Activity?): List<PermRowState> {
    val vars = Perms.vars(ctx)
    return Perms.catalogue(ctx).entries.map { PermRowState(it, PermStatusReader.status(ctx, it, activity, vars)) }
}

/** The adb lines for every applicable entry (granted ones too: the block is the full grant, safe to re-run). */
fun adbLinesFor(ctx: Context, rows: List<PermRowState>): List<AdbLine> =
    AdbScript.lines(rows.filter { it.status.state != PermState.NOT_APPLICABLE }.map { it.entry }, Perms.vars(ctx))

/**
 * The catalogue-driven head of the Permissions page: progress, the "Requested by" highlight, the missing-only filter,
 * Grant all via ADB (copy / run on the connected channel with per-line results) and one section per [PermSection].
 */
@Composable
internal fun PermsCenterSection(
    rows: List<PermRowState>,
    highlight: PermsLink.Request?,
    channelName: String?,
    adbResults: List<AdbRunResult>,
    adbRunning: Boolean,
    onClearHighlight: () -> Unit,
    onAskRuntime: (List<PermEntry>) -> Unit,
    onAutoAgain: () -> Unit,
    onRunAdb: () -> Unit,
    onOpenAdbShell: () -> Unit,
    ctx: Context,
) {
    val p = LocalKitPalette.current
    var missingOnly by rememberSaveable { mutableStateOf(highlight != null) }
    val applicable = rows.filter { it.status.state != PermState.NOT_APPLICABLE }
    val granted = applicable.count { it.status.state == PermState.GRANTED }
    val total = applicable.size.coerceAtLeast(1)

    Column(Modifier.fillMaxWidth().testTag("perms:center")) {
        Text("PERMISSIONS — $granted of ${applicable.size} granted", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = p.accent,
            modifier = Modifier.padding(top = 4.dp).testTag("perms:progress"))
        Box(Modifier.fillMaxWidth().height(5.dp).padding(top = 1.dp).background(Color(0xFF2A2A33))) {
            Box(Modifier.fillMaxWidth(granted.toFloat() / total).height(5.dp).background(Color(0xFF22C55E)))
        }
        if (highlight != null) {
            val n = rows.count { it.entry.id in highlight.ids }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp).background(p.accent.copy(alpha = 0.18f)).padding(6.dp).testTag("perms:highlight"),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Requested by ${highlight.feature}: $n permission(s) highlighted below", fontSize = 11.sp, color = p.textPrimary, modifier = Modifier.weight(1f))
                Btn("Show all", tag = "perms:highlight-clear") { onClearHighlight() }
            }
        }
        val dialogable = rows.filter { it.entry.kind == PermKind.RUNTIME && it.entry.autoRequest && it.status.state == PermState.DENIED }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Btn(if (missingOnly) "✓ Missing only" else "Missing only", tag = "perms:filter-missing", fill = true) { missingOnly = !missingOnly }
            Btn("Ask all again (${dialogable.size})", tag = "perms:ask-all", fill = true) { onAutoAgain() }
        }
        // ── Grant all via ADB ──
        val lines = remember(rows) { adbLinesFor(ctx, rows) }
        Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
            Btn("Copy ADB block (${lines.size})", tag = "perms:adb-copy", fill = true) {
                copy(ctx, AdbScript.block(lines, ctx.packageName))
                Toast.makeText(ctx, "Copied ${lines.size} adb commands", Toast.LENGTH_SHORT).show()
            }
            if (channelName != null) Btn(if (adbRunning) "Running…" else "Run via $channelName", tag = "perms:adb-run", fill = true) { if (!adbRunning) onRunAdb() }
            else Btn("ADB not connected ›", tag = "perms:adb-connect", fill = true) { onOpenAdbShell() }
        }
        if (adbResults.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(top = 2.dp).testTag("perms:adb-results")) {
            Text("ADB: ${adbResults.count { it.ok }} ok · ${adbResults.count { !it.ok }} failed", fontSize = 10.5.sp, color = p.textPrimary.copy(alpha = 0.7f))
            for (r in adbResults) Text((if (r.ok) "✓ " else "✗ ") + r.line.command.take(90) + (if (r.output.isNotBlank()) "  → ${r.output.trim().take(80)}" else ""),
                fontSize = 9.5.sp, fontFamily = FontFamily.Monospace, color = if (r.ok) Color(0xFF22C55E) else Color(0xFFEF4444))
        }

        for (section in PermSection.values()) {
            val inSection = rows.filter { it.entry.section == section }
            if (inSection.isEmpty()) continue
            val shown = inSection.filter { r ->
                (highlight == null || r.entry.id in highlight.ids || !missingOnly) &&
                    (!missingOnly || r.status.missing || r.status.state == PermState.UNKNOWN)
            }
            val app = inSection.filter { it.status.state != PermState.NOT_APPLICABLE }
            Text("${section.title.uppercase()} — ${app.count { it.status.state == PermState.GRANTED }}/${app.size}",
                fontSize = 11.sp, fontWeight = FontWeight.Bold, color = p.accent,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 1.dp).testTag("perms:section:${section.name.lowercase()}"))
            if (shown.isEmpty()) Text("nothing missing", fontSize = 10.sp, color = p.textPrimary.copy(alpha = 0.5f))
            for (r in shown) CatalogueRow(r, highlight?.ids?.contains(r.entry.id) == true, ctx, onAskRuntime)
        }
    }
}

@Composable
private fun CatalogueRow(r: PermRowState, highlighted: Boolean, ctx: Context, onAskRuntime: (List<PermEntry>) -> Unit) {
    val p = LocalKitPalette.current
    val e = r.entry
    val (icon, col) = when (r.status.state) {
        PermState.GRANTED -> "✓" to Color(0xFF22C55E)
        PermState.DENIED -> "◯" to Color(0xFFF59E0B)
        PermState.PERMANENTLY_DENIED -> "✗" to Color(0xFFEF4444)
        PermState.NOT_APPLICABLE -> "–" to Color(0xFF6B7280)
        PermState.UNKNOWN -> "?" to Color(0xFF9CA3AF)
    }
    val stateWord = when (r.status.state) {
        PermState.GRANTED -> "granted"; PermState.DENIED -> "denied"; PermState.PERMANENTLY_DENIED -> "permanently denied"
        PermState.NOT_APPLICABLE -> "not applicable"; PermState.UNKNOWN -> "check manually"
    }
    var mod = Modifier.fillMaxWidth().padding(vertical = 1.dp)
    if (highlighted) mod = mod.border(1.dp, p.accent).background(p.accent.copy(alpha = 0.12f))
    Column(mod.padding(horizontal = 3.dp, vertical = 2.dp).testTag("perms:row:${e.id}")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("$icon ${e.label}  ·  $stateWord" + (if (r.status.reason.isNotBlank()) " (${r.status.reason})" else ""),
                fontSize = 11.5.sp, color = col, modifier = Modifier.weight(1f))
            when {
                r.status.state == PermState.GRANTED || r.status.state == PermState.NOT_APPLICABLE -> {}
                e.kind == PermKind.RUNTIME && e.autoRequest && r.status.state == PermState.DENIED ->
                    Btn("Request", tag = "perms:request:${e.id}") { onAskRuntime(listOf(e)) }
                e.kind == PermKind.ADB_ONLY && e.settings == null ->
                    Btn("Copy adb", tag = "perms:copy:${e.id}") {
                        val l = AdbScript.lines(listOf(e), Perms.vars(ctx))
                        copy(ctx, l.joinToString("\n") { it.forComputer() })
                        Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
                    }
                else -> Btn("Open ›", tag = "perms:open:${e.id}") {
                    if (!PermsSettings.open(ctx, e)) Toast.makeText(ctx, "No settings page for ${e.label} on this device", Toast.LENGTH_LONG).show()
                    else if (e.lookFor.isNotBlank()) Toast.makeText(ctx, "Look for: ${e.lookFor}", Toast.LENGTH_LONG).show()
                }
            }
        }
        if (r.status.missing || r.status.state == PermState.UNKNOWN) {
            val hint = listOf(e.lookFor.takeIf { it.isNotBlank() }?.let { "Look for: $it" }, e.instruction.takeIf { it.isNotBlank() })
                .filterNotNull().joinToString(" · ")
            val permanently = r.status.state == PermState.PERMANENTLY_DENIED
            val text = if (permanently && hint.isBlank()) "Open › App info > Permissions > ${e.label} > Allow" else hint
            if (text.isNotBlank()) Text(text, fontSize = 10.sp, color = p.textPrimary.copy(alpha = 0.65f))
        }
    }
}

@Composable
private fun RowScope.Btn(label: String, tag: String, fill: Boolean = false, onClick: () -> Unit) {
    Text(label, fontSize = 11.sp, maxLines = 2, textAlign = TextAlign.Center, color = Color.White,
        modifier = (if (fill) Modifier.weight(1f) else Modifier).padding(horizontal = 1.5.dp).background(Color(0xFF7C3AED))
            .clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 3.dp).testTag(tag))
}

private fun copy(ctx: Context, v: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("perms", v))
}
