package com.diegonmarcos.superapp.light

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay

/**
 * The Light page (#1155) as one shared composable: the SuperApp's LightActivity and Cloud Calc's Toolkit > Light page both draw THIS,
 * over the one [Light] engine, so there is a single copy of the rules (LightModel.kt) and of the screen. The host only chooses the
 * ink ([textColor]) and the page colour ([background]). The front light is the host window turned white at the chosen brightness;
 * it, and a torch that is not kept on, end when the panel leaves composition or the host activity stops.
 */
@Composable
fun LightPanel(engine: LightEngine, textColor: Color, background: Color, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(engine) {
        val activity = ctx.findActivity()
        val onChange: () -> Unit = { tick++; activity?.applyLightWindow(engine) }
        val stopObserver = LifecycleEventObserver { _, ev -> if (ev == Lifecycle.Event.ON_STOP) engine.onLeave() }
        (activity as? ComponentActivity)?.lifecycle?.addObserver(stopObserver)
        engine.addListener(onChange)
        activity?.applyLightWindow(engine)
        onDispose {
            engine.removeListener(onChange)
            (activity as? ComponentActivity)?.lifecycle?.removeObserver(stopObserver)
            engine.onLeave()   // the torch survives only for a running timer or Keep on (the service holds it)
            activity?.applyLightWindow(engine)
        }
    }
    LightScreen(engine, tick, textColor, background, modifier)
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

private fun Activity.applyLightWindow(engine: LightEngine) {
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
private fun LightScreen(e: LightEngine, tick: Int, ink: Color, page: Color, modifier: Modifier) {
    @Suppress("UNUSED_EXPRESSION") tick
    val s = e.settings; val st = e.state
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) { while (true) { now = android.os.SystemClock.elapsedRealtime(); delay(500) } }

    if (st.frontOn) {
        // Front light: a full white screen (dark during the off half of a strobe), one big way out.
        Box(modifier.fillMaxSize().background(if (st.lit) Color.White else Color.Black).testTag("light-front-screen")) {
            Button(onClick = { e.setFront(false) }, modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp).fillMaxWidth().heightIn(min = 80.dp).testTag("light-front-off"),
                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                content = { Text("Front light off", fontSize = 22.sp, color = Color.White) })
        }
        return
    }
    Column(modifier.fillMaxSize().background(page).verticalScroll(rememberScrollState()).padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Light", fontSize = 28.sp, color = ink)
        st.error?.let { Text(it, color = Color(0xFFFF8A80), modifier = Modifier.testTag("light-error")) }

        Big(if (st.rearOn) "Rear light: ON" else "Rear light: off", "light-rear", st.rearOn) { e.setRear(!st.rearOn) }
        Big("Front light: off (tap for full white screen)", "light-front", false) { e.setFront(true) }

        Text("Mode", color = ink)
        Pick(listOf("Spotlight", "Flashing"), s.mode.ordinal, "light-mode") { i -> e.update { it.copy(mode = LightMode.values()[i]) } }
        if (s.mode == LightMode.FLASHING) {
            Text("Flash rate: ${"%.1f".format(s.strobeHz)} Hz (maximum ${LightLimits.MAX_STROBE_HZ.toInt()} Hz)", color = ink)
            Slider(s.strobeHz, { v -> e.update { it.copy(strobeHz = v) } }, Modifier.testTag("light-hz"),
                valueRange = LightLimits.MIN_STROBE_HZ..LightLimits.MAX_STROBE_HZ)
            Text("Warning: flashing light can trigger seizures in people with photosensitive epilepsy. The rate is capped at 3 flashes per second.",
                color = Color(0xFFFFCC80), fontSize = 14.sp, modifier = Modifier.testTag("light-warning"))
        }

        Text("Rear intensity: ${s.rearPercent}%", color = ink)
        if (e.rearMaxLevel > 1) Slider(s.rearPercent.toFloat(), { v -> e.update { it.copy(rearPercent = v.toInt()) } },
            Modifier.testTag("light-rear-level"), valueRange = 1f..100f)
        else Text("This phone's flashlight supports on/off only.", color = Color(0xFFB0BEC5), modifier = Modifier.testTag("light-rear-onoff"))
        Text("Front intensity: ${s.frontPercent}%", color = ink)
        Slider(s.frontPercent.toFloat(), { v -> e.update { it.copy(frontPercent = v.toInt()) } }, Modifier.testTag("light-front-level"), valueRange = 1f..100f)

        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Timer (auto-off)", Modifier.weight(1f), color = ink)
            Switch(s.timerOn, { v -> e.update { it.copy(timerOn = v) } }, Modifier.testTag("light-timer"))
        }
        if (s.timerOn) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { e.update { it.copy(timerMinutes = it.timerMinutes - 1) } }, modifier = Modifier.heightIn(min = 64.dp).testTag("light-timer-minus"), content = { Text("-", fontSize = 24.sp) })
                Text("${s.timerMinutes} min", Modifier.weight(1f), color = ink, fontSize = 20.sp)
                OutlinedButton(onClick = { e.update { it.copy(timerMinutes = it.timerMinutes + 1) } }, modifier = Modifier.heightIn(min = 64.dp).testTag("light-timer-plus"), content = { Text("+", fontSize = 24.sp) })
            }
            if (st.timerRunning) {
                val left = ((st.timerEndAt - now) / 1000).coerceAtLeast(0)
                Text("Off in %d:%02d".format(left / 60, left % 60), color = ink)
            }
        }

        Text("Quick tap on the Light tile turns on", color = ink)
        Pick(listOf("Rear", "Front", "Both"), s.defaultTarget.ordinal, "light-default") { i -> e.update { it.copy(defaultTarget = LightTarget.values()[i]) } }

        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Keep the rear light on when I leave (notification with Off)", Modifier.weight(1f), color = ink)
            Switch(s.keepOn, { v -> e.update { it.copy(keepOn = v) } }, Modifier.testTag("light-keep"))
        }
    }
}
