package com.diegonmarcos.capitalfinterm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.diegonmarcos.capitalfinterm.Alerts
import com.diegonmarcos.capitalfinterm.AssetData
import com.diegonmarcos.capitalfinterm.Catalog
import com.diegonmarcos.capitalfinterm.Dashboards
import com.diegonmarcos.capitalfinterm.FinFormat
import com.diegonmarcos.capitalfinterm.FinLogic
import com.diegonmarcos.capitalfinterm.ui.fin.FinCard
import com.diegonmarcos.capitalfinterm.ui.fin.FinDim
import com.diegonmarcos.capitalfinterm.ui.fin.FinFooter
import com.diegonmarcos.capitalfinterm.ui.fin.FinKpiGrid
import com.diegonmarcos.capitalfinterm.ui.fin.FinKpiTile
import com.diegonmarcos.capitalfinterm.ui.fin.FinMonitorRow
import com.diegonmarcos.capitalfinterm.ui.fin.FinQuote
import com.diegonmarcos.capitalfinterm.ui.fin.FinQuoteHeader
import com.diegonmarcos.capitalfinterm.ui.fin.FinTitleRow
import com.diegonmarcos.capitalfinterm.ui.fin.Fresh
import com.diegonmarcos.capitalfinterm.ui.fin.LocalFinPalette

/**
 * Quote detail (UI spec 4.3), pushed on the tab's stack: the quote header pinned at the top, then the KPI strip (previous close,
 * change, change %, as of), the "Related" instruments of the same panel as monitor rows, and the footer. It reads what the panels
 * already fetched (FinStore), so it opens at once; the star adds or removes the symbol from the watchlist.
 */
@Composable
fun QuotePage(symbol: String, nav: FinNav) {
    val ctx = LocalContext.current
    val p = LocalFinPalette.current
    val sources = remember { AssetData.sources(ctx) }
    val dashboards = remember { AssetData.dashboards(ctx) }
    val box = remember { EncryptedSecretBox(ctx) }
    var watch by remember { mutableStateOf(Alerts.watchlist(box.get(KEY_WATCHLIST))) }
    val hit = FinStore.entries.entries.firstNotNullOfOrNull { (k, e) ->
        val d = e.data ?: return@firstNotNullOfOrNull null
        val panel = dashboards.firstOrNull { it.id == k.substringBefore('/') }?.panels?.firstOrNull { it.id == k.substringAfter('/') }
        if (panel?.view != Dashboards.VIEW_QUOTES) return@firstNotNullOfOrNull null
        val qs = quotesOf(d)
        qs.firstOrNull { it.symbol.equals(symbol, ignoreCase = true) }?.let { Triple(it, qs, k to e) }
    }
    val q = hit?.first
    val entry = hit?.third?.second
    val source = entry?.let { Catalog.byId(sources, it.source) }
    val stale = entry != null && (entry.failures > 0 || FinFormat.isStale(entry.fetchedAt, System.currentTimeMillis(), entry.source))
    val watched = watch.any { it.equals(symbol, ignoreCase = true) }
    Column(Modifier.fillMaxSize().background(p.canvas).testTag("fin:quotepage:$symbol")) {
        FinQuoteHeader(q ?: if (hit == null && FinStore.entries.isNotEmpty()) FinQuote(symbol, last = "", chg = "", pct = "", date = "") else null,
            source?.name.orEmpty(), stale, watched,
            onWatch = {
                watch = if (watched) watch.filterNot { it.equals(symbol, ignoreCase = true) } else watch + symbol.uppercase()
                box.put(KEY_WATCHLIST, Alerts.watchlistText(watch))
            },
            onRetry = { nav.search() })
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val width = maxWidth - FinDim.screenPad * 2
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = FinDim.screenPad, end = FinDim.screenPad, top = FinDim.gap, bottom = FinDim.bottomInset),
                verticalArrangement = Arrangement.spacedBy(FinDim.gap)) {
                if (q != null) item(key = "kpi") {
                    val last = FinFormat.parseNum(q.last)
                    val chg = FinFormat.parseNum(q.chg)
                    val prev = if (last != null && chg != null) FinFormat.price(last - chg) else FinFormat.DASH
                    val tiles = listOf(
                        FinQuote("Prev close", last = prev, chg = "", pct = "", date = q.date),
                        FinQuote("Change", last = FinLogic.change(q.chg), chg = "", pct = "", date = q.date),
                        FinQuote("Change %", last = if (FinLogic.missing(q.pct)) FinFormat.DASH else FinLogic.change(q.pct) + "%", chg = "", pct = "", date = q.date),
                        FinQuote("As of", last = q.date.take(10).ifBlank { FinFormat.DASH }, chg = "", pct = "", date = q.date.drop(11).take(5)),
                    )
                    FinKpiGrid(tiles.map<FinQuote, @Composable (Modifier) -> Unit> { t -> { m -> FinKpiTile(t, t.symbol, "", stale, m) } }, if (width >= 456.dp) 4 else 2)
                }
                val related = hit?.second?.filterNot { it.symbol.equals(symbol, ignoreCase = true) }.orEmpty()
                if (related.isNotEmpty()) item(key = "related") {
                    FinCard(pad = false) {
                        Box(Modifier.padding(horizontal = FinDim.cardPad)) { FinTitleRow("Related") }
                        related.forEachIndexed { i, r -> FinMonitorRow(r, stale, width, divider = i < related.size - 1) { nav.openQuote(r.symbol) } }
                        Box(Modifier.padding(horizontal = FinDim.cardPad)) {
                            FinFooter("quote", if (stale) Fresh.STALE else Fresh.FRESH, "", q?.date.orEmpty(), source?.name.orEmpty(), entry?.data?.servedBy.orEmpty()) { nav.openSources() }
                        }
                    }
                }
            }
        }
    }
}

