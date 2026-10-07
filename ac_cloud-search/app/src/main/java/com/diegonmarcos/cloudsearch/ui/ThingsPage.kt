package com.diegonmarcos.cloudsearch.ui

import android.Manifest
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudsearch.R
import com.diegonmarcos.cloudsearch.core.SearchConfig
import com.diegonmarcos.cloudsearch.core.ThingsEngine
import com.diegonmarcos.cloudsearch.core.Things
import com.diegonmarcos.cloudsearch.data.Browser
import com.diegonmarcos.cloudsearch.data.Locator
import com.diegonmarcos.cloudsearch.data.ThingsArea
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** The Things page's state: the item, where the search was centred, and the comparison it produced. */
class ThingsModel(initial: String) {
    var q by mutableStateOf(initial)
    var result by mutableStateOf<ThingsEngine.Result?>(null)
    var where by mutableStateOf<ThingsArea.Resolved?>(null)
    var loading by mutableStateOf(false)
    var stale by mutableStateOf(true)

    fun run(state: SearchState, scope: CoroutineScope, ctx: android.content.Context) {
        val item = q
        loading = true
        stale = false
        state.busy++
        scope.launch {
            try {
                val (w, r) = withContext(Dispatchers.IO) {
                    runCatching {
                        val w = ThingsArea.resolve(state.services, ctx)
                        w to state.services.things.compare(w.area, item)
                    }.getOrNull() ?: (null to null)
                }
                where = w
                result = r
                state.services.prefs.setLastQuery(state.v().id, item)
            } catch (e: CancellationException) {
                stale = true // the page left before the answer came: ask again when it is back
                throw e
            } finally {
                loading = false
                state.busy--
            }
        }
    }
}

/** "14:05" for today, otherwise the date: when a price was seen or fetched. */
fun stamp(ms: Long): String =
    if (DateUtils.isToday(ms)) android.text.format.DateFormat.format("HH:mm", ms).toString() else day(ms)

@Composable
fun ThingsPage(v: SearchConfig.Vertical) {
    val state = LocalState.current
    val g = LocalGlass.current
    val ctx = LocalContext.current
    val m = state.things(v.id)
    val scope = rememberCoroutineScope()
    val prefs = state.services.prefs
    // The coarse-location permission is asked ONCE, the first time Things opens with location on.
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { state.areaChanged() }
    LaunchedEffect(v.id, state.city, state.areaRev) {
        if (prefs.useLocation && !prefs.locationAsked && !Locator.granted(ctx)) {
            prefs.locationAsked = true
            ask.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        } else if (m.stale) m.run(state, scope, ctx)
    }
    val r = m.result
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        pageHeader(state, v)
        item { GlassField(m.q, { m.q = it }, v.placeholder, Tags.SEARCH_BOX, onSearch = { m.run(state, scope, ctx) }) }
        item { AreaLine(m.where, r) }
        if (m.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = g.accent, trackColor = g.glass) }
        if (r != null) {
            item { StoresLine(r) }
            if (r.rows.isNotEmpty()) item { PriceTable(r.rows) }
            else if (!m.loading && r.statuses.firstOrNull { it.id == ThingsEngine.ID_STORES }?.let { it.state.hasAnswer() } == true) item {
                Text(stringResource(R.string.things_no_stores), color = g.text2, style = Type.style(Type.small), modifier = Modifier.padding(vertical = Metrics.gutter))
            }
            item { SourceStrip(r.statuses + state.services.engine.unfetched(v.id, m.q, state.city)) }
        } else if (!m.loading && m.where == null && !m.stale) item {
            Text(stringResource(R.string.things_area_failed, "?"), color = g.negative, style = Type.style(Type.small))
        }
        item { Text(stringResource(R.string.things_blurb), color = g.text2, style = Type.style(Type.tiny)) }
    }
}

private fun com.diegonmarcos.cloudsearch.core.SearchEngine.State.hasAnswer() = this == com.diegonmarcos.cloudsearch.core.SearchEngine.State.OK ||
    this == com.diegonmarcos.cloudsearch.core.SearchEngine.State.CACHED || this == com.diegonmarcos.cloudsearch.core.SearchEngine.State.STALE

/** Where the search is centred and how that was decided; the chip opens the settings that change it. */
@Composable
private fun AreaLine(w: ThingsArea.Resolved?, r: ThingsEngine.Result?) {
    val state = LocalState.current
    val g = LocalGlass.current
    if (w == null) return
    val place = if (w.how == ThingsArea.How.LOCATION) stringResource(R.string.things_area_location) else w.area.label
    val how = stringResource(when (w.how) {
        ThingsArea.How.LOCATION -> R.string.things_how_location
        ThingsArea.How.TYPED -> R.string.things_how_typed
        ThingsArea.How.CITY -> R.string.things_how_city
    })
    val why = when (w.why) {
        ThingsArea.Why.NONE -> null
        ThingsArea.Why.PERMISSION -> stringResource(R.string.things_why_permission)
        ThingsArea.Why.NO_FIX -> stringResource(R.string.things_why_nofix)
        ThingsArea.Why.UNKNOWN_CITY -> stringResource(R.string.things_why_unknown_city, w.typed)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        Ph(R.drawable.ph_map_pin, Metrics.iconSm, g.accent)
        Text(
            stringResource(R.string.things_area_line, place, r?.area?.radiusKm ?: w.area.radiusKm, how) + (why?.let { " ($it)" } ?: ""),
            color = g.text2, style = Type.style(Type.small), maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).testTag(Tags.THINGS_AREA),
        )
        Chip(stringResource(R.string.things_area_title), Tags.THINGS_SETTINGS) { state.menu = Menu.PROFILE }
    }
}

