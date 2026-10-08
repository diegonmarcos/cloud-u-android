@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.diegonmarcos.cloudcalc.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudcalc.Declarations
import com.diegonmarcos.cloudcalc.Edit
import com.diegonmarcos.cloudcalc.Editor
import com.diegonmarcos.cloudcalc.Fx
import com.diegonmarcos.cloudcalc.Logic
import com.diegonmarcos.cloudcalc.R
import com.diegonmarcos.cloudcalc.decide.JevFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Engine calls block on a binder: always off the main thread. */
private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

/** How long typing must pause before a live result is asked for. */
private const val DEBOUNCE_MS = 150L

/**
 * THE ONE DISPATCH from a mode's declared `kind` to its renderer. test/test-calc-shell.sh reads
 * these branch labels and diffs them against every kind build.json declares, both ways.
 */
@Composable
fun ModeScreen(mode: Declarations.Mode) {
    Box(Modifier.fillMaxSize().testTag(CalcTags.mode(mode.id))) {
        when (mode.kind) {
            "expression" -> ExpressionMode(mode)
            "catalog" -> CatalogMode(mode)
            "converter" -> ConverterMode(mode)
            "form" -> FormMode(mode)
            "plot" -> PlotMode(mode)
            "meter" -> MeterMode(mode)
            "sound_events" -> SoundEventsMode(mode)
            "sound_history" -> SoundHistoryMode(mode)
            "sound_generator" -> SoundGeneratorMode(mode)
            "sound_identify" -> SoundIdentifyMode(mode)
            "camera_measure" -> CameraMeasureMode(mode)
            "camera_level" -> CameraLevelMode(mode)
            "camera_identify" -> CameraIdentifyMode(mode)
            "camera_colour" -> CameraColourMode(mode)
            "camera_text" -> CameraTextMode(mode)
            "image_route" -> ImageRouteMode(mode)
            "history" -> HistoryMode(mode)
            "worldclock" -> WorldClockMode(mode)
            "alarms" -> AlarmsMode(mode)
            "timers" -> TimersMode(mode)
            "stopwatch" -> StopwatchMode(mode)
            "interval" -> IntervalMode(mode)
            "bedtime" -> BedtimeMode(mode)
            "jev" -> JevMode(mode)
            "jev_token" -> JevTokenMode(mode)
            "jev_routing" -> JevRoutingMode(mode)
            "jev_models" -> JevModelsMode(mode)
            "jev_test" -> JevTestMode(mode)
            else -> Text(stringResource(R.string.unknown_kind, mode.kind), Modifier.padding(CalcMetrics.gutter))
        }
    }
}

// ── expression: Standard, Scientific, Programmer, CAS ──────────────────────────────────────

