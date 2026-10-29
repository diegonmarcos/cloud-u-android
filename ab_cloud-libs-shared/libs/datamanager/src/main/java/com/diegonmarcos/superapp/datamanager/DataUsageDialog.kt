package com.diegonmarcos.superapp.datamanager

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.fragment.app.DialogFragment
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Data Usage manager — opened from Configs/About → Network, the same
 * "open the full data screen" affordance the Firewall section uses.
 *
 * Four axes:
 *   • per PERIOD — LIVE windows (Today / This month / 7d / 30d) answered
 *     by [DataUsageProvider] straight from NetworkStatsManager, plus
 *     STORED months from [DataUsageHistoryStore] which outlive the OS's
 *     ~90-day retention. The two pill rows pick between them.
 *   • per NETWORK TYPE — mobile vs Wi-Fi, rx/tx split.
 *   • per SIM — one row (and a chip, once more than one subscription is
 *     known) per active subscription, labelled carrier + slot.
 *   • per APP — ranked list; in a live window each row expands into a
 *     daily breakdown. Stored months keep only the top
 *     [DataUsageHistoryStore.TOP_APPS] apps, so those rows don't expand.
 *
 * Live windows persist nothing — the OS owns that history. Only the
 * monthly roll-up is stored, because that is precisely what the OS throws
 * away.
 *
 * 2026-10-16 owner decision: all apps must be Compose. The dialog is a ComposeView under CloudKitTheme
 * (libs:ui-kit); the palette comes from the dm_* colour resources, and the host contract
 * (DialogFragment, TAG) is unchanged.
 */
class DataUsageDialog : DialogFragment() {

    /** Selectable live window. `days` < 0 means "calendar month to date". */
    private data class Period(val label: String, val days: Int)

    private val periods = listOf(
        Period("Today", 0),
        Period("This month", -1),
        Period("Last 7 days", 7),
        Period("Last 30 days", 30),
    )

    /** Either a live period index, or a stored "yyyy-MM" key. Exactly one
     *  is non-null at any time. */
    private var selectedPeriod by mutableStateOf<Int?>(0)
    private var selectedMonth by mutableStateOf<String?>(null)

    /** uid of the row currently expanded into a daily breakdown, if any. */
    private var expandedUid by mutableStateOf<Int?>(null)

    /** Bumped to re-read every provider (a grant, or the Recheck button). */
    private var revision by mutableIntStateOf(0)

    /** #1274 back from the host's Permissions page: re-read every provider. */
    override fun onResume() { super.onResume(); revision++ }