@Composable
private fun StoresLine(r: ThingsEngine.Result) {
    val g = LocalGlass.current
    val text = if (r.matched) stringResource(R.string.things_stores_line, r.category.label, r.storesFound, r.rows.size)
    else stringResource(R.string.things_fallback_line, r.q)
    Text(text, color = g.text2, style = Type.style(Type.label), modifier = Modifier.testTag(Tags.THINGS_STORES))
}

/** The comparison: store, distance, price (with where it was seen and when), cheapest first; no price says why. */
@Composable
private fun PriceTable(rows: List<Things.Row>) {
    val g = LocalGlass.current
    val ctx = LocalContext.current
    GlassCard(Modifier.testTag(Tags.THINGS_TABLE)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            Text(stringResource(R.string.things_col_store), color = g.text2, style = Type.style(Type.label, FontWeight.Bold), modifier = Modifier.weight(1f))
            Text(stringResource(R.string.things_col_km), color = g.text2, style = Type.style(Type.label, FontWeight.Bold), textAlign = TextAlign.End, modifier = Modifier.width(KM_COL))
            Text(stringResource(R.string.things_col_price), color = g.text2, style = Type.style(Type.label, FontWeight.Bold), textAlign = TextAlign.End, modifier = Modifier.width(PRICE_COL))
        }
        rows.forEach { row ->
            Hairline()
            Row(
                Modifier.fillMaxWidth().testTag(Tags.thingsRow(row.store.osm)).semantics(mergeDescendants = true) {}
                    .let { mod -> row.url?.let { u -> mod.clickable { Browser.open(ctx, u) } } ?: mod },
                horizontalArrangement = Arrangement.spacedBy(Metrics.gap),
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.small)) {
                        Text(row.store.name, color = g.text, style = Type.style(Type.body, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (row.url != null) Ph(R.drawable.ph_arrow_square_out, Metrics.iconXs, g.text2, stringResource(R.string.things_open_link))
                    }
                    val sub = if (row.price != null) listOfNotNull(row.title, row.store.address).joinToString(" · ") else row.note
                    if (sub.isNotBlank()) Text(sub, color = g.text2, style = Type.style(Type.label), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(String.format(Locale.ROOT, "%.1f", row.store.km), color = g.text2, style = Type.style(Type.small), textAlign = TextAlign.End, modifier = Modifier.width(KM_COL))
                Column(Modifier.width(PRICE_COL), horizontalAlignment = Alignment.End) {
                    val price = row.price
                    if (price != null) {
                        Text(money(price, row.currency), color = g.text, style = Type.style(Type.body, FontWeight.Bold))
                        val src = stringResource(if (row.source == Things.Source.SHELF) R.string.things_src_shelf else R.string.things_src_online)
                        Text(listOfNotNull(src, row.at?.let { stamp(it) }).joinToString(" · "), color = g.text2, style = Type.style(Type.tiny), maxLines = 1)
                    } else Text(stringResource(R.string.things_no_price), color = g.text2, style = Type.style(Type.small))
                }
            }
        }
    }
}

private val KM_COL = 30.dp
private val PRICE_COL = 74.dp

/** The Things area in the profile menu: location on/off (asked once), a typed city for when it is off, the radius. */
@Composable
fun ThingsSettings(state: SearchState) {
    val g = LocalGlass.current
    val ctx = LocalContext.current
    val prefs = state.services.prefs
    val t = state.cfg.things ?: return
    var use by remember { mutableStateOf(prefs.useLocation) }
    var city by remember { mutableStateOf(prefs.thingsCity) }
    var radius by remember { mutableStateOf(prefs.radiusKm.toString()) }
    var granted by remember { mutableStateOf(Locator.granted(ctx)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; state.areaChanged() }
    fun apply() {
        prefs.thingsCity = city
        val km = radius.toIntOrNull()?.let { t.clampRadius(it) } ?: prefs.radiusKm
        prefs.radiusKm = km
        radius = km.toString()
        state.areaChanged()
    }
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.small)) {
        FieldLabel(stringResource(R.string.things_area_title))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            Text(stringResource(R.string.things_use_location), color = g.text, style = Type.style(Type.small), modifier = Modifier.weight(1f))
            Switch(
                use,
                { on ->
                    use = on
                    prefs.useLocation = on
                    if (on && !prefs.locationAsked && !granted) { prefs.locationAsked = true; ask.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }
                    else state.areaChanged()
                },
                Modifier.testTag(Tags.THINGS_USE_LOCATION),
                colors = SwitchDefaults.colors(checkedTrackColor = g.accent),
            )
        }
        Text(
            stringResource(when { granted -> R.string.things_loc_granted; prefs.locationAsked -> R.string.things_loc_denied; else -> R.string.things_loc_unasked }),
            color = g.text2, style = Type.style(Type.tiny),
        )
        GlassField(city, { city = it }, stringResource(R.string.things_city_hint), Tags.THINGS_CITY, icon = R.drawable.ph_map_pin, onSearch = { apply() })
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            Text(stringResource(R.string.things_radius_km), color = g.text, style = Type.style(Type.small))
            GlassField(radius, { radius = it.filter(Char::isDigit).take(3) }, "", Tags.THINGS_RADIUS, Modifier.weight(1f), icon = null, number = true, onSearch = { apply() })
        }
        ChipRow {
            t.radiusStepsKm.forEach { km -> Chip("$km km", Tags.option("radius_$km"), selected = radius == km.toString()) { radius = km.toString(); apply() } }
        }
        Chip(stringResource(R.string.things_apply), Tags.THINGS_APPLY, accent = true) { apply() }
    }
}
