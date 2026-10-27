@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.diegonmarcos.cloudcalc.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.diegonmarcos.cloudcalc.DataUnits
import com.diegonmarcos.cloudcalc.Declarations
import com.diegonmarcos.cloudcalc.Logic
import com.diegonmarcos.cloudcalc.Nutrition

// ── nutrition: Nutrition – Food (food quantities by density, energy, macros per portion, oven) ─────────
//
// Chapters are the mode's `categories` (build.json), in this order: [0] food quantity, [1] macros per portion,
// [2] energy kcal <-> kJ, [3] oven °C / °F / gas mark. The figures are nutrition.json's (USDA FoodData Central,
// cited in its `sources`); the arithmetic is Nutrition's BigDecimal, no engine.

private const val OVEN_C = "°C"
private const val OVEN_F = "°F"
private const val OVEN_GAS = "gas mark"
private val OVEN_UNITS = listOf(OVEN_C, OVEN_F, OVEN_GAS)

@Composable
internal fun NutritionMode(mode: Declarations.Mode) {
    val d = Nutrition.bundled
    val state = LocalCalcState.current
    var category by rememberSaveable(mode.id) { mutableStateOf(mode.defaults["category"] ?: mode.categories.firstOrNull().orEmpty()) }
    val food = rememberSaveable(mode.id) { mutableStateOf(mode.defaults["food"] ?: d.foods.first().id) }
    val value = rememberSaveable(mode.id) { mutableStateOf(mode.defaults["value"] ?: "1") }
    val from = rememberSaveable(mode.id) { mutableStateOf(mode.defaults["from"].orEmpty()) }
    val to = rememberSaveable(mode.id) { mutableStateOf(mode.defaults["to"].orEmpty()) }
    // A search hit opens this mode on its chapter and food.
    LaunchedEffect(state.jump) {
        val j = state.jump?.takeIf { it.modeId == mode.id } ?: return@LaunchedEffect
        if (j.category in mode.categories) category = j.category
        if (d.food(j.unit) != null) food.value = j.unit
        val units = when (mode.categories.indexOf(j.category)) { 0, 1 -> d.quantityUnits.map { it.name }; 2 -> d.energy.map { it.name }; else -> OVEN_UNITS }
        if (j.unit in units) from.value = j.unit
        state.jump = null
    }
    val chapter = mode.categories.indexOf(category)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(CalcMetrics.gutter)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
            items(mode.categories, key = { it }) { c -> FilterChip(selected = c == category, onClick = { category = c }, label = { Text(c) }) }
        }
        when (chapter) {
            0 -> QuantityBody(mode, d, food, value, from, to)
            1 -> MacrosBody(mode, d, food, value, from)
            2 -> EnergyBody(mode, d, value, from, to)
            3 -> OvenBody(mode, d, value, from)
        }
        Text(
            d.sources.values.joinToString(" · "), Modifier.padding(top = CalcMetrics.gap),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun foodItems(d: Nutrition.Data) = d.foods.map { Logic.Item(it.id, it.name, "food", "") }
private fun qtyItems(units: List<Nutrition.Qty>) = units.map { Logic.Item(it.name, it.label, "unit", "") }
private fun nameOf(item: Logic.Item?, name: String): String = item?.title ?: name

@Composable
private fun AmountField(value: MutableState<String>, keep: () -> Unit) {
    OutlinedTextField(
        value = value.value, onValueChange = { value.value = it },
        modifier = Modifier.fillMaxWidth().testTag(CalcTags.INPUT),
        label = { Text("Value") },
        textStyle = MaterialTheme.typography.titleMedium,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { keep() }),
    )
}

@Composable
private fun FoodPicker(d: Nutrition.Data, food: MutableState<String>) {
    val f = d.food(food.value) ?: d.foods.first()
    UnitPicker(foodItems(d), f.id, emptyList(), Modifier.fillMaxWidth().padding(vertical = CalcMetrics.gap).testTag(CalcTags.NUTRITION_FOOD), text = { item, n -> nameOf(item, n) }) { food.value = it }
    Text(f.usda + " · " + f.portion + " = " + f.cupG.toPlainString() + " g", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Keep(result: Logic.Result?, keep: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f))
        if (result?.ok == true) FilledTonalButton(
            onClick = { keep() },
            modifier = Modifier.height(CalcMetrics.compactHeight).testTag(CalcTags.CONVERT_EQ), contentPadding = PaddingValues(horizontal = CalcMetrics.gap),
        ) { Text("=") }
    }
}

