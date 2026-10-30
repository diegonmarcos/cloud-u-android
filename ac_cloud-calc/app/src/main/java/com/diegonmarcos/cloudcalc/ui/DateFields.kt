package com.diegonmarcos.cloudcalc.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.diegonmarcos.cloudcalc.Declarations
import com.diegonmarcos.cloudcalc.RangeSelection
import com.diegonmarcos.cloudcalc.WorkingDays
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/** #1329 test tags of the date widgets (the day cell carries its ISO date). */
object DateTags {
    fun day(d: LocalDate) = "calc_day_$d"
    const val PREV = "calc_cal_prev"
    const val NEXT = "calc_cal_next"
    const val TOGGLE = "calc_cal_toggle"
    fun choice(id: String) = "calc_choice_$id"
    fun toggle(id: String) = "calc_switch_$id"
}

/** A choice field: the button shows the picked option's label, the menu lists them all. */
@Composable
internal fun ChoiceField(label: String, options: List<Declarations.Option>, value: String, tag: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(vertical = CalcMetrics.small)) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().testTag(tag)) {
            Text(label + ": " + (options.firstOrNull { it.value == value }?.label ?: value), modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(o.label) }, onClick = { onPick(o.value); open = false }, modifier = Modifier.testTag("${tag}_${o.value}")) }
        }
    }
}

/** A toggle field: "true" / "false". */
@Composable
internal fun ToggleField(label: String, value: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = CalcMetrics.small), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange, modifier = Modifier.testTag(tag))
    }
}

/**
 * A date field: the typed ISO text and, under it, a tappable month calendar (opened by the toggle, or at once when [open]).
 * [rangeEnd] != null makes it a RANGE picker over this field ([start]) and the end field: one calendar for both, the text of both
 * kept in step with the taps and the taps with the text. Weekends and holidays are tinted, today is ringed, the range highlighted.
 */
@Composable
internal fun DateField(
    label: String, text: String, onText: (String) -> Unit, tag: String,
    rangeEnd: Pair<String, String>?, onEndText: (String) -> Unit, endTag: String,
    open: Boolean, calendar: (LocalDate) -> Pair<Boolean, String?>,
) {
    var shown by remember { mutableStateOf(open) }
    // The first tap of a range leaves it waiting for its end: the start it was set to.
    var waiting by remember { mutableStateOf<LocalDate?>(null) }
    val from = WorkingDays.date(text)
    val to = rangeEnd?.let { WorkingDays.date(it.first) }
    OutlinedTextField(
        value = text, onValueChange = { onText(it); waiting = null }, label = { Text(label) },
        modifier = Modifier.fillMaxWidth().padding(vertical = CalcMetrics.small).testTag(tag), singleLine = true,
        trailingIcon = { TextButton(onClick = { shown = !shown }, modifier = Modifier.testTag(DateTags.TOGGLE)) { Text(if (shown) "▴" else "▾") } },
    )
    if (rangeEnd != null) {
        OutlinedTextField(
            value = rangeEnd.first, onValueChange = { onEndText(it); waiting = null }, label = { Text(rangeEnd.second) },
            modifier = Modifier.fillMaxWidth().padding(vertical = CalcMetrics.small).testTag(endTag), singleLine = true,
        )
    }
    if (!shown) return
    // What is highlighted: the awaiting start alone, else the typed (or tapped) range, else the single date.
    val selection = when {
        waiting != null && waiting == from -> RangeSelection(from, null)
        rangeEnd != null -> RangeSelection(from, to)
        else -> RangeSelection(from, from)
    }
    CalendarGrid(
        selection = selection, calendar = calendar, tag = tag,
        onTap = { day ->
            if (rangeEnd == null) { onText(day.toString()); return@CalendarGrid }
            val next = (if (waiting != null && waiting == from) RangeSelection(from, null) else RangeSelection(null, null)).tap(day)
            if (next.pending) {
                // First tap: a one-day range, so the results stay live while the end is awaited.
                waiting = day; onText(day.toString()); onEndText(day.toString())
            } else {
                waiting = null; onText(next.start.toString()); onEndText(next.end.toString())
            }
        },
    )
}

/** The month grid. [calendar] answers (is weekend, holiday name) for a day. */
@Composable
internal fun CalendarGrid(selection: RangeSelection, calendar: (LocalDate) -> Pair<Boolean, String?>, tag: String, onTap: (LocalDate) -> Unit) {
    val today = remember { LocalDate.now() }
    var month by remember { mutableStateOf(YearMonth.from(selection.start ?: today)) }
    val first = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(vertical = CalcMetrics.small)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { month = month.minusMonths(1) }, modifier = Modifier.testTag(DateTags.PREV)) { Text("‹") }
            Text(month.month.getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + month.year, style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { month = month.plusMonths(1) }, modifier = Modifier.testTag(DateTags.NEXT)) { Text("›") }
        }
        Row(Modifier.fillMaxWidth()) {
            (0 until 7).map { first.plus(it.toLong()) }.forEach { dow ->
                Text(dow.getDisplayName(TextStyle.NARROW, Locale.getDefault()), Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall)
            }
        }
        val lead = (month.atDay(1).dayOfWeek.value - first.value + 7) % 7
        val cells = lead + month.lengthOfMonth()
        (0 until (cells + 6) / 7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                (0 until 7).forEach { col ->
                    val n = week * 7 + col - lead + 1
                    if (n < 1 || n > month.lengthOfMonth()) { Box(Modifier.weight(1f).aspectRatio(1f)); return@forEach }
                    val day = month.atDay(n)
                    val (weekend, holiday) = calendar(day)
                    val edge = day == selection.start || day == selection.end
                    val inRange = selection.contains(day)
                    val fill: Color = when {
                        edge -> scheme.primary
                        inRange -> scheme.primaryContainer
                        holiday != null -> scheme.tertiaryContainer
                        weekend -> scheme.surfaceVariant
                        else -> Color.Transparent
                    }
                    val ink: Color = when {
                        edge -> scheme.onPrimary
                        holiday != null && !inRange -> scheme.onTertiaryContainer
                        holiday != null -> scheme.tertiary
                        weekend -> scheme.onSurfaceVariant
                        else -> scheme.onSurface
                    }
                    Box(
                        Modifier.weight(1f).aspectRatio(1f).padding(CalcMetrics.hairline).clip(CircleShape).background(fill)
                            .let { if (day == today) it.border(BorderStroke(CalcMetrics.hairline * 2, scheme.primary), CircleShape) else it }
                            .clickable { onTap(day) }.testTag(DateTags.day(day)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(n.toString(), color = ink, style = MaterialTheme.typography.bodyMedium, fontWeight = if (holiday != null) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
        val names = (1..month.lengthOfMonth()).mapNotNull { d -> calendar(month.atDay(d)).second?.let { "$d $it" } }
        Text(
            if (names.isEmpty()) "▫ weekend   ● holiday   ◯ today" else names.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = CalcMetrics.small),
        )
    }
}

/** The weekend days of a form's values, by its bound weekend field (Sat + Sun when it has none). */
internal fun weekendFor(bind: Map<String, String>, values: Map<String, String>): Set<DayOfWeek> =
    WorkingDays.weekendOf(values[bind["weekend"].orEmpty()] ?: "SAT,SUN")