    /** The "Open Permissions" banner action: the host's Permissions page on [ids]; this lib never asks itself. */
    private fun openPerms(ids: List<String>) =
        com.diegonmarcos.superapp.perms.PermsLink.open(requireContext(), DataUsageProvider.PERMS_FEATURE, ids)

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(
                androidx.core.content.ContextCompat.getColor(requireContext(), R.color.dm_bg)))
        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onCreateView(
        inflater: LayoutInflater, c: ViewGroup?, s: Bundle?,
    ): View {
        val ctx = inflater.context
        // Fold the current month in on open too, so history still accumulates
        // for a user whose device never lets the 15-min worker run.
        runCatching { DataUsageHistoryStore.refresh(ctx) }
        return ComposeView(ctx).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val palette = KitPalette(
                    surface = colorResource(R.color.dm_card),
                    surfaceSelected = colorResource(R.color.dm_active),
                    textPrimary = colorResource(R.color.dm_text),
                    textSecondary = colorResource(R.color.dm_text_dim),
                    accent = colorResource(R.color.dm_accent),
                    hairline = colorResource(R.color.dm_idle),
                    tileInk = colorResource(R.color.dm_text),
                )
                CloudKitTheme(palette) { Screen() }
            }
        }
    }

    private fun windowStart(now: Long): Long {
        val p = periods[selectedPeriod ?: 0]
        return when {
            p.days < 0 -> DataUsageProvider.startOfMonth(now)
            p.days == 0 -> DataUsageProvider.startOfToday(now)
            else -> DataUsageProvider.startOfDaysAgo(p.days - 1, now)
        }
    }

    @Composable
    private fun Screen() {
        val ctx = LocalContext.current
        val rev = revision
        Column(
            Modifier.fillMaxSize().background(colorResource(R.color.dm_bg))
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 48.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Data Usage", color = LocalKitPalette.current.textPrimary, fontSize = 22.sp,
                    fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text("✕", color = LocalKitPalette.current.textSecondary, fontSize = 20.sp,
                    modifier = Modifier.clickable { dismiss() }
                        .padding(start = 12.dp, top = 4.dp, end = 4.dp, bottom = 4.dp))
            }

            // Usage access is the ONE hard gate; the host's Permissions page grants it (#1274).
            if (!DataUsageProvider.hasUsageAccess(ctx)) {
                Card("Permissions needed: Usage access") {
                    Small("Per-app data usage is read from NetworkStatsManager, which needs the Usage Access special grant. It's the same grant the battery per-app estimate and the Phone smart folders use — granting it once unlocks all of them.")
                    Pill(com.diegonmarcos.superapp.perms.PermsBanner.BUTTON) { openPerms(listOf("usage_access")) }
                    Pill("Recheck") { revision++ }
                }
                return@Column
            }

            // Live-window selector.
            Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                periods.forEachIndexed { i, p ->
                    Pill(p.label, active = selectedPeriod == i, modifier = Modifier.weight(1f).padding(end = 6.dp)) {
                        selectedPeriod = i; selectedMonth = null; expandedUid = null
                    }
                }
            }

            // Stored-month picker — the history the OS no longer has.
            val stored = remember(rev) { runCatching { DataUsageHistoryStore.months(ctx) }.getOrDefault(emptyList()) }
            if (stored.isNotEmpty()) {
                Small("Stored months — kept beyond the OS's ~90-day retention")
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    stored.forEach { m ->
                        Pill(DataUsageHistoryStore.monthLabel(m.month),
                            active = selectedMonth == m.month, modifier = Modifier.padding(end = 6.dp)) {
                            selectedMonth = m.month; selectedPeriod = null; expandedUid = null
                        }
                    }
                }
            }

            val month = selectedMonth
            if (month != null) StoredMonth(month, rev) else Live(rev)
        }
    }

    // ── stored month ──────────────────────────────────────────────────
    @Composable
    private fun StoredMonth(month: String, rev: Int) {
        val ctx = LocalContext.current
        val row = remember(month, rev) {
            runCatching { DataUsageHistoryStore.months(ctx) }.getOrDefault(emptyList())
                .firstOrNull { it.month == month }
        }
        val label = DataUsageHistoryStore.monthLabel(month)

        Card("$label  ·  stored") {
            if (row == null) { Small("No stored row."); return@Card }
            val t = row.totals
            Big(DataUsageProvider.fmtBytes(t.total), "whole device, mobile + Wi-Fi")
            Line("Mobile", DataUsageProvider.fmtBytes(t.mobile))
            Split(t.mobileRx, t.mobileTx)
            Line("Wi-Fi", DataUsageProvider.fmtBytes(t.wifi))
            Split(t.wifiRx, t.wifiTx)
            if (t.total > 0) Bar(t.mobile, t.total, colorResource(R.color.dm_bar_mobile))
            Small("Month totals are exact and kept permanently — the OS itself may no longer hold this month.")
        }

        val subs = remember(month, rev) {
            runCatching { DataUsageHistoryStore.subsOf(ctx, month) }.getOrDefault(emptyList())
        }
        if (subs.isNotEmpty()) {
            Card("By SIM  ·  $label") {
                val peak = subs.maxOf { it.total }.coerceAtLeast(1L)
                subs.forEach { s ->
                    Line("${s.label}  (slot ${s.slot + 1})", DataUsageProvider.fmtBytes(s.total))
                    Split(s.rx, s.tx)
                    Bar(s.total, peak, colorResource(R.color.dm_bar_sim))
                }
                Small("Carrier and slot are recorded at the time of the month, so history stays readable across SIM swaps.")
            }
        }

        val apps = remember(month, rev) {
            runCatching { DataUsageHistoryStore.appsOf(ctx, month) }.getOrDefault(emptyList())
        }
        Card("By app  ·  top ${DataUsageHistoryStore.TOP_APPS} of $label") {
            if (apps.isEmpty()) {
                Small("No stored per-app rows for this month — they're kept for the most recent ${DataUsageHistoryStore.MAX_APP_MONTHS} months only.")
                return@Card
            }
            val peak = apps.first().bytes.coerceAtLeast(1L)
            apps.forEach { a ->
                StoredAppRow(a.pkg, a.bytes)
                Bar(a.bytes, peak, colorResource(R.color.dm_bar_app))
            }
            Small("Only the top ${DataUsageHistoryStore.TOP_APPS} apps are stored per month; the month total above stays exact regardless.")
        }
    }

    // ── live window ───────────────────────────────────────────────────
    @Composable
    private fun Live(rev: Int) {
        val ctx = LocalContext.current
        val period = selectedPeriod
        val now = remember(period, rev) { System.currentTimeMillis() }
        val start = remember(period, now) { windowStart(now) }
        val device = remember(period, rev) {
            runCatching { DataUsageProvider.deviceTotals(ctx, start, now) }
                .getOrDefault(DataUsageProvider.Totals())
        }
        val apps = remember(period, rev) {
            runCatching { DataUsageProvider.perApp(ctx, start, now) }.getOrDefault(emptyList())
        }

        // 1) Summary — the per-network-type axis.
        Card("${periods[period ?: 0].label}  ·  since ${fmtStamp(start)}") {
            Big(DataUsageProvider.fmtBytes(device.total), "whole device, mobile + Wi-Fi")
            Line("Mobile", DataUsageProvider.fmtBytes(device.mobile))
            Split(device.mobileRx, device.mobileTx)
            Line("Wi-Fi", DataUsageProvider.fmtBytes(device.wifi))
            Split(device.wifiRx, device.wifiTx)
            if (device.total > 0) {
                Bar(device.mobile, device.total, colorResource(R.color.dm_bar_mobile))
                Small("orange bar = mobile share of the period")
            }
            Small("Device totals come from querySummaryForDevice, so they include traffic no app owns (tethering, kernel sockets) and read slightly higher than the per-app sum below.")
        }

        // 2) Per-SIM breakdown.
        SimCard(start, now, rev)

        // 3) Per-app ranked list with drill-down.
        Card("By app  ·  ${apps.size} apps with traffic") {
            if (apps.isEmpty()) {
                Small("No per-app traffic recorded in this window.")
                return@Card
            }
            val peak = apps.first().totals.total.coerceAtLeast(1L)
            apps.forEach { a ->
                AppRow(a) { expandedUid = if (expandedUid == a.uid) null else a.uid }
                Bar(a.totals.total, peak, colorResource(R.color.dm_bar_app))
                if (expandedUid == a.uid) DrillDown(a, start, now)
            }
            Small("Tap an app for its day-by-day breakdown. Apps sharing a Linux uid are reported by the kernel as one bucket and can't be split apart.")
        }
    }

    /** Per-subscription card, with a Permissions banner when READ_PHONE_STATE
     *  is missing. Degrades to the aggregate mobile figure, never hides. */
    @Composable
    private fun SimCard(start: Long, end: Long, rev: Int) {
        val ctx = LocalContext.current
        Card("By SIM") {
            if (!DataUsageProvider.hasPhoneState(ctx)) {
                Small("Permissions needed: Phone. Splitting mobile data per SIM / eSIM needs it (READ_PHONE_STATE) to list your active subscriptions. Without it only the aggregate mobile total above is available.")
                Pill(com.diegonmarcos.superapp.perms.PermsBanner.BUTTON) { openPerms(listOf("phone_state")) }
                return@Card
            }
            val subs = remember(start, rev) {
                runCatching { DataUsageProvider.mobilePerSubscription(ctx, start, end) }
                    .getOrDefault(emptyList())
            }
            if (subs.isEmpty()) {
                Small("No active mobile subscription — Wi-Fi only, or no SIM present.")
                return@Card
            }
            // Chips only earn their space once there's more than one SIM.
            if (subs.size > 1) {
                Row(Modifier.fillMaxWidth()) {
                    subs.forEach { s ->
                        Pill("${simName(s)} · ${DataUsageProvider.fmtBytes(s.totals.mobile)}",
                            active = true, modifier = Modifier.weight(1f).padding(end = 6.dp)) { }
                    }
                }
            }
            val peak = subs.maxOf { it.totals.mobile }.coerceAtLeast(1L)
            subs.forEach { s ->
                Line("${simName(s)}  (slot ${s.slot + 1})", DataUsageProvider.fmtBytes(s.totals.mobile))
                Split(s.totals.mobileRx, s.totals.mobileTx)
                Bar(s.totals.mobile, peak, colorResource(R.color.dm_bar_sim))
            }
            if (subs.any { !it.exact }) {
                Small("⚠ Android 10+ restricts the subscriber id (IMSI) to privileged apps, so an exact per-SIM split isn't possible here — each subscription shows the COMBINED mobile total, not its own share. The monthly ledger stores that same honest figure rather than inventing a division.")
            } else {
                Small("Per-subscription totals, folded into the monthly ledger labelled by carrier + slot so they survive SIM swaps.")
            }
        }
    }

    private fun simName(s: DataUsageProvider.SubUsage): String =
        s.carrier.ifBlank { "SIM ${s.slot + 1}" }

    /** Expanded per-app daily breakdown for the selected live window. */
    @Composable
    private fun DrillDown(a: DataUsageProvider.AppUsage, start: Long, end: Long) {
        val ctx = LocalContext.current
        Column(Modifier.padding(start = 10.dp, top = 6.dp, bottom = 10.dp)) {
            Line("Mobile", DataUsageProvider.fmtBytes(a.totals.mobile))
            Split(a.totals.mobileRx, a.totals.mobileTx)
            Line("Wi-Fi", DataUsageProvider.fmtBytes(a.totals.wifi))
            Split(a.totals.wifiRx, a.totals.wifiTx)
            Small("Daily —")
            val daily = remember(a.uid, start) {
                runCatching { DataUsageProvider.dailyForUid(ctx, a.uid, start, end) }
                    .getOrDefault(emptyList())
            }
            val peak = daily.maxOfOrNull { it.totals.total }?.coerceAtLeast(1L) ?: 1L
            if (daily.isEmpty()) {
                Small("No daily buckets in this window.")
            } else {
                daily.forEach { d ->
                    Line(fmtDay(d.startMs), DataUsageProvider.fmtBytes(d.totals.total))
                    Bar(d.totals.total, peak, colorResource(R.color.dm_bar_day))
                }
            }
            Small(a.pkg)
        }
    }

    // ── tiny composable helpers (same idiom as EnergyUsageDialog) ───────
    @Composable
    private fun Card(title: String, body: @Composable () -> Unit) {
        val p = LocalKitPalette.current
        Text(title, color = p.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.surface).padding(12.dp)) {
            body()
        }
    }

    @Composable
    private fun Big(value: String, sub: String) {
        val p = LocalKitPalette.current
        Text(value, color = p.textPrimary, fontSize = 26.sp, fontWeight = FontWeight.Medium)
        Text(sub, color = p.textPrimary.copy(alpha = 0.6f), fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 6.dp))
    }

    @Composable
    private fun Line(k: String, v: String) {
        val p = LocalKitPalette.current
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Text(k, color = p.textSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(v, color = p.textPrimary, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.End)
        }
    }

    /** "↓ 12.4 MB   ↑ 900 KB" sub-line under a transport row. */
    @Composable
    private fun Split(rx: Long, tx: Long) {
        Text("↓ ${DataUsageProvider.fmtBytes(rx)}    ↑ ${DataUsageProvider.fmtBytes(tx)}",
            color = LocalKitPalette.current.textPrimary.copy(alpha = 0.53f), fontSize = 11.sp,
            fontFamily = FontFamily.Monospace, textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
    }

    @Composable
    private fun AppRow(a: DataUsageProvider.AppUsage, onClick: () -> Unit) {
        val p = LocalKitPalette.current
        Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically) {
            AppIcon(a.pkg)
            Text(a.label, color = p.textPrimary, fontSize = 14.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(DataUsageProvider.fmtBytes(a.totals.total), color = colorResource(R.color.dm_value),
                fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        }
    }

    /** Row for a stored month — only pkg + bytes were kept, and the app may
     *  since have been uninstalled, so the label falls back to the package. */
    @Composable
    private fun StoredAppRow(pkg: String, bytes: Long) {
        val ctx = LocalContext.current
        val label = remember(pkg) {
            runCatching {
                ctx.packageManager.getApplicationLabel(
                    ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(pkg)
            Text(label, color = LocalKitPalette.current.textPrimary, fontSize = 14.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(DataUsageProvider.fmtBytes(bytes), color = colorResource(R.color.dm_value),
                fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        }
    }

    @Composable
    private fun AppIcon(pkg: String) {
        val ctx = LocalContext.current
        val bmp = remember(pkg) {
            runCatching { ctx.packageManager.getApplicationIcon(pkg).toBitmap(64, 64).asImageBitmap() }.getOrNull()
        }
        Box(Modifier.padding(end = 10.dp).size(28.dp)) {
            if (bmp != null) Image(bmp, contentDescription = null, modifier = Modifier.size(28.dp))
        }
    }

    /** Proportional horizontal bar — no drawing code and no charting dependency. */
    @Composable
    private fun Bar(value: Long, peak: Long, color: Color) {
        val frac = (value.coerceIn(0L, peak).toFloat() / peak.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
        Box(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp).height(4.dp)) {
            if (frac > 0f) Box(Modifier.fillMaxWidth(frac).height(4.dp).background(color))
        }
    }

    @Composable
    private fun Pill(label: String, active: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
        val p = LocalKitPalette.current
        Text(label, color = p.textPrimary, fontSize = 12.sp, textAlign = TextAlign.Center,
            modifier = modifier.clip(RoundedCornerShape(20.dp))
                .background(if (active) p.surfaceSelected else p.hairline)
                .clickable { onClick() }
                .padding(horizontal = 10.dp, vertical = 8.dp))
    }

    @Composable
    private fun Small(t: String) {
        Text(t, color = LocalKitPalette.current.textPrimary.copy(alpha = 0.47f), fontSize = 11.sp,
            modifier = Modifier.padding(top = 2.dp, bottom = 4.dp))
    }

    private fun fmtStamp(ts: Long): String =
        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(ts))

    private fun fmtDay(ts: Long): String =
        SimpleDateFormat("EEE MMM d", Locale.getDefault()).format(Date(ts))

    companion object { const val TAG = "data_usage_dialog" }
}