private fun parsed(v: String, notNumber: String): Pair<java.math.BigDecimal?, Logic.Result?> {
    if (v.isBlank()) return null to null
    val x = DataUnits.parse(v)
    return x to if (x == null) Logic.Result(false, "", emptyList(), notNumber) else null
}

/** Food quantity: an amount of one unit of a food in another, mass <-> volume through the food's density. */
@Composable
private fun QuantityBody(mode: Declarations.Mode, d: Nutrition.Data, food: MutableState<String>, value: MutableState<String>, from: MutableState<String>, to: MutableState<String>) {
    val state = LocalCalcState.current
    val f = d.food(food.value) ?: d.foods.first()
    val fu = d.quantityUnit(from.value) ?: d.quantityUnits.first { it.name == "cup" }
    val tu = d.quantityUnit(to.value) ?: d.quantityUnits.first { it.name == "g" }
    val (x, err) = parsed(value.value, "Not a number: ${value.value.trim()}")
    val result = err ?: x?.let { Logic.Result(true, Nutrition.show(Nutrition.convert(d, f, it, fu, tu)) + " " + tu.name, emptyList(), "") }
    val keep: () -> Unit = {
        if (result?.ok == true) state.remember(Logic.Entry(mode.id, "${value.value.trim()} ${fu.name} ${f.name} to ${tu.name}", result.text), historyMax())
    }
    FoodPicker(d, food)
    AmountField(value, keep)
    val items = qtyItems(d.quantityUnits)
    Row(Modifier.fillMaxWidth().padding(vertical = CalcMetrics.gap), horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
        UnitPicker(items, fu.name, emptyList(), Modifier.weight(1f), text = ::nameOf) { from.value = it }
        TextButton(
            onClick = { val a = fu.name; from.value = tu.name; to.value = a },
            modifier = Modifier.height(CalcMetrics.compactHeight).testTag(CalcTags.CONVERT_SWAP), contentPadding = PaddingValues(horizontal = CalcMetrics.gap),
        ) { Text("⇄") }
        UnitPicker(items, tu.name, emptyList(), Modifier.weight(1f), text = ::nameOf) { to.value = it }
    }
    ResultBlock(result)
    Keep(result, keep)
}

/** Macros per portion: an amount of a food -> energy and macronutrients. */
@Composable
private fun MacrosBody(mode: Declarations.Mode, d: Nutrition.Data, food: MutableState<String>, value: MutableState<String>, from: MutableState<String>) {
    val state = LocalCalcState.current
    val f = d.food(food.value) ?: d.foods.first()
    val u = d.quantityUnit(from.value) ?: d.quantityUnits.first { it.name == "g" }
    val (x, err) = parsed(value.value, "Not a number: ${value.value.trim()}")
    val m = x?.let { Nutrition.macros(d, f, it, u) }
    val result = err ?: m?.let {
        Logic.Result(
            true, Nutrition.show1(it.kcal) + " kcal (" + Nutrition.show1(it.kj) + " kJ)",
            listOf(
                "Portion " + Nutrition.show(it.grams) + " g",
                "Protein " + Nutrition.show1(it.protein) + " g",
                "Fat " + Nutrition.show1(it.fat) + " g",
                "Carbohydrate " + Nutrition.show1(it.carbs) + " g",
                "Fibre " + Nutrition.show1(it.fiber) + " g",
            ), "",
        )
    }
    val keep: () -> Unit = {
        if (result?.ok == true) state.remember(Logic.Entry(mode.id, "${value.value.trim()} ${u.name} ${f.name}", result.text + " · " + result.messages.drop(1).joinToString(", ")), historyMax())
    }
    FoodPicker(d, food)
    Row(Modifier.fillMaxWidth().padding(vertical = CalcMetrics.gap), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
        Box(Modifier.weight(1f)) { AmountField(value, keep) }
        UnitPicker(qtyItems(d.quantityUnits), u.name, emptyList(), Modifier.weight(1f), text = ::nameOf) { from.value = it }
    }
    ResultBlock(result)
    Keep(result, keep)
}

