package com.diegonmarcos.cloudnav

import android.app.Dialog
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.fragment.app.DialogFragment
import com.diegonmarcos.cloudnav.tracker.TrackerInk
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitSectionHeader
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * AccuBattery-style energy page — opened from Configs/About → Battery &
 * Usage. Renders, in one scroll:
 *   1. Live discharge speed (whole-device mA / %·h from
 *      BatterySessionStats — the same snapshot the status strip uses).
 *   2. "Draining the device" — marginal mA per system state from the
 *      Tier-1 watchdog ([EnergyWatchdog.attribution]).
 *   3. "By foreground app" — per-app average draw + energy share while
 *      that app was foreground (correlation, the no-privilege proxy for
 *      AccuBattery's per-app list).
 *   4. "Inside Cloud Nav" — our own subsystem ledger
 *      ([EnergyLedger]) so we can see + fix what WE cost.
 *
 * Full-screen DialogFragment so it's decoupled from the host's fragment
 * container — open with `EnergyUsageDialog().show(fm, TAG)`. The body is
 * Compose ([EnergyUsageScreen]) over the fleet palette.
 */
class EnergyUsageDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.setBackgroundDrawable(ColorDrawable(0xFF0A0A0F.toInt()))
        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { CloudKitTheme(TrackerInk.palette()) { EnergyUsageScreen(onClose = { dismiss() }) } }
        }

    companion object { const val TAG = "energy_usage_dialog" }
}

object EnergyTags {
    const val CLOSE = "energy:close"
    const val REFRESH = "energy:refresh"
    const val RESET = "energy:reset"
    const val SPEED = "energy:speed"
    const val DRAIN = "energy:drain"
    const val APPS = "energy:apps"
    const val LEDGER = "energy:ledger"
}

/** One "label ... value" line of a card. */
data class EnergyLine(val key: String, val value: String)

/** One app of the per-app estimate. */
data class EnergyAppLine(val pkg: String, val label: String, val value: String)

/** What the page shows, read once per refresh; [read] is the only part that touches the system. */
class EnergyUsageModel(
    val speedBig: String?, val speedSub: String, val speedLines: List<EnergyLine>,
    val sampleCount: Int, val drainLines: List<EnergyLine>?,
    val usageAccess: Boolean, val apps: List<EnergyAppLine>,
    val ledgerSince: String, val ledgerLines: List<EnergyLine>,
) {
    companion object {
        fun read(ctx: Context): EnergyUsageModel {
            val bs = runCatching { BatterySessionStats.read(ctx) }.getOrNull()
            var big: String? = null; var sub = ""; var speed = emptyList<EnergyLine>()
            if (bs != null) {
                val ma = if (bs.currentUa != 0) (kotlin.math.abs(bs.currentUa) / 1000) else 0
                big = if (ma > 0) "$ma mA" else "—"
                sub = BatterySessionStats.fmtRateUnified(bs)
                speed = listOf(
                    EnergyLine("Live draw", BatterySessionStats.fmtPowerRow(bs)),
                    EnergyLine("Battery temp", BatterySessionStats.fmtBatteryTemp(bs)),
                    EnergyLine(if (bs.isCharging) "Time to full" else "Est. battery last", BatterySessionStats.fmtEtaDuration(bs)),
                )
            }
            val attr = runCatching { EnergyWatchdog.attribution(ctx) }.getOrDefault(emptyMap())
            val samples = (attr["samples"] as? Int) ?: 0

            val access = EnergyWatchdog.hasUsageAccess(ctx)
            val apps = if (!access) emptyList()
            else runCatching { EnergyWatchdog.perAppEstimate(ctx) }.getOrDefault(emptyList()).take(15).map { e ->
                EnergyAppLine(e.pkg, e.label, "%.1f mAh  ·  %s".format(e.mAh, fmtDur(e.fgMs)))
            }

            val (since, ledger) = EnergyLedger.snapshot()
            val lines = ledger.entries.sortedByDescending { it.value.cpuNanos }.map { (tag, st) ->
                EnergyLine(tag, buildString {
                    append("${st.cpuNanos / 1_000_000L} ms")
                    if (st.wakeups > 0) append("  ·  ${st.wakeups} wake")
                    if (st.bytes > 0) append("  ·  ${sizeStr(st.bytes)}")
                })
            }
            return EnergyUsageModel(big, sub, speed, samples, drainLines(attr), access, apps,
                if (since > 0) fmtAgo(since) else "start", lines)
        }

        /** The "Draining the device" lines from the watchdog's attribution map; null while it has no samples. */
        @Suppress("UNCHECKED_CAST")
        fun drainLines(attr: Map<String, Any>): List<EnergyLine>? {
            val states = attr["states"] as? Map<String, Map<String, Any>>
            val samples = (attr["samples"] as? Int) ?: 0
            if (states == null || samples == 0) return null
            val out = ArrayList<EnergyLine>()
            out.add(EnergyLine("Idle baseline", "${(attr["baseline_idle_ma"] as? Int) ?: 0} mA"))
            states.entries.sortedByDescending { (it.value["marginal_ma"] as? Int) ?: 0 }.forEach { (k, v) ->
                val m = (v["marginal_ma"] as? Int) ?: 0
                val n = (v["samples"] as? Int) ?: 0
                if (n > 0) out.add(EnergyLine(k.replace('_', ' '), (if (m >= 0) "+$m mA" else "$m mA") + "   ($n)"))
            }
            return out
        }

        fun sizeStr(b: Long): String = when {
            b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
            b >= 1L shl 10 -> "%.1f KB".format(b / (1L shl 10).toDouble())
            else -> "$b B"
        }

        fun fmtDur(ms: Long): String {
            val m = ms / 60_000L
            return when {
                m >= 60 -> "${m / 60}h ${m % 60}m"
                m >= 1 -> "${m}m"
                else -> "${ms / 1000L}s"
            }
        }

        fun fmtAgo(sinceMs: Long, now: Long = System.currentTimeMillis()): String {
            val mins = (now - sinceMs) / 60_000L
            return when {
                mins < 1 -> "just now"
                mins < 60 -> "${mins}m ago"
                else -> "${mins / 60}h ${mins % 60}m ago"
            }
        }
    }
}