@Composable
private fun ExpressionMode(mode: Declarations.Mode) {
    val api = LocalCalcApi.current
    val state = LocalCalcState.current
    // ONE input value - text AND selection - that the soft keyboard and the keypad both edit.
    var field by rememberSaveable(mode.id, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    val text = field.text
    fun current() = Edit.of(field.text, field.selection.start, field.selection.end)
    fun setEdit(e: Edit) { field = TextFieldValue(e.text, TextRange(e.start, e.end)) }
    var overrides by remember(mode.id) { mutableStateOf(mapOf<String, Int>()) }
    // What the engine answered, and the text it answered FOR: a result is shown only while the two agree,
    // so the result line can never be an older text's value (or an echo of the expression) after an edit.
    var answer by remember(mode.id) { mutableStateOf<Pair<String, Logic.Result>?>(null) }
    val result = answer?.takeIf { it.first == text }?.second
    var bases by remember(mode.id) { mutableStateOf(listOf<Pair<String, String>>()) }
    var suggestions by remember(mode.id) { mutableStateOf(listOf<Logic.Item>()) }
    val options = Logic.options(mode.options, overrides)

    LaunchedEffect(state.pending) {
        val p = state.pending
        if (p != null && p.first == mode.id) {
            setEdit(Editor.reuse(current(), p.second))
            state.pending = null
        }
    }

    LaunchedEffect(text, options) {
        if (text.isBlank()) { answer = null; bases = emptyList(); return@LaunchedEffect }
        delay(DEBOUNCE_MS)
        val asked = text
        answer = asked to Logic.result(io { api.eval(Logic.normalize(asked), options) })
        bases = mode.showBases.map { c -> c.label to Logic.result(io { api.eval(Logic.normalize(asked), Logic.options(options, mapOf(c.key to c.value))) }).text }
    }
    LaunchedEffect(text, field.selection.start) {
        val word = Editor.wordBeforeCursor(current())
        suggestions = if (word.length >= 2) Logic.items(io { api.complete(word, 8) }) else emptyList()
    }

    Column(Modifier.fillMaxSize().padding(CalcMetrics.gutter)) {
        // DISPLAY BEGIN: every view that depends on the text or its result lives in this one box, which takes the height the keypad leaves, so the keypad is anchored to the bottom and never moves (test C14).
        Column(Modifier.fillMaxWidth().weight(1f).heightIn(min = CalcMetrics.displayMinHeight).verticalScroll(rememberScrollState()).testTag(CalcTags.DISPLAY)) {
            ChoiceRow(mode.angleChoices, options) { c -> overrides = overrides + (c.key to c.value) }
            ChoiceRow(mode.baseChoices, options) { c -> overrides = overrides + (c.key to c.value) }
            OutlinedTextField(
                value = field,
                onValueChange = { field = it },
                modifier = Modifier.fillMaxWidth().testTag(CalcTags.INPUT),
                textStyle = MaterialTheme.typography.headlineSmall,
                placeholder = { Text(stringResource(R.string.expression_hint)) },
            )
            if (suggestions.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(CalcMetrics.small)) {
                    items(suggestions) { s ->
                        AssistChip(onClick = { setEdit(Editor.complete(current(), s.name)) }, label = { Text(s.name) })
                    }
                }
            }
            ResultBlock(result)
            bases.forEach { (label, value) ->
                Row(Modifier.fillMaxWidth().padding(vertical = CalcMetrics.small), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label, style = MaterialTheme.typography.labelLarge)
                    Text(value, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        // DISPLAY END
        mode.keys.forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = CalcMetrics.small), horizontalArrangement = Arrangement.spacedBy(CalcMetrics.small)) {
                row.forEach { k ->
                    val press: () -> Unit = {
                        val r = result
                        if (k.action != Declarations.Action.EVALUATE) {
                            setEdit(Editor.press(current(), k))
                        } else if (r != null && r.ok) {
                            state.remember(Logic.Entry(mode.id, text, r.text), historyMax())
                            setEdit(Editor.replaceAll(r.text))
                        }
                    }
                    val m = Modifier.weight(1f).height(CalcMetrics.keyHeight).testTag(CalcTags.key(k.label))
                    if (k.action == Declarations.Action.EVALUATE) Button(onClick = press, modifier = m, contentPadding = PaddingValues(0.dp)) { Text(k.label) }
                    else FilledTonalButton(onClick = press, modifier = m, contentPadding = PaddingValues(0.dp)) { Text(k.label) }
                }
            }
        }
    }
}

/** The history cap the History mode declares. */
internal fun historyMax(): Int = Declarations.modes.firstOrNull { it.kind == "history" }?.historyMax ?: 200

@Composable
private fun ChoiceRow(choices: List<Declarations.Choice>, options: String, onPick: (Declarations.Choice) -> Unit) {
    if (choices.isEmpty()) return
    // No chip is lit unless the options really carry that value: lighting the first one by
    // default would claim a setting the engine is not using.
    val current = Logic.optionValue(options, choices.first().key, Int.MIN_VALUE)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
        items(choices, key = { it.label }) { c ->
            FilterChip(selected = c.value == current, onClick = { onPick(c) }, label = { Text(c.label) })
        }
    }
}

