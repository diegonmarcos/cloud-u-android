package com.diegonmarcos.cloudnav.tracker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitConfirmDialog
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.KitSectionHeader
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/** The fleet palette for the Tracker and Steps pages (the same ink as libs:maps' pages). */
object TrackerInk {
    fun palette(): KitPalette = KitPalette.fromArgb(
        surface = 0xFF0B0F17.toInt(), surfaceSelected = 0xFF141A25.toInt(), textPrimary = 0xFFECEFF4.toInt(),
        textSecondary = 0xFF9AA3B2.toInt(), accent = 0xFF4ADE80.toInt(), hairline = 0xFF2A3342.toInt(),
        tileInk = 0xFF0B0F17.toInt(), ok = 0xFF16A34A.toInt(), warn = 0xFFFBBF24.toInt(), bad = 0xFFEF4444.toInt(),
    )
}

/** The body of a settings-like page: the palette's surface, 16 dp gutter, children stacked, scrolling. */
@Composable
fun TrackerPage(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalKitPalette.current
    Column(modifier.fillMaxSize().background(p.surface).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

enum class Perm(val title: String, val why: String) {
    LOCATION("Location", "Cloud Nav needs your location to take the engine's fixes. Fine location is used for GPS fixes; coarse is enough for cell-tower fixes."),
    BACKGROUND("Location in the background", "So fixes keep working while the app is closed. Android asks for this separately, after the location permission — choose \"Allow all the time\"."),
    ACTIVITY("Physical activity", "Lets the engine read the phone's step counter, which drives the every-N-steps triggers and the estimated positions. Steps never leave this device."),
}

data class EngineUi(
    val config: EngineConfig = EngineConfig(),
    val granted: Set<Perm> = emptySet(),
    val hasStepSensor: Boolean = true,
    val stepLengthM: Double = DeadReckoning.DEFAULT_STEP_M,
    val lastFix: String = "No fix yet",
)

class EngineActions(
    val onConfig: (EngineConfig) -> Unit = {},
    val onRequest: (Perm) -> Unit = {},
    val onLegacy: () -> Unit = {},
)

object EngineTags {
    const val ENABLED = "engine:enabled"
    const val SRC_GPS = "engine:src:gps"
    const val SRC_CELL = "engine:src:cell"
    const val ACC_TIME = "engine:acc:time"
    const val ACC_STEPS = "engine:acc:steps"
    const val CELL_TIME = "engine:cell:time"
    const val MOTION = "engine:motion"
    const val HINT = "engine:hint"
    const val EXPLAIN = "engine:explain"
    const val LEGACY = "engine:legacy"
    fun plus(tag: String) = "$tag:plus"
    fun minus(tag: String) = "$tag:minus"
    fun value(tag: String) = "$tag:value"
    fun grant(p: Perm) = "engine:grant:${p.name.lowercase()}"
    const val RATIONALE_OK = "engine:rationale:ok"
}

@Composable
fun EngineSettingsScreen(ui: EngineUi, a: EngineActions) {
    val p = LocalKitPalette.current
    val c = ui.config
    var hint by remember { mutableStateOf<String?>(null) }
    var rationale by remember { mutableStateOf<Perm?>(null) }

    rationale?.let { perm ->
        KitConfirmDialog(perm.title, perm.why, "Continue", "Not now",
            onConfirm = { rationale = null; a.onRequest(perm) }, onDismiss = { rationale = null })
    }

    TrackerPage {
        KitSectionHeader("Tracking engine", "Where you were and how often it is checked, with the least battery. Fixes feed Timeline and Steps.")
        KitCard {
            ToggleRow(EngineTags.ENABLED, "Run the engine", "A background job about every 15 min. No foreground notification.", c.enabled, true) {
                a.onConfig(c.copy(enabled = it))
            }
            Text("Last fix: ${ui.lastFix}", color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
            if (!ui.hasStepSensor) Text("This phone has no step counter: step triggers and estimates are unavailable.", color = p.warn, style = MaterialTheme.typography.bodySmall)
        }

        KitCard {
            Text("Permissions", color = p.textPrimary, style = MaterialTheme.typography.titleMedium)
            for (perm in Perm.values()) {
                val ok = perm in ui.granted
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(perm.title, color = p.textPrimary, style = MaterialTheme.typography.bodyLarge)
                        Text(if (ok) "Granted" else "Not granted", color = if (ok) p.ok else p.warn, style = MaterialTheme.typography.bodySmall)
                    }
                    if (!ok) OutlinedButton(onClick = { rationale = perm }, modifier = Modifier.testTag(EngineTags.grant(perm))) { Text("Grant") }
                }
            }
        }

        KitSectionHeader("A  Source", "", eyebrow = true)
        KitCard {
            ToggleRow(EngineTags.SRC_GPS, "GPS", "Satellites: accurate, uses more battery", c.useGps, true) {
                c.withSources(it, c.useCell)?.let(a.onConfig)?.also { hint = null } ?: run { hint = "Keep at least one source on." }
            }
            ToggleRow(EngineTags.SRC_CELL, "Cell towers", "Network location: coarse, very cheap", c.useCell, true) {
                c.withSources(c.useGps, it)?.let(a.onConfig)?.also { hint = null } ?: run { hint = "Keep at least one source on." }
            }
            hint?.let { Text(it, color = p.warn, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(EngineTags.HINT)) }
            Text(if (SourceRules.accurateProvider(c) == Provider.GPS) "Accurate fixes use GPS." else "GPS is off: accurate fixes use cell towers.",
                color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
        }

        KitSectionHeader("B  Accurate fix (precise location)", "", eyebrow = true)
        KitCard {
            TriggerRow(EngineTags.ACC_TIME, "Every", c.accurateEveryHours, "h", c.accurateByTime, true,
                { a.onConfig(c.copy(accurateByTime = it)) },
                { a.onConfig(c.copy(accurateEveryHours = EngineConfig.step(EngineConfig.HOURS_ACCURATE, c.accurateEveryHours, it))) })
            TriggerRow(EngineTags.ACC_STEPS, "Every", c.accurateEverySteps, "steps", c.accurateBySteps, ui.hasStepSensor,
                { a.onConfig(c.copy(accurateBySteps = it)) },
                { a.onConfig(c.copy(accurateEverySteps = EngineConfig.step(EngineConfig.STEPS_ACCURATE, c.accurateEverySteps, it))) })
            Text("Either one triggers it; with both on, whichever comes first.", color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
        }

        KitSectionHeader("C  Low-power updates (between accurate fixes)", "", eyebrow = true)
        KitCard {
            TriggerRow(EngineTags.CELL_TIME, "Cell towers every", c.cellEveryHours, "h", c.cellByTime, c.useCell,
                { a.onConfig(c.copy(cellByTime = it)) },
                { a.onConfig(c.copy(cellEveryHours = EngineConfig.step(EngineConfig.HOURS_CELL, c.cellEveryHours, it))) })
            TriggerRow(EngineTags.MOTION, "Estimate position every", c.motionEverySteps, "steps", c.motionBySteps, ui.hasStepSensor,
                { a.onConfig(c.copy(motionBySteps = it)) },
                { a.onConfig(c.copy(motionEverySteps = EngineConfig.step(EngineConfig.STEPS_MOTION, c.motionEverySteps, it))) })
            Text("Estimates use steps × step length (now ${"%.2f".format(ui.stepLengthM)} m, learned from accurate fixes) and the compass heading. They are marked predicted, with an uncertainty radius.",
                color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
        }

        OutlinedButton(onClick = a.onLegacy, modifier = Modifier.testTag(EngineTags.LEGACY)) { Text("Foreground tracker, calibration and export") }

        KitSectionHeader("How this engine works", "")
        Column(Modifier.testTag(EngineTags.EXPLAIN), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for ((h, body) in Explanation.sections(c)) {
                Text(h, color = p.accent, style = MaterialTheme.typography.titleSmall)
                Text(body, color = p.textPrimary, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ToggleRow(tag: String, title: String, sub: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalKitPalette.current
    Row(Modifier.fillMaxWidth().testTag(tag).toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = p.textPrimary, style = MaterialTheme.typography.bodyLarge)
            Text(sub, color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** A checkbox + "label [-] value unit [+]" row. [onStep] gets +1 / -1. */
@Composable
private fun TriggerRow(tag: String, label: String, value: Int, unit: String, checked: Boolean, enabled: Boolean,
                       onChecked: (Boolean) -> Unit, onStep: (Int) -> Unit) {
    val p = LocalKitPalette.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).testTag(tag).toggleable(value = checked && enabled, enabled = enabled, role = Role.Checkbox, onValueChange = onChecked).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked && enabled, onCheckedChange = null, enabled = enabled)
            Text("$label ", color = if (enabled) p.textPrimary else p.textSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 4.dp))
        }
        val on = checked && enabled
        Text("−", color = if (on) p.accent else p.textSecondary, style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.testTag(EngineTags.minus(tag)).then(if (on) Modifier.clickable { onStep(-1) } else Modifier).padding(horizontal = 12.dp, vertical = 4.dp))
        Text("$value $unit", color = if (on) p.textPrimary else p.textSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(EngineTags.value(tag)))
        Text("+", color = if (on) p.accent else p.textSecondary, style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.testTag(EngineTags.plus(tag)).then(if (on) Modifier.clickable { onStep(1) } else Modifier).padding(horizontal = 12.dp, vertical = 4.dp))
    }
}
