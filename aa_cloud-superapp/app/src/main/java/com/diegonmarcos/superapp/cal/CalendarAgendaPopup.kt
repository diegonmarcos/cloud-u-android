package com.diegonmarcos.superapp.cal

import android.content.Context
import android.os.SystemClock
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.ui.KitBubble
import com.diegonmarcos.superapp.ui.KitPopup
import kotlinx.coroutines.delay
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Calendar agenda popup — shown when the user taps the date+time
 * center cell of the status strip. Renders the same 7-day list shape
 * as CalendarAgendaFragment (today + next 6 days), with the same
 * placeholder rows since libs:cal slice D (CalDAV) hasn't landed yet.
 *
 * Scrollable so the bubble stays compact even though the content has
 * a fixed 7-row layout. Today's row is highlighted (slightly brighter
 * background, bold header) so the user can read "what's next" at a
 * glance.
 *
 * Mirrors the visual shape of BatteryEstimatePopup / NetworkInfoPopup
 * / SystemInfoPopup so the four strip popups read as one family.
 */
object CalendarAgendaPopup {

    // Stopwatch state — kept in static fields so the timer keeps
    // running while the popup is closed. SystemClock.elapsedRealtime
    // is monotonic across sleeps so the elapsed value is accurate
    // even if the user dismisses the popup, locks the screen, and
    // re-opens it minutes later.
    @Volatile private var stopwatchRunning: Boolean = false
    @Volatile private var stopwatchStartElapsedMs: Long = 0L
    @Volatile private var stopwatchAccumulatedMs: Long = 0L

    private fun stopwatchCurrentMs(): Long =
        if (stopwatchRunning)
            stopwatchAccumulatedMs + (SystemClock.elapsedRealtime() - stopwatchStartElapsedMs)
        else
            stopwatchAccumulatedMs

    fun show(ctx: Context, anchor: View) {
        // Horizontally SCREEN-centered, dropped from the status strip (the drop matches Battery/SysInfo exactly).
        KitPopup.show(anchor, KitPopup.Align.CENTER) { CalendarBubble() }
    }

    /** Under a Compose anchor whose window bounds are [rect]. */
    fun show(host: View, rect: androidx.compose.ui.unit.IntRect) {
        KitPopup.show(host, rect, KitPopup.Align.CENTER) { CalendarBubble() }
    }

    private val MONO = FontFamily.Monospace
    private val LILAC = Color(0xFFE9D8FD)