/** Energy: kcal <-> kJ (and cal, J). */
@Composable
private fun EnergyBody(mode: Declarations.Mode, d: Nutrition.Data, value: MutableState<String>, from: MutableState<String>, to: MutableState<String>) {
    val state = LocalCalcState.current
    val fu = d.energy.firstOrNull { it.name == from.value } ?: d.energy.first { it.name == "kcal" }
    val tu = d.energy.firstOrNull { it.name == to.value } ?: d.energy.first { it.name == "kJ" }
    val (x, err) = parsed(value.value, "Not a number: ${value.value.trim()}")
    val result = err ?: x?.let { Logic.Result(true, Nutrition.show(Nutrition.energy(it, fu, tu)) + " " + tu.name, emptyList(), "") }
    val keep: () -> Unit = {
        if (result?.ok == true) state.remember(Logic.Entry(mode.id, "${value.value.trim()} ${fu.name} to ${tu.name}", result.text), historyMax())
    }
    AmountField(value, keep)
    val items = qtyItems(d.energy)
    Row(Modifier.fillMaxWidth().padding(vertical = CalcMetrics.gap), horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
        UnitPicker(items, fu.name, emptyList(), Modifier.weight(1f), text = ::nameOf) { from.value = it }
        TextButton(
            onClick = { val a = fu.name; from.value = tu.name; to.value = a },
            modifier = Modifier.height(CalcMetrics.compactHeight).testTag(CalcTags.CONVERT_SWAP), contentPadding = PaddingValues(horizontal = CalcMetrics.gap),
        ) { Text("⇄") }
        UnitPicker(items, tu.name, emptyList(), Modifier.weight(1f), text = ::nameOf) { to.value = it }
    }
    ResultBlock(result)
    Keep(result, keep)
}

/** Oven: °C, °F and gas mark from any one of them. */
@Composable
private fun OvenBody(mode: Declarations.Mode, d: Nutrition.Data, value: MutableState<String>, from: MutableState<String>) {
    val state = LocalCalcState.current
    val u = from.value.takeIf { it in OVEN_UNITS } ?: OVEN_C
    val result = ovenResult(d, value.value, u)
    val keep: () -> Unit = {
        if (result?.ok == true) state.remember(Logic.Entry(mode.id, "${value.value.trim()} $u", result.text + " · " + result.messages.joinToString(", ")), historyMax())
    }
    Row(Modifier.fillMaxWidth().padding(vertical = CalcMetrics.gap), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
        Box(Modifier.weight(1f)) { AmountField(value, keep) }
        UnitPicker(OVEN_UNITS.map { Logic.Item(it, it, "unit", "") }, u, emptyList(), Modifier.weight(1f), text = ::nameOf) { from.value = it }
    }
    ResultBlock(result)
    Keep(result, keep)
}

/** The three oven readings of [text] in [unit]; gas marks come from the cited table (the nearest row for °C / °F). */
internal fun ovenResult(d: Nutrition.Data, text: String, unit: String): Logic.Result? {
    if (text.isBlank()) return null
    fun bad() = Logic.Result(false, "", emptyList(), "Not an oven temperature: ${text.trim()}")
    if (unit == OVEN_GAS) {
        val row = Nutrition.gasRow(d, text) ?: return bad()
        return Logic.Result(true, "${row.c.toPlainString()} °C", listOf("${row.f.toPlainString()} °F", "Gas mark ${row.gas} · ${row.desc}"), "")
    }
    val x = DataUnits.parse(text) ?: return bad()
    val c = if (unit == OVEN_C) x else Nutrition.fToC(x)
    val f = if (unit == OVEN_F) x else Nutrition.cToF(x)
    val g = Nutrition.nearestGas(d, c)
    val main = if (unit == OVEN_C) Nutrition.show(f) + " °F" else Nutrition.show(c) + " °C"
    return Logic.Result(true, main, listOf("Nearest gas mark ${g.gas} (${g.c.toPlainString()} °C / ${g.f.toPlainString()} °F) · ${g.desc}"), "")
}