@Composable
private fun ResultBlock(result: Logic.Result?) {
    if (result == null) return
    Column(Modifier.fillMaxWidth().padding(vertical = CalcMetrics.gap)) {
        if (result.error.isNotBlank()) {
            Text(result.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(CalcTags.RESULT))
        } else {
            Text(
                "= " + result.text,
                style = MaterialTheme.typography.headlineMedium,
                color = if (result.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(CalcTags.RESULT),
            )
        }
        result.messages.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

// ── catalog: Physics & Constants ────────────────────────────────────────────────────────────

@Composable
private fun CatalogMode(mode: Declarations.Mode) {
    val api = LocalCalcApi.current
    val state = LocalCalcState.current
    var rows by remember(mode.id) { mutableStateOf(listOf<Logic.Item>()) }
    val values = remember(mode.id) { mutableStateMapOf<String, String>() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(mode.id) {
        rows = mode.catalog.flatMap { src -> Logic.items(io { api.items(src.kind, src.category, 500) }) }.distinctBy { it.name }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(CalcMetrics.gutter)) {
        if (rows.isEmpty()) item { Text(stringResource(R.string.loading)) }
        items(rows, key = { it.name }) { r ->
            Column(
                Modifier.fillMaxWidth().clickable {
                    scope.launch {
                        val v = Logic.result(io { api.eval(r.name, mode.options) })
                        values[r.name] = if (v.ok) v.text else v.error
                        if (v.ok) state.remember(Logic.Entry(mode.id, r.name, v.text), historyMax())
                    }
                }.padding(vertical = CalcMetrics.gap),
            ) {
                Text(r.title.ifBlank { r.name }, style = MaterialTheme.typography.bodyLarge)
                Text(r.name + "  ·  " + r.category, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                values[r.name]?.let { Text("= $it", color = MaterialTheme.colorScheme.primary) }
            }
            HorizontalDivider()
        }
    }
}

// ── converter: Units, Currency ──────────────────────────────────────────────────────────────

@Composable
private fun ConverterMode(mode: Declarations.Mode) {
    val api = LocalCalcApi.current
    val state = LocalCalcState.current
    val scope = rememberCoroutineScope()
    var category by rememberSaveable(mode.id) { mutableStateOf(mode.defaults["category"] ?: mode.categories.firstOrNull().orEmpty()) }
    var value by rememberSaveable(mode.id) { mutableStateOf(mode.defaults["value"] ?: "1") }
    var from by rememberSaveable(mode.id) { mutableStateOf(mode.defaults["from"].orEmpty()) }
    var to by rememberSaveable(mode.id) { mutableStateOf(mode.defaults["to"].orEmpty()) }
    var units by remember(mode.id) { mutableStateOf(listOf<Logic.Item>()) }
    // The answer carries the conversion it answers; it is shown only while that is still the current one.
    var answer by remember(mode.id) { mutableStateOf<Pair<String, Logic.Result>?>(null) }
    val expr = Logic.convert(value, from, to)
    val result = answer?.takeIf { it.first == expr }?.second
    var rates by remember(mode.id) { mutableStateOf("") }
    /** The stamp the rates carry; the matrix recomputes when it moves. */
    var ratesTime by remember(mode.id) { mutableStateOf(0L) }

    LaunchedEffect(category) {
        units = Logic.items(io { api.items("unit", category, 1000) })
        val names = units.map { it.name }
        if (from !in names) from = names.getOrElse(0) { "" }
        if (to !in names) to = names.getOrElse(1) { from }
    }
    LaunchedEffect(value, from, to) {
        if (value.isBlank() || from.isBlank() || to.isBlank()) { answer = null; return@LaunchedEffect }
        delay(DEBOUNCE_MS)
        val asked = Logic.convert(value, from, to)
        answer = asked to Logic.result(io { api.eval(asked, mode.options) })
    }
    // On open: show the date the rates carry, and when it is older than the latest ECB publication fetch. The
    // line is always re-read from the engine after a fetch, so it shows the fetched file's own date.
    if (mode.rates) LaunchedEffect(mode.id) {
        rates = ratesLine(io { api.ratesInfo() }.also { ratesTime = Fx.time(it) })
        val fetched = io { Fx.refreshIfStale(api, System.currentTimeMillis()) } ?: return@LaunchedEffect
        val info = io { api.ratesInfo() }
        ratesTime = Fx.time(info)
        rates = ratesLine(info) + failureNote(fetched)
        answer = Logic.convert(value, from, to).let { q -> q to Logic.result(io { api.eval(q, mode.options) }) }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(CalcMetrics.gutter)) {
        if (mode.categories.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
                items(mode.categories, key = { it }) { c -> FilterChip(selected = c == category, onClick = { category = c }, label = { Text(c) }) }
            }
        }
        OutlinedTextField(
            value = value, onValueChange = { value = it },
            modifier = Modifier.fillMaxWidth().testTag(CalcTags.INPUT),
            label = { Text(stringResource(R.string.value)) },
            textStyle = MaterialTheme.typography.titleMedium,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                result?.takeIf { it.ok }?.let { r -> state.remember(Logic.Entry(mode.id, Logic.convert(value, from, to), r.text), historyMax()) }
            }),
        )
        Row(Modifier.fillMaxWidth().padding(vertical = CalcMetrics.gap), horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
            UnitPicker(units, from, mode.favourites, Modifier.weight(1f)) { from = it }
            TextButton(onClick = { val f = from; from = to; to = f }, modifier = Modifier.height(CalcMetrics.compactHeight).testTag(CalcTags.CONVERT_SWAP), contentPadding = PaddingValues(horizontal = CalcMetrics.gap)) { Text("⇄") }
            UnitPicker(units, to, mode.favourites, Modifier.weight(1f)) { to = it }
        }
        ResultBlock(result)
        // = keeps the conversion in History (the keypad's = for a converter); compact, one row with the rates line.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
            if (mode.rates) Text(rates, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else Box(Modifier.weight(1f))
            result?.takeIf { it.ok }?.let { r ->
                FilledTonalButton(
                    onClick = { state.remember(Logic.Entry(mode.id, Logic.convert(value, from, to), r.text), historyMax()) },
                    modifier = Modifier.height(CalcMetrics.compactHeight).testTag(CalcTags.CONVERT_EQ), contentPadding = PaddingValues(horizontal = CalcMetrics.gap),
                ) { Text("=") }
            }
            if (mode.rates) OutlinedButton(
                onClick = {
                    scope.launch {
                        val fetched = io { Fx.refresh(api, System.currentTimeMillis()) }
                        val info = io { api.ratesInfo() }
                        ratesTime = Fx.time(info)
                        rates = ratesLine(info) + failureNote(fetched)
                        answer = Logic.convert(value, from, to).let { q -> q to Logic.result(io { api.eval(q, mode.options) }) }
                    }
                },
                modifier = Modifier.height(CalcMetrics.compactHeight), contentPadding = PaddingValues(horizontal = CalcMetrics.gap),
            ) { Text(stringResource(R.string.update_rates), style = MaterialTheme.typography.labelMedium) }
        }
        if (mode.rates && mode.favourites.size > 1) RatesMatrix(mode, ratesTime, rates)
    }
}

/** Cross rates of the favourites: a row's one unit in each column's currency, dense, with the rates date. */
@Composable
private fun RatesMatrix(mode: Declarations.Mode, ratesTime: Long, ratesText: String) {
    val api = LocalCalcApi.current
    val codes = mode.favourites
    var cells by remember(mode.id) { mutableStateOf(mapOf<Pair<String, String>, String>()) }
    LaunchedEffect(ratesTime) {
        cells = io {
            codes.flatMap { r -> codes.map { c -> r to c } }.associateWith { (r, c) ->
                if (r == c) "—" else Logic.result(api.eval(Logic.convert("1", r, c), mode.options)).let { if (it.ok) Fx.cell(it.text) else "?" }
            }
        }
    }
    Column(Modifier.fillMaxWidth().padding(top = CalcMetrics.gap).testTag(CalcTags.MATRIX)) {
        Text(ratesText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().padding(vertical = CalcMetrics.hairline)) {
            Text("", Modifier.weight(1f))
            codes.forEach { c -> Text(c, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, textAlign = androidx.compose.ui.text.style.TextAlign.End) }
        }
        codes.forEach { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = CalcMetrics.hairline)) {
                Text(r, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                codes.forEach { c ->
                    Text(cells[r to c].orEmpty(), Modifier.weight(1f).testTag(CalcTags.matrixCell(r, c)), style = MaterialTheme.typography.labelSmall,
                        textAlign = androidx.compose.ui.text.style.TextAlign.End, maxLines = 1)
                }
            }
        }
    }
}

private fun failureNote(fetchJson: String): String = runCatching {
    val o = org.json.JSONObject(fetchJson)
    if (o.optBoolean("ok")) "" else " (update failed: " + o.optString("error").ifBlank { "no source answered" } + ")"
}.getOrDefault("")

private fun ratesLine(json: String): String = runCatching {
    val t = org.json.JSONObject(json).optLong("time")
    "Rates as of " + if (t > 0) java.time.Instant.ofEpochSecond(t).toString().take(10) else "unknown"
}.getOrDefault(json)

@Composable
private fun UnitPicker(units: List<Logic.Item>, selected: String, favourites: List<String>, modifier: Modifier, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().height(CalcMetrics.compactHeight), contentPadding = PaddingValues(horizontal = CalcMetrics.gap)) { Text(selected.ifBlank { "—" }) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            // The favourites first, in their declared order, then a rule, then the rest.
            val (fav, rest) = Fx.pinned(units.map { it.name }, favourites)
            val byName = units.associateBy { it.name }
            @Composable fun item(name: String) = DropdownMenuItem(
                text = { Text((byName[name]?.title ?: name) + " (" + name + ")", style = MaterialTheme.typography.bodySmall) },
                onClick = { onPick(name); open = false },
                modifier = Modifier.height(CalcMetrics.compactHeight).testTag(CalcTags.pickerItem(name)),
            )
            fav.forEach { item(it) }
            if (fav.isNotEmpty()) HorizontalDivider()
            rest.forEach { item(it) }
        }
    }
}

