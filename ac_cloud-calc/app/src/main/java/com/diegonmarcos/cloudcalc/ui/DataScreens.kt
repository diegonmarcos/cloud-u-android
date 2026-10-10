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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.diegonmarcos.cloudcalc.DataUnits
import com.diegonmarcos.cloudcalc.Declarations
import com.diegonmarcos.cloudcalc.Logic
import com.diegonmarcos.cloudcalc.R

// ── data_converter: Network / Data (rates, sizes, transfer time) ───────────────────────────────
//
// The Units/Currency converter's look - category chips, value, from ⇄ to pickers with the favourites
// pinned first, the result, = to History - over units the mode declares itself, converted in exact
// decimals (DataUnits) instead of by the engine, so 100 Mbit/s reads 12.5 MB/s to its last digit.

@Composable
internal fun DataConverterMode(mode: Declarations.Mode) {
    var category by rememberSaveable(mode.id) { mutableStateOf(mode.defaults["category"] ?: mode.categories.firstOrNull().orEmpty()) }
    val value = rememberSaveable(mode.id) { mutableStateOf(mode.defaults["value"] ?: "1") }
    val from = rememberSaveable(mode.id) { mutableStateOf(mode.defaults["from"].orEmpty()) }
    val to = rememberSaveable(mode.id) { mutableStateOf(mode.defaults["to"].orEmpty()) }
    // The transfer chip's inputs live here, so leaving the chip and coming back keeps them.
    val t = mode.transfer
    val size = rememberSaveable(mode.id) { mutableStateOf(t?.sizeValue.orEmpty()) }
    val sizeUnit = rememberSaveable(mode.id) { mutableStateOf(t?.sizeUnit.orEmpty()) }
    val rate = rememberSaveable(mode.id) { mutableStateOf(t?.rateValue.orEmpty()) }
    val rateUnit = rememberSaveable(mode.id) { mutableStateOf(t?.rateUnit.orEmpty()) }
    val set = mode.unitSets.firstOrNull { it.category == category }
    // A category whose units do not hold the current pair opens on its declared one (as the engine converter does).
    LaunchedEffect(category) {
        if (set != null && (set.unit(from.value) == null || set.unit(to.value) == null)) { from.value = set.from; to.value = set.to }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(CalcMetrics.gutter)) {
        if (mode.categories.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
                items(mode.categories, key = { it }) { c -> FilterChip(selected = c == category, onClick = { category = c }, label = { Text(c) }) }
            }
        }
        Text(
            stringResource(R.string.data_case_hint), Modifier.padding(vertical = CalcMetrics.hairline).testTag(CalcTags.DATA_HINT),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            t != null && category == t.category -> TransferBody(mode, t, size, sizeUnit, rate, rateUnit)
            set != null -> PairBody(mode, set, value, from, to)
        }
    }
}

/** Value, from ⇄ to, the exact answer. */
@Composable
private fun PairBody(mode: Declarations.Mode, set: Declarations.UnitSet, value: MutableState<String>, from: MutableState<String>, to: MutableState<String>) {
    val state = LocalCalcState.current
    val v = value.value
    val fu = set.unit(from.value) ?: set.unit(set.from)
    val tu = set.unit(to.value) ?: set.unit(set.to)
    val notNumber = stringResource(R.string.data_not_a_number, v.trim())
    val x = DataUnits.parse(v)
    val result = when {
        v.isBlank() || fu == null || tu == null -> null
        x == null -> Logic.Result(false, "", emptyList(), notNumber)
        else -> Logic.Result(true, DataUnits.plain(DataUnits.convert(x, fu, tu)) + " " + tu.name, emptyList(), "")
    }
    val keep: () -> Unit = {
        val res = result
        if (res != null && res.ok && fu != null && tu != null) state.remember(Logic.Entry(mode.id, "${v.trim()} ${fu.name} to ${tu.name}", res.text), historyMax())
    }
    OutlinedTextField(
        value = v, onValueChange = { value.value = it },
        modifier = Modifier.fillMaxWidth().testTag(CalcTags.INPUT),
        label = { Text(stringResource(R.string.value)) },
        textStyle = MaterialTheme.typography.titleMedium,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { keep() }),
    )
    val units = pickerItems(set)
    Row(Modifier.fillMaxWidth().padding(vertical = CalcMetrics.gap), horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
        UnitPicker(units, fu?.name.orEmpty(), mode.favourites, Modifier.weight(1f), text = ::label) { from.value = it }
        TextButton(
            onClick = { val f = fu?.name.orEmpty(); from.value = tu?.name.orEmpty(); to.value = f },
            modifier = Modifier.height(CalcMetrics.compactHeight).testTag(CalcTags.CONVERT_SWAP), contentPadding = PaddingValues(horizontal = CalcMetrics.gap),
        ) { Text("⇄") }
        UnitPicker(units, tu?.name.orEmpty(), mode.favourites, Modifier.weight(1f), text = ::label) { to.value = it }
    }
    ResultBlock(result)
    KeepRow(result, keep)
}

