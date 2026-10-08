package com.diegonmarcos.cloudsearch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.diegonmarcos.cloudsearch.R
import com.diegonmarcos.cloudsearch.core.agents.ReportItem

/**
 * #913 Reports: what agents published, newest first, each opening a detail view. The reports are written by
 * agents through the local ReportStore (data/AgentData.kt); this page only reads them and lets the person mark
 * a draft as sent by themselves or dismiss it.
 */
@Composable
fun ReportsSection(state: SearchState) {
    val svc = state.services.agents
    val g = LocalGlass.current
    val m = state.agentsModel
    if (svc == null) {
        Text(stringResource(R.string.agents_none), Modifier.padding(top = Metrics.stripTop, start = Metrics.gutter), color = g.text2)
        return
    }
    val reports = remember(m.rev, state.reportOpen) { svc.reports.newestFirst() }
    val open = state.reportOpen?.let { id -> reports.firstOrNull { it.id == id } }
    LazyColumn(
        Modifier.fillMaxSize().testTag(Tags.page("reports")),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = Metrics.gutter, end = Metrics.gutter, top = Metrics.stripTop, bottom = Metrics.contentBottom),
        verticalArrangement = Arrangement.spacedBy(Metrics.gap),
    ) {
        if (open != null) {
            item {
                GlassCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                        Chip(stringResource(R.string.reports_back), AgentTags.report("back"), icon = R.drawable.ph_caret_down) { state.reportOpen = null }
                        Chip(stringResource(R.string.reports_delete), AgentTags.report("delete")) { svc.reports.delete(open.id); state.reportOpen = null; m.rev++ }
                    }
                    Text(open.title, color = g.text, style = Type.style(Type.cardTitle, FontWeight.Bold))
                    Text(stamp(open.createdAt), color = g.text2, style = Type.style(Type.tiny))
                    Text(open.summary, color = g.text2, style = Type.style(Type.small))
                    if (open.items.isEmpty()) Text(stringResource(R.string.reports_no_items), color = g.text2, style = Type.style(Type.small))
                }
            }
            items(open.items, key = { it.id }) { DraftRow(state, open, it) }
        } else {
            if (reports.isEmpty()) item { Text(stringResource(R.string.reports_empty), color = g.text2, style = Type.style(Type.small)) }
            items(reports, key = { it.id }) { r ->
                GlassCard(Modifier.testTag(AgentTags.report("row_" + r.id)), onClick = { state.reportOpen = r.id }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                        Text(r.title, Modifier.weight(1f), color = g.text, style = Type.style(Type.cardTitle, FontWeight.Bold))
                        Text(stamp(r.createdAt), color = g.text2, style = Type.style(Type.tiny))
                    }
                    Text(r.summary, color = g.text2, style = Type.style(Type.small), maxLines = 2)
                    val waiting = r.items.count { it.status == ReportItem.DRAFT }
                    Text(stringResource(R.string.reports_counts, r.items.size, waiting), color = g.text2, style = Type.style(Type.tiny))
                }
            }
        }
    }
}