// ── form: Date & Time, Finance, Acoustics ───────────────────────────────────────────────────

@Composable
private fun FormMode(mode: Declarations.Mode) {
    if (mode.forms.isEmpty()) return
    var formId by rememberSaveable(mode.id) { mutableStateOf(mode.forms.first().id) }
    val form = mode.forms.firstOrNull { it.id == formId } ?: mode.forms.first()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(CalcMetrics.gutter)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
            items(mode.forms, key = { it.id }) { f -> FilterChip(selected = f.id == form.id, onClick = { formId = f.id }, label = { Text(f.label) }) }
        }
        androidx.compose.runtime.key(form.id) { FormBody(mode, form) }
    }
}

@Composable
private fun FormBody(mode: Declarations.Mode, form: Declarations.Form) {
    val api = LocalCalcApi.current
    val state = LocalCalcState.current
    val values = remember(form.id) { mutableStateMapOf<String, String>().apply { form.fields.forEach { put(it.id, it.default) } } }
    var outputs by remember(form.id) { mutableStateOf(listOf<Pair<String, Logic.Result>>()) }
    val snapshot = values.toMap()
    LaunchedEffect(snapshot) {
        delay(DEBOUNCE_MS)
        outputs = form.outputs.map { o -> o.label to Logic.result(io { api.eval(Logic.fill(o.expr, snapshot), mode.options) }) }
    }
    form.fields.forEach { f ->
        OutlinedTextField(
            value = values[f.id].orEmpty(),
            onValueChange = { values[f.id] = it },
            label = { Text(f.label) },
            modifier = Modifier.fillMaxWidth().padding(vertical = CalcMetrics.small).testTag(CalcTags.INPUT + "_" + f.id),
            singleLine = true,
        )
    }
    outputs.forEach { (label, r) ->
        Row(
            Modifier.fillMaxWidth().clickable {
                if (r.ok) state.remember(Logic.Entry(mode.id, label, r.text), historyMax())
            }.padding(vertical = CalcMetrics.gap),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (r.ok) r.text else r.error.ifBlank { r.messages.joinToString() },
                style = MaterialTheme.typography.titleMedium,
                color = if (r.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(CalcTags.RESULT),
            )
        }
    }
}