/** "How long to transfer": a size over a rate, as d / h / min / s with the exact seconds under it. */
@Composable
private fun TransferBody(
    mode: Declarations.Mode, t: Declarations.Transfer,
    size: MutableState<String>, sizeUnit: MutableState<String>, rate: MutableState<String>, rateUnit: MutableState<String>,
) {
    val state = LocalCalcState.current
    val sizes = mode.unitSets.firstOrNull { it.category == t.size }
    val rates = mode.unitSets.firstOrNull { it.category == t.rate }
    if (sizes == null || rates == null) return
    val su = sizes.unit(sizeUnit.value) ?: sizes.units.first()
    val ru = rates.unit(rateUnit.value) ?: rates.units.first()
    val sv = size.value
    val rv = rate.value
    val notSize = stringResource(R.string.data_not_a_number, sv.trim())
    val notRate = stringResource(R.string.data_not_a_number, rv.trim())
    val rateZero = stringResource(R.string.data_rate_above_zero)
    val s = DataUnits.parse(sv)
    val r = DataUnits.parse(rv)
    val secs = if (s != null && r != null) DataUnits.seconds(s, su, r, ru) else null
    val result = when {
        sv.isBlank() || rv.isBlank() -> null
        s == null -> Logic.Result(false, "", emptyList(), notSize)
        r == null -> Logic.Result(false, "", emptyList(), notRate)
        secs == null -> Logic.Result(false, "", emptyList(), rateZero)
        else -> Logic.Result(true, DataUnits.duration(secs), listOf(DataUnits.secondsLine(secs)).filter { it.isNotEmpty() }, "")
    }
    val keep: () -> Unit = {
        val res = result
        if (res != null && res.ok) {
            val text = res.text + res.messages.firstOrNull()?.let { " ($it)" }.orEmpty()
            state.remember(Logic.Entry(mode.id, "${sv.trim()} ${su.name} at ${rv.trim()} ${ru.name}", text), historyMax())
        }
    }
    TransferLine(mode, t.size, size, CalcTags.TRANSFER_SIZE, sizes, su.name, keep) { sizeUnit.value = it }
    TransferLine(mode, t.rate, rate, CalcTags.TRANSFER_RATE, rates, ru.name, keep) { rateUnit.value = it }
    ResultBlock(result)
    KeepRow(result, keep)
}

/** One input of the transfer chip: its number and its unit, on one row. */
@Composable
private fun TransferLine(
    mode: Declarations.Mode, title: String, value: MutableState<String>, tag: String,
    set: Declarations.UnitSet, unit: String, keep: () -> Unit, onUnit: (String) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = CalcMetrics.hairline), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
        OutlinedTextField(
            value = value.value, onValueChange = { value.value = it }, modifier = Modifier.weight(1f).testTag(tag),
            label = { Text(title) }, textStyle = MaterialTheme.typography.titleMedium, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { keep() }),
        )
        UnitPicker(pickerItems(set), unit, mode.favourites, Modifier.weight(1f), text = ::label, onPick = onUnit)
    }
}

/** = keeps the answer in History, compact and on the right, as in the other converters. */
@Composable
private fun KeepRow(result: Logic.Result?, keep: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f))
        if (result?.ok == true) FilledTonalButton(
            onClick = { keep() },
            modifier = Modifier.height(CalcMetrics.compactHeight).testTag(CalcTags.CONVERT_EQ), contentPadding = PaddingValues(horizontal = CalcMetrics.gap),
        ) { Text("=") }
    }
}

private fun pickerItems(set: Declarations.UnitSet): List<Logic.Item> = set.units.map { Logic.Item(it.name, it.label, "unit", set.category) }

/** A declared unit's label already carries its symbol and alias ("Mbit/s (Mbps)"). */
private fun label(item: Logic.Item?, name: String): String = item?.title ?: name
