package com.diegonmarcos.cloudme.health

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

private val zone: ZoneId get() = ZoneId.systemDefault()
private fun dayX(d: LocalDate) = d.toEpochDay().toDouble()
private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.US)

@Composable
fun WorkoutPage(e: HealthEnv) {
    val day = LocalDate.now().toString()
    HTitle("Structured workout plan", "Tick what you did today and log the load. Your log is stored on this phone and travels with the fleet backup.")
    e.content.workout.forEach { d ->
        HCard {
            HHead(d.label)
            d.exercises.forEach { ex ->
                val entry = e.user.workout.firstOrNull { it.date == day && it.day == d.id && it.exercise == ex.id }
                val last = e.user.workout.filter { it.day == d.id && it.exercise == ex.id && it.loadKg != null && it.date != day }.maxByOrNull { it.date }
                Tick("${ex.name}: ${ex.scheme}" + if (ex.note.isNotBlank()) " (${ex.note})" else "", entry?.done == true) { on ->
                    e.update { it.withWorkout(WorkoutEntry(day, d.id, ex.id, on, entry?.loadKg)) }
                }
                if (ex.load) {
                    var kg by remember(entry?.loadKg) { mutableStateOf(entry?.loadKg?.let { HealthFormulas.num(it) }.orEmpty()) }
                    Row {
                        NumField("Load (kg)", kg, { kg = it }, Modifier.weight(1f).padding(end = 8.dp))
                        Pill("Save load", kg.toDoubleOrNull() != null) { kg.toDoubleOrNull()?.let { v -> e.update { it.withWorkout(WorkoutEntry(day, d.id, ex.id, entry?.done == true, v)) } } }
                    }
                    last?.let { HText("Last logged ${it.date}: ${HealthFormulas.num(it.loadKg ?: 0.0)} kg") }
                }
            }
        }
    }
    val sessions = e.user.workout.filter { it.done }.map { it.date }.distinct().sortedDescending().take(5)
    HCard { HHead("Sessions you logged"); if (sessions.isEmpty()) HText("None yet."); sessions.forEach { HText(it, ink()) } }
    HHead("Recorded by your devices (7 days)")
    HcPanel(HealthHc.ACTIVITY, { c -> HealthHc.workouts(c, 7) }) { list ->
        if (list.isEmpty()) HEmpty("Health Connect holds no exercise sessions for the last 7 days.")
        list.forEach { w -> HCard { HRow(w.title, "${Duration.between(w.start, w.end).toMinutes()} min"); HText(w.start.atZone(zone).toLocalDate().format(dayFmt)) } }
    }
}

@Composable
fun ActivitiesPage(e: HealthEnv) {
    HTitle("Daily activities", "Steps and exercise from Health Connect.")
    HcPanel(HealthHc.ACTIVITY, { c -> HealthHc.steps(c, 7) to HealthHc.workouts(c, 7) }) { (steps, work) ->
        val daily = HealthSeries.dailySum(steps, zone)
        if (daily.isEmpty() && work.isEmpty()) HEmpty("Health Connect holds no steps or exercise for the last 7 days.")
        else {
            HCard {
                HHead("Steps, last 7 days")
                val todayN = daily.firstOrNull { it.day == LocalDate.now() }?.value ?: 0.0
                HRow("Today", "%.0f".format(Locale.US, todayN))
                if (daily.isNotEmpty()) {
                    HRow("Week total", "%.0f".format(Locale.US, daily.sumOf { it.value }))
                    HRow("Daily average", "%.0f".format(Locale.US, daily.map { it.value }.average()))
                    BarChart(daily.map { it.value }, accent())
                }
            }
            HCard {
                HHead("Exercise sessions")
                if (work.isEmpty()) HText("None recorded in the last 7 days.")
                work.forEach { w -> HRow("${w.start.atZone(zone).toLocalDate().format(dayFmt)}  ${w.title}", "${Duration.between(w.start, w.end).toMinutes()} min") }
                if (work.isNotEmpty()) HRow("Total training", "${work.sumOf { Duration.between(it.start, it.end).toMinutes() }} min")
            }
        }
    }
}