@Composable
fun EnergyUsageScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val p = LocalKitPalette.current
    var nonce by remember { mutableIntStateOf(0) }
    val m = remember(nonce) { EnergyUsageModel.read(ctx) }
    Column(Modifier.fillMaxSize().background(p.surface).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Battery Usage", Modifier.weight(1f), color = p.textPrimary, style = MaterialTheme.typography.headlineSmall)
            Text("✕", Modifier.testTag(EnergyTags.CLOSE).clickable(onClick = onClose).padding(start = 12.dp, top = 4.dp, end = 4.dp, bottom = 4.dp),
                color = p.textSecondary, style = MaterialTheme.typography.titleLarge)
        }

        KitSectionHeader("Discharging speed", "", eyebrow = true)
        KitCard(Modifier.testTag(EnergyTags.SPEED)) {
            if (m.speedBig == null) EnergyRow(EnergyLine("—", "no data")) else {
                Text(m.speedBig, color = p.textPrimary, style = MaterialTheme.typography.headlineMedium)
                Text(m.speedSub, color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
                m.speedLines.forEach { EnergyRow(it) }
            }
        }

        KitSectionHeader("Draining the device  ·  ${m.sampleCount} samples", "", eyebrow = true)
        KitCard(Modifier.testTag(EnergyTags.DRAIN)) {
            val d = m.drainLines
            if (d == null) EnergyNote("Collecting samples… open the app a few times while on battery, then reopen this page.")
            else d.forEach { EnergyRow(it) }
        }

        KitSectionHeader("By app  ·  estimate (foreground × draw)", "", eyebrow = true)
        KitCard(Modifier.testTag(EnergyTags.APPS)) {
            when {
                !m.usageAccess -> EnergyNote("Needs Usage Access. Grant it in Configs/About → Permissions → Set Usage Access.")
                m.apps.isEmpty() -> EnergyNote("No foreground time in the current discharge session yet.")
                else -> {
                    EnergyNote("mAh ≈ app foreground-time × avg screen-on draw (this discharge session)")
                    m.apps.forEach { AppRow(it) }
                }
            }
        }

        KitSectionHeader("Inside Cloud Nav", "", eyebrow = true)
        KitCard(Modifier.testTag(EnergyTags.LEDGER)) {
            if (m.ledgerLines.isEmpty()) EnergyNote("No subsystem activity recorded yet this session.") else {
                EnergyNote("CPU-time / wakeups / bytes per subsystem since ${m.ledgerSince}")
                m.ledgerLines.forEach { EnergyRow(it) }
            }
        }

        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { nonce++ }, modifier = Modifier.testTag(EnergyTags.REFRESH)) { Text("Refresh") }
            OutlinedButton(onClick = {
                EnergyLedger.reset(); runCatching { EnergyStore(ctx).clear() }
                nonce++
            }, modifier = Modifier.testTag(EnergyTags.RESET)) { Text("Reset window") }
        }
    }
}

@Composable
private fun EnergyRow(l: EnergyLine) {
    val p = LocalKitPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(l.key, Modifier.weight(1f), color = p.textSecondary, style = MaterialTheme.typography.bodyMedium)
        Text(l.value, color = p.textPrimary, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun EnergyNote(t: String) {
    Text(t, color = LocalKitPalette.current.textSecondary, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun AppRow(a: EnergyAppLine) {
    val p = LocalKitPalette.current
    val ctx = LocalContext.current
    val icon = remember(a.pkg) { runCatching { ctx.packageManager.getApplicationIcon(a.pkg).toBitmap(56, 56).asImageBitmap() }.getOrNull() }
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) Image(icon, null, Modifier.size(28.dp)) else Spacer(Modifier.size(28.dp))
        Text(a.label, Modifier.weight(1f).padding(start = 10.dp), color = p.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium)
        Text(a.value, color = p.accent, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}