// ── plot: Graph ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PlotMode(mode: Declarations.Mode) {
    val api = LocalCalcApi.current
    val p = mode.plot ?: return
    var expr by rememberSaveable(mode.id) { mutableStateOf(p.default) }
    var xmin by rememberSaveable(mode.id) { mutableStateOf(p.xmin.toString()) }
    var xmax by rememberSaveable(mode.id) { mutableStateOf(p.xmax.toString()) }
    var data by remember(mode.id) { mutableStateOf(Logic.Plot(emptyList(), emptyList(), "")) }
    LaunchedEffect(expr, xmin, xmax) {
        val lo = xmin.toDoubleOrNull(); val hi = xmax.toDoubleOrNull()
        if (expr.isBlank() || lo == null || hi == null || hi <= lo) return@LaunchedEffect
        delay(DEBOUNCE_MS)
        data = Logic.plot(io { api.plot(expr, lo, hi, p.steps) })
    }
    val line = MaterialTheme.colorScheme.primary
    val axis = MaterialTheme.colorScheme.outline
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(CalcMetrics.gutter)) {
        OutlinedTextField(
            value = expr, onValueChange = { expr = it },
            label = { Text(stringResource(R.string.plot_hint)) },
            modifier = Modifier.fillMaxWidth().testTag(CalcTags.INPUT), singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
            OutlinedTextField(value = xmin, onValueChange = { xmin = it }, label = { Text("x min") }, modifier = Modifier.weight(1f), singleLine = true)
            OutlinedTextField(value = xmax, onValueChange = { xmax = it }, label = { Text("x max") }, modifier = Modifier.weight(1f), singleLine = true)
        }
        if (data.error.isNotBlank()) Text(data.error, color = MaterialTheme.colorScheme.error)
        Canvas(Modifier.fillMaxWidth().height(CalcMetrics.plotHeight).padding(vertical = CalcMetrics.gap).testTag(CalcTags.RESULT)) {
            val pts = data.xs.zip(data.ys)
            val ys = pts.mapNotNull { it.second }
            if (pts.isEmpty() || ys.isEmpty()) return@Canvas
            val x0 = data.xs.first(); val x1 = data.xs.last()
            var y0 = ys.min(); var y1 = ys.max()
            if (y1 - y0 < 1e-12) { y0 -= 1; y1 += 1 }
            fun sx(x: Double) = ((x - x0) / (x1 - x0) * size.width).toFloat()
            fun sy(y: Double) = (size.height - (y - y0) / (y1 - y0) * size.height).toFloat()
            if (y0 <= 0 && y1 >= 0) drawLine(axis, Offset(0f, sy(0.0)), Offset(size.width, sy(0.0)))
            if (x0 <= 0 && x1 >= 0) drawLine(axis, Offset(sx(0.0), 0f), Offset(sx(0.0), size.height))
            val path = Path()
            var pen = false
            for ((x, y) in pts) {
                if (y == null) {
                    pen = false
                } else if (!pen) {
                    path.moveTo(sx(x), sy(y))
                    pen = true
                } else {
                    path.lineTo(sx(x), sy(y))
                }
            }
            drawPath(path, line, style = Stroke(width = CalcMetrics.stroke.toPx()))
        }
        if (data.ys.isNotEmpty()) {
            val ys = data.ys.filterNotNull()
            if (ys.isNotEmpty()) Text("y ∈ [" + "%.4g".format(ys.min()) + ", " + "%.4g".format(ys.max()) + "]", style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ── history ─────────────────────────────────────────────────────────────────────────────────

@Composable
private fun HistoryMode(mode: Declarations.Mode) {
    val api = LocalCalcApi.current
    val state = LocalCalcState.current
    var variables by remember(mode.id) { mutableStateOf(listOf<Logic.Item>()) }
    LaunchedEffect(mode.id) { variables = Logic.items(io { api.items("variable", "Temporary", 100) }) }
    LazyColumn(Modifier.fillMaxSize().testTag(CalcTags.HISTORY_LIST), contentPadding = PaddingValues(CalcMetrics.gutter)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.history), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { state.clearHistory() }, modifier = Modifier.testTag(CalcTags.HISTORY_CLEAR)) { Text(stringResource(R.string.clear)) }
            }
        }
        if (state.history.isEmpty()) item { Text(stringResource(R.string.history_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        itemsIndexed(state.history.toList()) { i, e ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = CalcMetrics.gap)) {
                    // Tap the expression to reuse it, the result to reuse that.
                    Text(e.expr, Modifier.fillMaxWidth().clickable { state.send(e.mode, e.expr) }.testTag(CalcTags.historyExpr(i)),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("= " + e.result, Modifier.fillMaxWidth().clickable { state.send(e.mode, e.result) }.testTag(CalcTags.historyResult(i)),
                        style = MaterialTheme.typography.titleMedium)
                    if (e.ts != 0L) Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(e.ts)),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // #770 a follow-up question kept with its result: every option's probability.
                    if (e.decision.isNotEmpty()) Text(JevFlow.summary(e.decision), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
                TextButton(onClick = { state.deleteHistory(i) }, modifier = Modifier.testTag(CalcTags.historyDelete(i))) { Text(stringResource(R.string.delete)) }
            }
            HorizontalDivider()
        }
        item { Text(stringResource(R.string.variables), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = CalcMetrics.gutter)) }
        if (variables.isEmpty()) item { Text(stringResource(R.string.variables_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(variables, key = { it.name }) { v ->
            Text(v.name, Modifier.fillMaxWidth().clickable { state.send(mode.id, v.name) }.padding(vertical = CalcMetrics.gap))
        }
    }
}