@Composable
fun BodyCompPage(e: HealthEnv) {
    val f = e.content.formulas
    HTitle("Body composition trends", "Weight and body fat from Health Connect, with the entries you saved on Anthropometrics.")
    HcPanel(HealthHc.BODY, { c -> HealthHc.weight(c, 90) to HealthHc.bodyFat(c, 90) }) { (w, bf) ->
        val mine = e.user.measurements.filter { it.massKg != null }
        if (w.isEmpty() && bf.isEmpty() && mine.isEmpty()) HEmpty("No weight or body fat in Health Connect for the last 90 days, and none saved on Anthropometrics.")
        if (w.isNotEmpty() || mine.isNotEmpty()) HCard {
            HHead("Weight (kg)")
            val hcPts = HealthSeries.dailyMean(w, zone).map { dayX(it.day) to it.value }
            val myPts = mine.map { dayX(java.time.Instant.ofEpochMilli(it.t).atZone(zone).toLocalDate()) to (it.massKg ?: 0.0) }
            LineChart(listOfNotNull(hcPts.takeIf { it.isNotEmpty() }?.let { ChartSeries(it, accent()) }, myPts.takeIf { it.isNotEmpty() }?.let { ChartSeries(it, Good) }))
            if (hcPts.size > 1) HRow("Change over the window", "%+.1f kg".format(Locale.US, hcPts.last().second - hcPts.first().second))
            if (myPts.isNotEmpty() && hcPts.isNotEmpty()) HText("Violet: Health Connect. Green: your saved entries.")
        }
        if (bf.isNotEmpty()) HCard {
            HHead("Body fat (%)")
            val pts = HealthSeries.dailyMean(bf, zone).map { dayX(it.day) to it.value }
            LineChart(listOf(ChartSeries(pts, Warn)))
            if (pts.size > 1) HRow("Change over the window", "%+.1f points".format(Locale.US, pts.last().second - pts.first().second))
        }
        val lastW = w.lastOrNull()?.value ?: mine.lastOrNull()?.massKg
        val lastBf = bf.lastOrNull()?.value ?: mine.lastOrNull { it.bfPct != null }?.bfPct
        val h = e.user.latestMeasure()?.heightCm
        val a = HealthFormulas.anthro(f, lastW, h, lastBf, e.user.activity)
        if (a != null) HCard { HHead("Derived from the latest values"); HRow("LBM", "%.1f kg".format(Locale.US, a.lbm)); HRow("FFMI", "%.1f".format(Locale.US, a.ffmi)) }
    }
}

@Composable
fun HrPage(e: HealthEnv) {
    var days by remember { mutableLongStateOf(7L) }
    HTitle("Heart rate and HRV", "Resting heart rate and RMSSD heart rate variability from Health Connect.")
    Choice(listOf("7" to "7 days", "30" to "30 days"), days.toString()) { days = it.toLong() }
    HcPanel(HealthHc.HEART, { c -> HealthHc.restingHr(c, 30) to HealthHc.hrv(c, 30) }) { (hrAll, hrvAll) ->
        val since = LocalDate.now().minusDays(days - 1)
        val hr = HealthSeries.dailyMean(hrAll, zone).filter { !it.day.isBefore(since) }
        val hrv = HealthSeries.dailyMean(hrvAll, zone).filter { !it.day.isBefore(since) }
        if (hr.isEmpty() && hrv.isEmpty()) HEmpty("Health Connect holds no resting heart rate or HRV for this window.")
        if (hr.isNotEmpty()) HCard {
            HHead("Resting heart rate (bpm)")
            LineChart(listOf(ChartSeries(hr.map { dayX(it.day) to it.value }, Bad)))
            HRow("Average", "%.0f".format(Locale.US, hr.map { it.value }.average()))
            HRow("Lowest / highest", "%.0f / %.0f".format(Locale.US, hr.minOf { it.value }, hr.maxOf { it.value }))
        }
        if (hrv.isNotEmpty()) HCard {
            HHead("HRV RMSSD (ms)")
            LineChart(listOf(ChartSeries(hrv.map { dayX(it.day) to it.value }, accent())))
            HRow("Average", "%.0f".format(Locale.US, hrv.map { it.value }.average()))
            val base = HealthSeries.dailyMean(hrvAll, zone)
            if (base.size >= 8) {
                val recent = base.takeLast(7).map { it.value }.average()
                val all = base.map { it.value }.average()
                HRow("Last 7 days vs 30-day baseline", "%+.0f %%".format(Locale.US, (recent / all - 1) * 100))
            }
        }
    }
}

@Composable
fun SleepPage(e: HealthEnv) {
    val labels = e.content.formulas.let { mapOf("deep" to "Deep", "rem" to "REM", "light" to "Light", "awake" to "Awake") }
    var target by remember(e.user.sleepTargetH) { mutableStateOf(e.user.sleepTargetH?.let { HealthFormulas.num(it) }.orEmpty()) }
    HTitle("Sleep architecture", "Nights and stages from Health Connect.")
    HcPanel(HealthHc.SLEEP, { c -> HealthHc.sleep(c, 7, zone) }) { nights ->
        if (nights.isEmpty()) HEmpty("Health Connect holds no sleep sessions for the last 7 days.")
        else {
            HCard {
                HHead("Hours asleep, last nights")
                BarChart(nights.map { it.totalMin / 60.0 }, accent())
                HRow("Average", "%.1f h".format(Locale.US, nights.map { it.totalMin }.average() / 60))
            }
            nights.last().let { n ->
                HCard {
                    HHead("Last night (${n.day.format(dayFmt)})")
                    if (n.stageMin.isEmpty()) HText("The sleep app did not record stages for this night.")
                    labels.forEach { (k, label) -> n.stageMin[k]?.let { HRow(label, "%.0f min".format(Locale.US, it)) } }
                    HRow("Total asleep", "%.1f h".format(Locale.US, n.totalMin / 60))
                }
            }
            HCard {
                HHead("Sleep debt")
                NumField("Target (hours per night)", target, { target = it })
                Pill("Save target", target.toDoubleOrNull() != null) { e.update { it.copy(sleepTargetH = target.toDoubleOrNull()) } }
                e.user.sleepTargetH?.let { t -> HRow("Debt over these nights", "%.1f h".format(Locale.US, HealthSeries.sleepDebtH(nights, t))) }
                    ?: HText("Set a target to see the debt. It is your number, not a default.")
            }
        }
    }
}

