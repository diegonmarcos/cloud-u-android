package com.diegonmarcos.cloudme.health

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.HealthConnectClient
import com.diegonmarcos.cloudme.R
import com.diegonmarcos.superapp.health.HealthConnectGateway

/** Small me_*-palette building blocks of the Health pages. Pages are ONE vertical scroller (HealthPage); nothing here scrolls vertically. */
@Composable internal fun ink() = colorResource(R.color.me_text)
@Composable internal fun dim() = colorResource(R.color.me_text_dim)
@Composable internal fun accent() = colorResource(R.color.me_primary)

internal val Good = Color(0xFF34D399)
internal val Warn = Color(0xFFF59E0B)
internal val Bad = Color(0xFFF43F5E)

@Composable
internal fun HCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.fillMaxWidth().padding(top = 10.dp).clip(shape).background(colorResource(R.color.me_surface))
            .border(1.dp, colorResource(R.color.me_outline), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(14.dp),
    ) { content() }
}

@Composable internal fun HTitle(text: String, sub: String = "") {
    Text(text, color = ink(), fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
    if (sub.isNotBlank()) Text(sub, color = dim(), fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp))
}

@Composable internal fun HHead(text: String) =
    Text(text, color = ink(), fontSize = 15.sp, fontWeight = FontWeight.Bold)

@Composable internal fun HText(text: String, color: Color = dim(), size: Int = 13, modifier: Modifier = Modifier) =
    Text(text, color = color, fontSize = size.sp, lineHeight = (size + 6).sp, modifier = modifier.padding(top = 4.dp))

@Composable internal fun HRow(label: String, value: String, valueColor: Color = ink()) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(label, color = dim(), fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = valueColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable internal fun HEmpty(text: String) = HCard { HText(text) }

@Composable
internal fun Pill(text: String, selected: Boolean = false, color: Color? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    val bg = if (selected) (color ?: accent()) else colorResource(R.color.me_surface_high)
    Text(
        text, color = if (selected) colorResource(R.color.me_on_primary) else ink(), fontSize = 13.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(end = 8.dp, top = 8.dp).clip(shape).background(bg).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** A single-choice strip: one horizontal row (the only scroll it owns is horizontal, so it never nests the page's vertical one). */
@Composable
internal fun Choice(options: List<Pair<String, String>>, selected: String?, onPick: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        options.forEach { (id, label) -> Pill(label, id == selected) { onPick(id) } }
    }
}

@Composable
internal fun NumField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value, onValueChange = { s -> onChange(s.filter { it.isDigit() || it == '.' }.take(7)) }, singleLine = true,
        label = { Text(label) }, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
        colors = fieldColors(), modifier = modifier.padding(top = 8.dp),
    )
}

@Composable
internal fun TextBox(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, colors = fieldColors(),
        modifier = modifier.fillMaxWidth().padding(top = 8.dp), minLines = 3,
    )
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = ink(), unfocusedTextColor = ink(), cursorColor = accent(),
    focusedBorderColor = accent(), unfocusedBorderColor = colorResource(R.color.me_outline),
    focusedLabelColor = accent(), unfocusedLabelColor = dim(),
)

@Composable
internal fun Tick(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(top = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Checkbox(checked, { onChange(it) }, colors = CheckboxDefaults.colors(checkedColor = accent(), uncheckedColor = dim(), checkmarkColor = colorResource(R.color.me_on_primary)))
        Text(label, color = ink(), fontSize = 14.sp)
    }
}

@Composable
internal fun Slide(value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onChange: (Float) -> Unit) {
    Slider(
        value = value, onValueChange = onChange, valueRange = range, steps = steps,
        colors = SliderDefaults.colors(thumbColor = accent(), activeTrackColor = accent(), inactiveTrackColor = colorResource(R.color.me_outline)),
    )
}

/** A line chart on a Canvas: [series] of (x, y) points, optional vertical markers (x, label colour). No data, no chart: callers show their own empty state. */
data class ChartSeries(val points: List<Pair<Double, Double>>, val color: Color)

@Composable
internal fun LineChart(series: List<ChartSeries>, markers: List<Double> = emptyList(), height: Int = 160, yMin: Double? = null, yMax: Double? = null) {
    val grid = colorResource(R.color.me_outline)
    val mark = Warn
    val all = series.flatMap { it.points }
    if (all.isEmpty()) return
    val x0 = all.minOf { it.first }; val x1 = all.maxOf { it.first }
    val lo = yMin ?: all.minOf { it.second }; val hi = (yMax ?: all.maxOf { it.second }).let { if (it <= lo) lo + 1 else it }
    Canvas(Modifier.fillMaxWidth().height(height.dp).padding(top = 8.dp)) {
        val w = size.width; val h = size.height
        fun px(x: Double) = if (x1 == x0) w / 2 else ((x - x0) / (x1 - x0) * w).toFloat()
        fun py(y: Double) = (h - ((y - lo) / (hi - lo) * h)).toFloat()
        for (i in 0..3) drawLine(grid, Offset(0f, h * i / 3f), Offset(w, h * i / 3f), 1f)
        markers.filter { it in x0..x1 }.forEach { drawLine(mark, Offset(px(it), 0f), Offset(px(it), h), 2f) }
        series.forEach { s ->
            val pts = s.points.sortedBy { it.first }
            if (pts.size == 1) drawCircle(s.color, 5f, Offset(px(pts[0].first), py(pts[0].second)))
            else {
                val p = Path()
                pts.forEachIndexed { i, (x, y) -> if (i == 0) p.moveTo(px(x), py(y)) else p.lineTo(px(x), py(y)) }
                drawPath(p, s.color, style = Stroke(width = 4f, cap = StrokeCap.Round))
                pts.forEach { (x, y) -> drawCircle(s.color, 4f, Offset(px(x), py(y))) }
            }
        }
    }
}

/** Bars (one per entry) on a Canvas. */
@Composable
internal fun BarChart(values: List<Double>, color: Color, height: Int = 120) {
    if (values.isEmpty()) return
    val max = values.max().let { if (it <= 0) 1.0 else it }
    Canvas(Modifier.fillMaxWidth().height(height.dp).padding(top = 8.dp)) {
        val slot = size.width / values.size
        values.forEachIndexed { i, v ->
            val bh = (v / max * size.height).toFloat()
            drawRect(color, Offset(i * slot + slot * 0.15f, size.height - bh), androidx.compose.ui.geometry.Size(slot * 0.7f, bh))
        }
    }
}

/**
 * A tracker section fed by Health Connect: says honestly when Health Connect is missing, when permission is not granted
 * (with the grant button) and when it is loading; [content] only ever sees real data.
 */
@Composable
internal fun <T> HcPanel(needed: Set<String>, load: suspend (HealthConnectClient) -> T, content: @Composable (T) -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val state by produceState<HcState<T>>(HcState.Loading, tick) { value = HealthHc.guarded(ctx, needed, load) }
    val launcher = rememberLauncherForActivityResult(HealthConnectGateway.requestPermissionsContract()) { tick++ }
    when (val s = state) {
        HcState.Loading -> HEmpty("Reading Health Connect...")
        is HcState.Unavailable -> HEmpty(s.why)
        is HcState.NeedPermission -> HCard {
            HText("Health Connect has not been allowed to share this with Cloud Me yet.")
            Pill("Allow in Health Connect", true) { launcher.launch(s.missing) }
        }
        is HcState.Ready -> content(s.data)
    }
}
