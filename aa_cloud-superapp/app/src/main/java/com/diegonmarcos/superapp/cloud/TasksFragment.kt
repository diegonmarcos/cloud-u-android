package com.diegonmarcos.superapp.cloud
import com.diegonmarcos.superapp.launcher.AggregatorStackFragment

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Tasks aggregator panel — a tab strip on top (Agenda · Day · ToDo)
 * + a swappable body below. Each tab is a self-contained placeholder
 * until CalDAV / Tasks integration lands; the panel exists so the
 * Infos · Apps surface gets the three views the user wants from one
 * card.
 *
 * Embedded inside [AggregatorStackFragment] (kind = "tasks"), so it has no scroll of
 * its own. Compose since #773: the chips and rows were hand-picked purple/white literals
 * no launcher theme could recolour; they are the palette's surface roles now.
 */
class TasksFragment : KitComposeFragment() {

    internal enum class Tab(val label: String) { AGENDA("Agenda"), DAY("Day"), TODO("ToDo") }

    override fun palette() = LauncherPalette.kit(requireContext())

    @Composable
    override fun Content() {
        var active by rememberSaveable { mutableStateOf(Tab.AGENDA) }
        val p = LocalKitPalette.current
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            // ── Chip strip ──
            Row(Modifier.fillMaxWidth().padding(start = 2.dp, top = 2.dp, end = 2.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (t in Tab.values()) {
                    val sel = t == active
                    Text(t.label, color = if (sel) p.textPrimary else p.textSecondary,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                        textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                            .background(if (sel) p.surfaceSelected else p.surface)
                            .clickable(role = Role.Tab) { active = t }
                            .semantics { selected = sel }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .testTag(chipTag(t)))
                }
            }
            // ── Body ──
            Column(Modifier.testTag(TAG_BODY)) {
                when (active) {
                    Tab.AGENDA -> Agenda()
                    Tab.DAY    -> Day()
                    Tab.TODO   -> ToDo()
                }
            }
        }
    }

    @Composable
    private fun Agenda() {
        val days = agendaDays()
        for ((i, d) in days.withIndex()) {
            SimpleRow(if (i == 0) "Today · $d" else d, "no events", i == 0)
        }
        Caption("CalDAV integration lands with libs:cal — declared calendars populate events here.")
    }

    @Composable
    private fun Day() {
        val now = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        // 8am → 10pm hourly grid.
        for (h in 8..22) {
            SimpleRow("%02d:00".format(h) + (if (h == now) "  · now" else ""),
                if (h == now) "no events right now" else "·", h == now)
        }
        Caption("Hour-by-hour for today. Once CalDAV is wired each row shows events that overlap that slot.")
    }

    @Composable
    private fun ToDo() {
        // Placeholder ToDo list. Real ToDo backend (CalDAV VTODO or
        // Tasks API) lands later; for now show a single illustrative row.
        SimpleRow("(no tasks yet)", "Sample row — long-press for actions when the ToDo store wires in.", false)
        Caption("ToDo persistence pending — will surface VTODO items from your CalDAV server + a local quick-task store.")
    }

    @Composable
    private fun SimpleRow(title: String, body: String, highlight: Boolean) {
        val p = LocalKitPalette.current
        Column(Modifier.fillMaxWidth().padding(top = 4.dp)
            .background(if (highlight) p.surfaceSelected else p.surface).padding(6.dp)) {
            Text(title, color = if (highlight) p.textPrimary else p.textSecondary,
                fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal,
                style = MaterialTheme.typography.bodyLarge)
            Text(body, color = p.textSecondary, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp))
        }
    }

    @Composable
    private fun Caption(text: String) {
        Text(text, color = LocalKitPalette.current.textSecondary, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 2.dp, top = 10.dp, end = 2.dp, bottom = 2.dp))
    }

    companion object {
        const val TAG_BODY = "tasks:body"
        internal fun chipTag(t: Tab) = "tasks:chip:${t.name.lowercase()}"
        /** The seven agenda headings, today first, in the device's locale. */
        internal fun agendaDays(): List<String> {
            val fmt = SimpleDateFormat("EEE  d MMM", Locale.getDefault())
            val cal = Calendar.getInstance()
            return List(7) { fmt.format(cal.time).also { cal.add(Calendar.DAY_OF_YEAR, 1) } }
        }
        fun newInstance(): TasksFragment = TasksFragment()
    }
}
