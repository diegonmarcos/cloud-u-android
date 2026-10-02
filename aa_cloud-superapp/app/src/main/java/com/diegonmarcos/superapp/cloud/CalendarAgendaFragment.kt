package com.diegonmarcos.superapp.cloud
import com.diegonmarcos.superapp.launcher.AggregatorStackFragment
import com.diegonmarcos.superapp.ui.LauncherPalette

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Self-contained agenda list — next 7 days, one row per day. No CalDAV
 * integration yet (lands with libs:cal slice D); each day shows "no
 * events" as a placeholder so the layout reads correctly.
 *
 * Used standalone (page:cal/agenda) and embedded inside
 * [AggregatorStackFragment] (Infos · Apps stack_apps = kind "calendar_agenda").
 *
 * Compose since #773. The rows used to paint hand-picked purple/white
 * literals, which no launcher theme could recolour; today is now the
 * palette's selected surface and the rest its plain surface. No scroll of its
 * own: embedded, it sits inside the stack's scrolling body.
 */
class CalendarAgendaFragment : KitComposeFragment() {

    override fun palette() = LauncherPalette.kit(requireContext())

    @Composable
    override fun Content() {
        val p = LocalKitPalette.current
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            for ((i, day) in days().withIndex()) {
                val today = i == 0
                Column(
                    Modifier.fillMaxWidth().padding(top = 4.dp)
                        .background(if (today) p.surfaceSelected else p.surface)
                        .padding(6.dp)
                        // One item per day for TalkBack ("Today · Thu 2 Oct, no events"), and
                        // one node a test can read the day's text off.
                        .semantics(mergeDescendants = true) {}
                        .testTag(dayTag(i)),
                ) {
                    Text(day, // MUTANT M6
                        color = p.textPrimary, style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (today) FontWeight.Bold else FontWeight.Normal)
                    Text("no events", color = p.textSecondary, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 2.dp))
                }
            }
            Text(
                "CalDAV integration lands with libs:cal slice D — once wired, events from your declared calendars populate here.",
                color = p.textSecondary, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 2.dp, top = 10.dp, end = 2.dp, bottom = 2.dp),
            )
        }
    }

    companion object {
        /** The seven day headings, today first, in the device's locale. */
        internal fun days(): List<String> {
            val fmt = SimpleDateFormat("EEE  d MMM", Locale.getDefault())
            val cal = Calendar.getInstance()
            return List(7) { fmt.format(cal.time).also { cal.add(Calendar.DAY_OF_YEAR, 1) } }
        }

        internal fun dayTag(i: Int): String = "agenda:day:$i"

        fun newInstance(): CalendarAgendaFragment = CalendarAgendaFragment()
    }
}
