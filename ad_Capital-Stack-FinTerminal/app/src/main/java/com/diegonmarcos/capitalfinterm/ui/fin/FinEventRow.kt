package com.diegonmarcos.capitalfinterm.ui.fin

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.diegonmarcos.capitalfinterm.FinFormat
import com.diegonmarcos.capitalfinterm.FinLogic
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/** One calendar event as the row draws it. [ts] is ISO (date, or date + time, UTC). */
data class FinEvent(val id: String, val ts: String, val country: String, val title: String, val importance: Int, val afp: FinLogic.Afp, val source: String = "")

/** Importance as 3 dots, 6dp, filled = importance in imp.low / imp.med / imp.high; unfilled in hairline (spec 2.6, checklist 8). */
@Composable
fun FinImportance(n: Int) {
    val p = LocalFinPalette.current
    Row(Modifier.width(28.dp).testTag("fin:imp:$n"), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(3) { i -> Box(Modifier.size(6.dp).clip(CircleShape).background(if (i < n) p.importance(n) else p.hairline)) }
    }
}

/** The 28dp day header of a calendar group: `THU 05 NOV`, surfaceRaised, full bleed. */
@Composable
fun FinDayHeader(isoDate: String) {
    val p = LocalFinPalette.current
    Box(Modifier.fillMaxWidth().height(FinDim.dayHeader).background(p.surfaceRaised).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
        Text(FinLogic.dayHeader(isoDate).uppercase(), color = p.text2, style = FinType.label)
    }
}

/**
 * Economic-calendar row (spec 2.6, tag fin:event:<id>): time | flag + country | importance dots | event; a second line A / F / P
 * when values exist, the actual coloured beat/miss by the event's better-when. Upcoming within 60 min: time in accent. Tap expands.
 */
@Composable
fun FinEventRow(e: FinEvent, now: java.time.LocalDateTime) {
    val p = LocalFinPalette.current
    var open by rememberSaveable(e.id) { mutableStateOf(false) }
    val hasValues = e.afp.actual != null || e.afp.forecast != null || e.afp.previous != null
    val time = if (e.ts.length > 10) e.ts.substring(11).take(5) else "all day"
    val soon = runCatching {
        val at = java.time.LocalDateTime.parse(e.ts.take(16))
        e.afp.actual == null && !at.isBefore(now) && at.isBefore(now.plusMinutes(60))
    }.getOrDefault(false)
    Column(Modifier.fillMaxWidth().animateContentSize().clickable { open = !open }.testTag("fin:event:${e.id}")) {
        Row(Modifier.fillMaxWidth().height(if (hasValues) 28.dp else FinDim.rowCalendar).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(time, Modifier.width(44.dp), color = if (soon) p.accent else p.text2, style = FinType.numSmall, maxLines = 1)
            Row(Modifier.width(48.dp), verticalAlignment = Alignment.CenterVertically) {
                FinLogic.flag(e.country)?.let { Text(it, style = FinType.body); Spacer(Modifier.width(3.dp)) }
                Text(e.country.uppercase().take(3), color = p.text2, style = FinType.caption, maxLines = 1)
            }
            FinImportance(e.importance)
            Text(e.title, Modifier.weight(1f), color = p.text, style = FinType.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (hasValues) {
            Row(Modifier.fillMaxWidth().height(20.dp).padding(start = 12.dp + 44.dp + 48.dp + 28.dp, end = 12.dp), verticalAlignment = Alignment.Top) {
                val beat = FinFormat.beatMiss(e.afp.actual, e.afp.forecast, FinFormat.betterHigher(e.title))
                AfpCell("A", e.afp.actual, beat?.let { p.dir(it) } ?: p.text)
                AfpCell("F", e.afp.forecast, p.text2)
                AfpCell("P", e.afp.previous, p.text2)
            }
        }
        if (open) {
            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(start = 12.dp + 44.dp, end = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(listOf(e.source, e.ts.replace('T', ' ') + " UTC").filter { it.isNotBlank() }.joinToString(" · "), color = p.text3, style = FinType.caption)
            }
        }
    }
}

@Composable
private fun AfpCell(label: String, v: String?, ink: androidx.compose.ui.graphics.Color) {
    val p = LocalFinPalette.current
    Row(Modifier.width(76.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = p.text3, style = FinType.label)
        Spacer(Modifier.width(4.dp))
        Text(v ?: FinFormat.DASH, color = if (v == null) p.text3 else ink, style = FinType.numSmall, maxLines = 1)
    }
}

/** A day-grouped event list with the accent "now" line inside today's group (spec 2.6, 3.4). */
@Composable
fun FinEventList(events: List<FinEvent>, limit: Int = Int.MAX_VALUE) {
    val p = LocalFinPalette.current
    val now = java.time.LocalDateTime.now(ZoneOffset.UTC)
    val today = LocalDate.now(ZoneOffset.UTC).toString()
    Column(Modifier.fillMaxWidth()) {
        events.take(limit).groupBy { it.ts.take(10) }.forEach { (day, list) ->
            FinDayHeader(day)
            var nowDrawn = day != today
            list.forEachIndexed { i, e ->
                if (!nowDrawn && e.ts.length > 10 && e.ts.take(16) > now.toString().take(16)) {
                    nowDrawn = true
                    Row(Modifier.fillMaxWidth().height(16.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("now " + LocalTime.now(ZoneOffset.UTC).toString().take(5), color = p.accent, style = FinType.numSmall)
                        Box(Modifier.padding(start = 6.dp).weight(1f).height(2.dp).background(p.accent))
                    }
                }
                FinEventRow(e, now)
                if (i < list.size - 1) Box(Modifier.padding(start = 12.dp).fillMaxWidth().height(1.dp).background(p.hairline))
            }
        }
    }
}

/** Day header + 3 skeleton rows. */
@Composable
fun FinEventSkeleton() {
    val p = LocalFinPalette.current
    Column {
        Box(Modifier.fillMaxWidth().height(FinDim.dayHeader).background(p.surfaceRaised))
        repeat(3) {
            Row(Modifier.fillMaxWidth().height(FinDim.rowCalendar).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                FinSkeleton(32.dp, 12.dp); Spacer(Modifier.width(12.dp)); FinSkeleton(36.dp, 12.dp); Spacer(Modifier.width(12.dp)); FinSkeleton(null, 12.dp)
            }
        }
    }
}
