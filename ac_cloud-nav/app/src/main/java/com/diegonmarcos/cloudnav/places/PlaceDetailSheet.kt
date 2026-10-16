package com.diegonmarcos.cloudnav.places

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudnav.NavConfig
import com.diegonmarcos.cloudnav.PoiField
import com.diegonmarcos.cloudnav.maps.MapsProviderClient
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** One data-driven row of the detail card: the field that matched and its value. */
data class PlaceDetailRow(val field: PoiField, val key: String, val value: String)

/** What the detail card shows for a hit; pure, so it is tested without a screen. */
class PlaceDetail(val meta: String, val rows: List<PlaceDetailRow>, val extra: Map<String, String>)

object PlaceDetailLogic {
    /** [originLat]/[originLon] = the user's current location for the distance readout (NaN when unknown, so the distance is omitted). */
    fun build(hit: MapsProviderClient.SearchHit, originLat: Double, originLon: Double, fields: List<PoiField> = NavConfig.poiDetailFields): PlaceDetail {
        val meta = buildList {
            hit.type?.let { add(prettify(it)) }
            if (!originLat.isNaN() && !originLon.isNaN()) {
                val km = haversineKm(originLat, originLon, hit.lat, hit.lon)
                add(if (km < 1.0) "${(km * 1000).roundToInt()} m away" else "%.1f km away".format(km))
            }
        }.joinToString("  ·  ")
        val shown = HashSet<String>()
        val rows = ArrayList<PlaceDetailRow>()
        fields.forEach { field ->
            val (key, value) = resolve(hit.tags, field) ?: return@forEach
            shown += key
            rows += PlaceDetailRow(field, key, value)
        }
        // Everything the API returned that no row showed; nothing hidden.
        return PlaceDetail(meta, rows, hit.tags.filterKeys { it !in shown && it != "name" })
    }

    /** First present of tag|alt -> (matchedKey, value). Null when none set. */
    fun resolve(tags: Map<String, String>, field: PoiField): Pair<String, String>? {
        (listOf(field.tag) + field.alt).forEach { k ->
            val v = tags[k]
            if (!v.isNullOrBlank()) return k to v
        }
        return null
    }

    fun prettify(s: String) = s.replace('_', ' ').replaceFirstChar { it.uppercase() }

    fun urlOf(raw: String) = if (raw.startsWith("http")) raw else "https://$raw"

    fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}

object PlaceDetailTags {
    const val SHEET = "place:detail"
    const val TITLE = "place:detail:title"
    const val META = "place:detail:meta"
    const val DIRECTIONS = "place:detail:directions"
    fun row(key: String) = "place:detail:row:$key"
}

/**
 * Rich POI detail card (tap a Places pin or result row). Shows everything the provider returned:
 * name, type, distance-from-here, then the data-driven [NavConfig.poiDetailFields] rows
 * (phone/website/hours/address/...) with tap actions, an "Open in maps / Directions" action, and a
 * dump of any remaining raw OSM tags. A Compose bottom sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaceDetailSheet(hit: MapsProviderClient.SearchHit, originLat: Double, originLon: Double, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val p = LocalKitPalette.current
    val d = remember(hit, originLat, originLon) { PlaceDetailLogic.build(hit, originLat, originLon) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = p.surfaceSelected, contentColor = p.textPrimary) {
        Column(Modifier.fillMaxWidth().testTag(PlaceDetailTags.SHEET).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            Text(hit.title, Modifier.testTag(PlaceDetailTags.TITLE), color = p.textPrimary, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (d.meta.isNotEmpty()) Text(d.meta, Modifier.padding(top = 4.dp, bottom = 10.dp).testTag(PlaceDetailTags.META), color = p.accent, style = MaterialTheme.typography.bodyMedium)
            d.rows.forEach { r ->
                val intent: Intent? = when (r.field.kind) {
                    "url" -> Intent(Intent.ACTION_VIEW, Uri.parse(PlaceDetailLogic.urlOf(r.value)))
                    "phone" -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:${r.value}"))
                    "email" -> Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${r.value}"))
                    else -> null
                }
                Text("${r.field.emoji}  ${r.field.label}: ${r.value}",
                    Modifier.fillMaxWidth().testTag(PlaceDetailTags.row(r.key)).then(if (intent != null) Modifier.clickable { openIntent(ctx, intent) } else Modifier).padding(vertical = 9.dp),
                    color = if (intent != null) p.accent else p.textPrimary, style = MaterialTheme.typography.bodyLarge)
            }
            if (d.extra.isNotEmpty()) {
                Text("More (raw OSM tags)", Modifier.padding(top = 14.dp, bottom = 6.dp), color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
                d.extra.forEach { (k, v) ->
                    Text("$k = $v", Modifier.padding(vertical = 2.dp), color = p.textSecondary, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
            // Directions / open-in-maps (geo: intent -> any installed maps app).
            Button(onClick = { openGeo(ctx, hit.lat, hit.lon, hit.title) },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag(PlaceDetailTags.DIRECTIONS),
                colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = p.tileInk)) { Text("🧭  Directions / open in maps") }
        }
    }
}

private fun openGeo(ctx: Context, lat: Double, lon: Double, label: String) {
    val enc = Uri.encode(label)
    openIntent(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon($enc)")))
}

private fun openIntent(ctx: Context, intent: Intent) {
    try { ctx.startActivity(intent) }
    catch (e: ActivityNotFoundException) { Toast.makeText(ctx, "No app to handle that", Toast.LENGTH_SHORT).show() }
}