    /** The bubble itself, split from [show] so it can be drawn without a window. */
    @Composable
    internal fun CalendarBubble() {
        var agenda by remember { mutableStateOf(true) }
        KitBubble(Modifier.width(260.dp).testTag("cal:bubble")) {
            // Headline row — left: label + today's date; right: the stopwatch.
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Calendar", color = Color(0xAAFFFFFF), fontSize = 11.sp, fontFamily = MONO)
                    Text(SimpleDateFormat("EEEE  d MMM yyyy", Locale.getDefault()).format(Calendar.getInstance().time),
                        color = Color.White, fontSize = 16.sp, fontFamily = MONO, fontWeight = FontWeight.Bold)
                }
                Stopwatch()
            }
            Spacer(Modifier.height(8.dp))
            // Two views switched by the tab row: "Agenda" (7-day list) and "Calendar" (this month's grid).
            Row(Modifier.fillMaxWidth()) {
                Tab("Agenda", agenda, Modifier.weight(1f)) { agenda = true }
                Tab("Calendar", !agenda, Modifier.weight(1f)) { agenda = false }
            }
            Spacer(Modifier.height(8.dp))
            if (agenda) AgendaView() else MonthView()
        }
    }

    /** One pill-less tab label: selected = lilac, bold, a faint lilac plate; unselected = dim. */
    @Composable
    private fun Tab(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
        Text(text, fontSize = 13.sp, fontFamily = MONO, textAlign = TextAlign.Center,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) LILAC else Color(0x77FFFFFF),
            modifier = modifier.background(if (selected) Color(0x33E9D8FD) else Color(0x11FFFFFF))
                .clickable(onClick = onClick).padding(6.dp).testTag("cal:tab:$text"))
    }

    /** Agenda tab content: the scrollable 7-day list (today + next 6 days), with placeholder rows until libs:cal slice D
     *  (CalDAV) lands. Capped at 300dp so the bubble stays compact. */
    @Composable
    private fun AgendaView() {
        val dayHeaderFmt = remember { SimpleDateFormat("EEE  d MMM", Locale.getDefault()) }
        val days = remember {
            val cal = Calendar.getInstance()
            (0 until 7).map { i -> dayHeaderFmt.format(cal.time).also { cal.add(Calendar.DAY_OF_YEAR, 1) }.let { if (i == 0) "Today · $it" else it } }
        }
        Column(Modifier.fillMaxWidth().height(300.dp).verticalScroll(rememberScrollState()).testTag("cal:agenda")) {
            days.forEachIndexed { i, label ->
                Column(Modifier.fillMaxWidth().padding(top = 4.dp).background(if (i == 0) Color(0x441A0033) else Color(0x221A0033)).padding(6.dp)) {
                    Text(label, color = if (i == 0) LILAC else Color(0xCCFFFFFF), fontSize = 13.sp, fontFamily = MONO,
                        fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Normal)
                    Text("no events", color = Color(0x77FFFFFF), fontSize = 11.sp, fontFamily = MONO, modifier = Modifier.padding(top = 2.dp))
                }
            }
            Text("CalDAV via libs:cal lands next; until then this is a placeholder. Same source as section:cal/agenda.",
                color = Color(0x55FFFFFF), fontSize = 10.sp, fontFamily = MONO,
                modifier = Modifier.padding(start = 2.dp, top = 10.dp, end = 2.dp, bottom = 2.dp))
        }
    }

    /** Calendar tab content: this month as a 7-column grid. Weekday header (locale first-day-of-week) + week rows; today's
     *  cell is a lilac disc. Days outside the month render as empty leading/trailing cells. */
    @Composable
    private fun MonthView() {
        val now = remember { Calendar.getInstance() }
        val today = now.get(Calendar.DAY_OF_MONTH)
        val firstDow = now.firstDayOfWeek          // locale: 1=Sun..7=Sat
        val daysInMonth = now.getActualMaximum(Calendar.DAY_OF_MONTH)
        val firstWeekday = remember { Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }.get(Calendar.DAY_OF_WEEK) }
        val leadingBlanks = ((firstWeekday - firstDow) + 7) % 7
        val rows = (leadingBlanks + daysInMonth + 6) / 7
        val shortWeekdays = remember { DateFormatSymbols(Locale.getDefault()).shortWeekdays } // [_, Sun..Sat]
        Column(Modifier.fillMaxWidth().testTag("cal:month")) {
            Text(SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(now.time), color = LILAC, fontSize = 13.sp,
                fontFamily = MONO, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
            Row(Modifier.fillMaxWidth()) {
                for (i in 0 until 7) {
                    val dow = ((firstDow - 1 + i) % 7) + 1   // Calendar weekday 1..7
                    Text(shortWeekdays.getOrNull(dow)?.take(1) ?: "", color = Color(0x88FFFFFF), fontSize = 11.sp, fontFamily = MONO,
                        fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f).padding(vertical = 3.dp))
                }
            }
            for (r in 0 until rows) {
                Row(Modifier.fillMaxWidth()) {
                    for (c in 0 until 7) {
                        val day = r * 7 + c - leadingBlanks + 1
                        val real = day in 1..daysInMonth
                        val isToday = real && day == today
                        Text(if (real) day.toString() else "", fontSize = 12.sp, fontFamily = MONO, textAlign = TextAlign.Center,
                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                            color = if (isToday) Color(0xFF111111) else Color(0xDDFFFFFF),
                            modifier = Modifier.weight(1f).padding(2.dp)
                                .background(if (isToday) LILAC else if (real) Color(0x221A0033) else Color.Transparent,
                                    if (isToday) CircleShape else androidx.compose.ui.graphics.RectangleShape)
                                .padding(vertical = 5.dp).let { if (isToday) it.testTag("cal:today") else it })
                    }
                }
            }
            Text("CalDAV event dots via libs:cal land next; the grid is the structure for now.", color = Color(0x55FFFFFF),
                fontSize = 10.sp, fontFamily = MONO, modifier = Modifier.padding(start = 2.dp, top = 10.dp, end = 2.dp, bottom = 2.dp))
        }
    }

    /** Top-right stopwatch: elapsed m:ss.t (20 sp bold) and a 22 dp play/stop disc beside it. Wired to the module-level state
     *  above, so the timer keeps running while the popup is closed; long-press the time to reset. */
    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    @Composable
    private fun Stopwatch() {
        var ms by remember { mutableLongStateOf(stopwatchCurrentMs()) }
        var running by remember { mutableStateOf(stopwatchRunning) }
        // 100 ms tick while the bubble is composed; it ends with it.
        LaunchedEffect(Unit) { while (true) { ms = stopwatchCurrentMs(); delay(100L) } }
        Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(formatStopwatch(ms), color = LILAC, fontSize = 20.sp, fontFamily = MONO, fontWeight = FontWeight.Bold,
                modifier = Modifier.combinedClickable(onClick = {}, onLongClick = {
                    // Long-press the time → reset to 0, so the user can start fresh without a third button.
                    stopwatchRunning = false
                    stopwatchAccumulatedMs = 0L
                    stopwatchStartElapsedMs = 0L
                    running = false; ms = 0L
                }).testTag("cal:stopwatch:time"))
            Box(Modifier.padding(start = 8.dp).size(22.dp).clip(CircleShape)
                .background(if (running) Color(0xFFB91C1C) else Color(0xFF16A34A))
                .clickable {
                    if (stopwatchRunning) {
                        stopwatchAccumulatedMs += SystemClock.elapsedRealtime() - stopwatchStartElapsedMs
                        stopwatchRunning = false
                    } else {
                        stopwatchStartElapsedMs = SystemClock.elapsedRealtime()
                        stopwatchRunning = true
                    }
                    running = stopwatchRunning
                    ms = stopwatchCurrentMs()
                }.testTag("cal:stopwatch:toggle"), contentAlignment = Alignment.Center) {
                Text(if (running) "■" else "▶", color = Color.White, fontSize = 10.sp)
            }
        }
    }

    internal fun formatStopwatch(ms: Long): String {
        val totalSec = ms / 1000L
        val m = totalSec / 60L
        val s = totalSec % 60L
        val tenths = (ms % 1000L) / 100L
        return "%d:%02d.%d".format(m, s, tenths)
    }

    internal fun resetStopwatchForTest() { stopwatchRunning = false; stopwatchAccumulatedMs = 0L; stopwatchStartElapsedMs = 0L }
}
