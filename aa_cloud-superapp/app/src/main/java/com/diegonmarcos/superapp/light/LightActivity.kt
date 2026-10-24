package com.diegonmarcos.superapp.light

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.recovery.RecoveryPalette
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import com.diegonmarcos.superapp.uikit.kitComposeView
import kotlinx.coroutines.delay

/** The Light page (#1155). Big touch targets; the front light is this window turned white at the chosen brightness. */
class LightActivity : ComponentActivity() {
    private val engine by lazy { Light.engine(this) }
    private var tick by mutableStateOf(0)
    private val onChange: () -> Unit = { tick++; applyWindow() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(kitComposeView(RecoveryPalette.kit) { LightScreen(engine, tick) })
    }

    override fun onStart() { super.onStart(); engine.addListener(onChange); applyWindow() }

    override fun onStop() {
        engine.removeListener(onChange)
        engine.onLeave()   // torch survives only for a running timer or Keep on (the service holds it)
        super.onStop()
    }

    private fun applyWindow() {
        val lp = window.attributes
        if (engine.state.frontOn) {
            lp.screenBrightness = engine.settings.frontPercent / 100f
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        window.attributes = lp
    }
}

@Composable
private fun Big(label: String, tag: String, on: Boolean? = null, onClick: () -> Unit) {
    val m = Modifier.fillMaxWidth().heightIn(min = 72.dp).testTag(tag)
    if (on == true) Button(onClick = onClick, modifier = m, content = { Text(label, fontSize = 20.sp) })
    else OutlinedButton(onClick = onClick, modifier = m, content = { Text(label, fontSize = 20.sp) })
}

@Composable
private fun Pick(labels: List<String>, selected: Int, tag: String, pick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { i, l ->
            val m = Modifier.weight(1f).heightIn(min = 64.dp).testTag("$tag-$i")
            if (i == selected) Button(onClick = { pick(i) }, modifier = m, content = { Text(l) }) else OutlinedButton(onClick = { pick(i) }, modifier = m, content = { Text(l) })
        }
    }
}

@Composable
private fun LightScreen(e: LightEngine, tick: Int) {
    @Suppress("UNUSED_EXPRESSION") tick
    val s = e.settings; val st = e.state; val p = LocalKitPalette.current
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) { while (true) { now = android.os.SystemClock.elapsedRealtime(); delay(500) } }

    if (st.frontOn) {
        // Front light: a full white screen (dark during the off half of a strobe), one big way out.
        Box(Modifier.fillMaxSize().background(if (st.lit) Color.White else Color.Black).testTag("light-front-screen")) {
            Button(onClick = { e.setFront(false) }, modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp).fillMaxWidth().heightIn(min = 80.dp).testTag("light-front-off"),
                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                content = { Text("Front light off", fontSize = 22.sp, color = Color.White) })
        }
        return
    }
    Column(Modifier.fillMaxSize().background(RecoveryPalette.page).verticalScroll(rememberScrollState()).padding(start = 16.dp, top = 36.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Light", fontSize = 28.sp, color = p.textPrimary)
        st.error?.let { Text(it, color = Color(0xFFFF8A80), modifier = Modifier.testTag("light-error")) }

        Big(if (st.rearOn) "Rear light: ON" else "Rear light: off", "light-rear", st.rearOn) { e.setRear(!st.rearOn) }
        Big("Front light: off (tap for full white screen)", "light-front", false) { e.setFront(true) }

        Text("Mode", color = p.textPrimary)
        Pick(listOf("Spotlight", "Flashing"), s.mode.ordinal, "light-mode") { i -> e.update { it.copy(mode = LightMode.values()[i]) } }
        if (s.mode == LightMode.FLASHING) {
            Text("Flash rate: ${"%.1f".format(s.strobeHz)} Hz (maximum ${LightLimits.MAX_STROBE_HZ.toInt()} Hz)", color = p.textPrimary)
            Slider(s.strobeHz, { v -> e.update { it.copy(strobeHz = v) } }, Modifier.testTag("light-hz"),
                valueRange = LightLimits.MIN_STROBE_HZ..LightLimits.MAX_STROBE_HZ)
            Text("Warning: flashing light can trigger seizures in people with photosensitive epilepsy. The rate is capped at 3 flashes per second.",
                color = Color(0xFFFFCC80), fontSize = 14.sp, modifier = Modifier.testTag("light-warning"))
        }

        Text("Rear intensity: ${s.rearPercent}%", color = p.textPrimary)
        if (e.rearMaxLevel > 1) Slider(s.rearPercent.toFloat(), { v -> e.update { it.copy(rearPercent = v.toInt()) } },
            Modifier.testTag("light-rear-level"), valueRange = 1f..100f)
        else Text("This phone's flashlight supports on/off only.", color = Color(0xFFB0BEC5), modifier = Modifier.testTag("light-rear-onoff"))
        Text("Front intensity: ${s.frontPercent}%", color = p.textPrimary)
        Slider(s.frontPercent.toFloat(), { v -> e.update { it.copy(frontPercent = v.toInt()) } }, Modifier.testTag("light-front-level"), valueRange = 1f..100f)

        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Timer (auto-off)", Modifier.weight(1f), color = p.textPrimary)
            Switch(s.timerOn, { v -> e.update { it.copy(timerOn = v) } }, Modifier.testTag("light-timer"))
        }
        if (s.timerOn) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { e.update { it.copy(timerMinutes = it.timerMinutes - 1) } }, modifier = Modifier.heightIn(min = 64.dp).testTag("light-timer-minus"), content = { Text("-", fontSize = 24.sp) })
                Text("${s.timerMinutes} min", Modifier.weight(1f), color = p.textPrimary, fontSize = 20.sp)
                OutlinedButton(onClick = { e.update { it.copy(timerMinutes = it.timerMinutes + 1) } }, modifier = Modifier.heightIn(min = 64.dp).testTag("light-timer-plus"), content = { Text("+", fontSize = 24.sp) })
            }
            if (st.timerRunning) {
                val left = ((st.timerEndAt - now) / 1000).coerceAtLeast(0)
                Text("Off in %d:%02d".format(left / 60, left % 60), color = p.textPrimary)
            }
        }

        Text("Quick tap on the Light tile turns on", color = p.textPrimary)
        Pick(listOf("Rear", "Front", "Both"), s.defaultTarget.ordinal, "light-default") { i -> e.update { it.copy(defaultTarget = LightTarget.values()[i]) } }

        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Keep the rear light on when I leave (notification with Off)", Modifier.weight(1f), color = p.textPrimary)
            Switch(s.keepOn, { v -> e.update { it.copy(keepOn = v) } }, Modifier.testTag("light-keep"))
        }
    }
}