/** One summary line per tracker that Health Connect can answer right now, for the dossier; a source that cannot answer says why. */
suspend fun trackerSummary(ctx: android.content.Context): List<String> {
    fun <T> line(name: String, s: HcState<T>, ok: (T) -> String?): String = when (s) {
        is HcState.Ready -> ok(s.data) ?: "$name: no data in the last 30 days"
        is HcState.NeedPermission -> "$name: Health Connect permission not granted"
        is HcState.Unavailable -> "$name: ${s.why}"
        HcState.Loading -> "$name: not read"
    }
    val out = mutableListOf<String>()
    out += line("Weight", HealthHc.guarded(ctx, setOf(HealthHc.perm(androidx.health.connect.client.records.WeightRecord::class))) { HealthHc.weight(it, 30) }) { r ->
        r.lastOrNull()?.let { "Weight: %.1f kg (latest), %+.1f kg over 30 days".format(Locale.US, it.value, it.value - r.first().value) }
    }
    out += line("Resting heart rate", HealthHc.guarded(ctx, setOf(HealthHc.perm(androidx.health.connect.client.records.RestingHeartRateRecord::class))) { HealthHc.restingHr(it, 30) }) { r ->
        if (r.isEmpty()) null else "Resting heart rate: %.0f bpm average over 30 days".format(Locale.US, r.map { it.value }.average())
    }
    out += line("HRV", HealthHc.guarded(ctx, setOf(HealthHc.perm(androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord::class))) { HealthHc.hrv(it, 30) }) { r ->
        if (r.isEmpty()) null else "HRV RMSSD: %.0f ms average over 30 days".format(Locale.US, r.map { it.value }.average())
    }
    out += line("Sleep", HealthHc.guarded(ctx, HealthHc.SLEEP) { HealthHc.sleep(it, 30, zone) }) { n ->
        if (n.isEmpty()) null else "Sleep: %.1f h average over %d nights".format(Locale.US, n.map { it.totalMin }.average() / 60, n.size)
    }
    out += line("Steps", HealthHc.guarded(ctx, setOf(HealthHc.perm(androidx.health.connect.client.records.StepsRecord::class))) { HealthSeries.dailySum(HealthHc.steps(it, 30), zone) }) { d ->
        if (d.isEmpty()) null else "Steps: %.0f per day average over 30 days".format(Locale.US, d.map { it.value }.average())
    }
    return out
}

@Composable
fun DossierPage(e: HealthEnv) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val include = e.user.dossierSections ?: HealthDossier.ALL_SECTIONS.toSet()
    var notes by remember(e.user.dossierNotes) { mutableStateOf(e.user.dossierNotes) }
    var msg by remember { mutableStateOf("") }
    val tracker by produceState(emptyList<String>()) { value = trackerSummary(ctx) }
    val sections = HealthDossier.build(e.content, e.user, include, tracker)
    HTitle("Physician dossier", "A clinical summary for medical review, exported as a PDF.")
    HCard {
        HHead("Include")
        HealthDossier.ALL_SECTIONS.forEach { id ->
            Tick(HealthDossier.TITLES.getValue(id), id in include) { on -> e.update { it.copy(dossierSections = if (on) include + id else include - id) } }
        }
        TextBox("Notes for the physician", notes, { notes = it })
        Pill("Save notes", notes != e.user.dossierNotes) { e.update { it.copy(dossierNotes = notes) } }
    }
    sections.forEach { s -> HCard { HHead(s.title); s.lines.forEach { HText(it, ink()) } } }
    HCard {
        Pill("Export PDF", true) {
            scope.launch {
                val ok = HealthDossier.export(ctx, "Physician dossier ${LocalDate.now()}", HealthDossier.build(e.content, e.user, include, tracker))
                msg = if (ok) "" else "No app on this phone can open a PDF. Install Cloud Drive."
            }
        }
        if (msg.isNotBlank()) HText(msg, Bad)
        HText("The PDF opens in the fleet's PDF reader (Cloud Drive), where it can be printed or shared.")
    }
}
