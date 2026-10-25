package com.diegonmarcos.superapp.cal
import com.diegonmarcos.superapp.launcher.AggregatorStackFragment
import com.diegonmarcos.superapp.R

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import java.util.Calendar
import java.util.Locale

/**
 * Self-contained month grid (Sun-first, up to 6 rows × 7 columns). No CalDAV
 * integration yet — that lands with libs:cal slice D. Today is highlighted
 * with a circular badge in the palette's accent.
 *
 * Used standalone (page:cal/month) and embedded inside [AggregatorStackFragment]
 * (Infos · Apps stack_apps[0] = kind "calendar_month"). Compose since #773; the month
 * arithmetic is [monthGrid], pure, so a test pins it without a UI.
 */
class CalendarMonthFragment : KitComposeFragment() {

    override fun palette() = LauncherPalette.kit(requireContext())

    @Composable
    override fun Content() {
        val p = LocalKitPalette.current
        val now = Calendar.getInstance()
        // Year * 12 + month (0-based — Calendar.MONTH convention): one saveable number the arrows step.
        var shown by rememberSaveable { mutableIntStateOf(now.get(Calendar.YEAR) * 12 + now.get(Calendar.MONTH)) }
        val year = shown / 12
        val month = shown % 12
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            // Header: ◀  Month YYYY  ▶
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Arrow(false) { shown-- }
                Text(monthTitle(year, month), color = p.accent, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).testTag(TAG_TITLE))
                Arrow(true) { shown++ }
            }
            // Day-of-week strip (S M T W T F S — locale-aware short names).
            Row(Modifier.fillMaxWidth().padding(2.dp)) {
                val short = java.text.DateFormatSymbols(Locale.getDefault()).shortWeekdays
                for (dow in Calendar.SUNDAY..Calendar.SATURDAY) {
                    Text(short[dow].take(1), color = p.textPrimary, fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f).alpha(0.65f))
                }
            }
            val todayDom = if (now.get(Calendar.YEAR) == year && now.get(Calendar.MONTH) == month)
                now.get(Calendar.DAY_OF_MONTH) else -1
            for (week in monthGrid(year, month)) {
                Row(Modifier.fillMaxWidth()) {
                    for (day in week) {
                        Box(Modifier.weight(1f).height(40.dp).padding(2.dp), contentAlignment = Alignment.Center) {
                            if (day != 0) {
                                val isToday = day == todayDom
                                Box(Modifier.fillMaxWidth().height(36.dp)
                                    .clip(CircleShape)
                                    .then(if (isToday) Modifier.background(p.accent) else Modifier)
                                    // Placeholder for day-tap; the CalDAV slice surfaces the day agenda here.
                                    .clickable { }
                                    .testTag(dayTag(day)),
                                    contentAlignment = Alignment.Center) {
                                    Text(day.toString(), color = if (isToday) p.tileInk else p.textPrimary,
                                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                                        style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Arrow(next: Boolean, onClick: () -> Unit) {
        Icon(painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = LocalKitPalette.current.accent,
            modifier = Modifier.size(36.dp)
                .semantics { contentDescription = if (next) "Next month" else "Previous month" }
                .testTag(if (next) TAG_NEXT else TAG_PREV)
                .clickable(onClick = onClick)
                .padding(6.dp)
                .then(if (next) Modifier else Modifier.rotate(180f)))
    }

    companion object {
        const val TAG_TITLE = "calmonth:title"
        const val TAG_PREV = "calmonth:prev"
        const val TAG_NEXT = "calmonth:next"
        fun dayTag(day: Int) = "calmonth:day:$day"

        internal fun monthTitle(year: Int, month: Int): String {
            val cal = Calendar.getInstance().apply { clear(); set(Calendar.YEAR, year); set(Calendar.MONTH, month) }
            return java.text.SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(cal.time)
        }

        /**
         * The month as rows of seven day numbers, Sunday first, 0 = blank. The 1st sits in the column
         * of its weekday; trailing all-blank rows are trimmed.
         */
        internal fun monthGrid(year: Int, month: Int): List<List<Int>> {
            val cal = Calendar.getInstance().apply { clear(); set(Calendar.YEAR, year); set(Calendar.MONTH, month) }
            val leading = cal.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY       // 0..6
            val days = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
            val cells = List(leading) { 0 } + (1..days).toList()
            return cells.chunked(7).map { it + List(7 - it.size) { 0 } }
        }

        fun newInstance() = CalendarMonthFragment()
    }
}
