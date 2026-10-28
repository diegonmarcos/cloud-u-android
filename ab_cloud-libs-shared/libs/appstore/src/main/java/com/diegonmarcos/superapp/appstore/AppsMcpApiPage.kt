package com.diegonmarcos.superapp.appstore

import android.util.Base64
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/**
 * #1211 Store > Access > Apps MCP-API (after Apps Mesh): one [StoreRow.Item] per app of Store > Cloud > Apps, the same row
 * the Cloud and Phone pages draw. Collapsed, the head says what is missing; open, every endpoint of the three kinds.
 * Data: [AppsMcpApi] over assets/apps-mcp-api.json and the baked fleet.
 */
@Composable
fun AppsMcpApiPage() {
    val ctx = LocalContext.current
    val decl = remember { AppsMcpApi.load(ctx) }
    val rows = remember {
        val fleet = JSONObject(String(Base64.decode(BuildConfig.CONSTELLATION_FLEET_B64, Base64.DEFAULT)))
        val groups = fleet.optJSONArray("groups")
        val members = (0 until (groups?.length() ?: 0)).map { groups!!.getJSONObject(it) }
            .firstOrNull { it.optString("id") == decl.group }?.optJSONArray("members")
        val byId = com.diegonmarcos.superapp.updater.Fleet.parse(BuildConfig.CONSTELLATION_FLEET_B64).associateBy { it.id }
        AppsMcpApi.rows(decl, (0 until (members?.length() ?: 0)).mapNotNull { byId[members!!.getString(it)] }
            .sortedBy { it.label.lowercase() }.map { it.id to it.label })
    }
    var open by remember { mutableStateOf(setOf<String>()) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(sdp(StoreDensity.S12))) {
        StoreRow.Summary(AppsMcpApi.summary(decl, rows), AppsMcpApi.TAG_SUMMARY)
        for (r in rows) {
            val missing = r.missing(decl.kinds)
            val isOpen = r.id in open
            StoreRow.Item(r.label, isOpen, { open = if (isOpen) open - r.id else open + r.id }, tag = AppsMcpApi.TAG_ROW + r.id,
                dot = if (missing.isEmpty()) "✓" else "◯", dotColor = if (missing.isEmpty()) OK else GAP,
                meta = if (missing.isEmpty()) "complete" else "missing " + missing.joinToString(", ") { it.label },
                metaColor = if (missing.isEmpty()) OK else GAP, metaTag = AppsMcpApi.TAG_MISSING + r.id) {
                for (k in decl.kinds) {
                    val eps = r.byKind[k.id].orEmpty()
                    Text(k.label + " - " + k.what + if (k.id in r.inherited) "  (default: ${decl.lib})" else "",
                        color = Ink.DIM.ink(), fontSize = StoreDensity.T_META.sp)
                    if (eps.isEmpty()) Text("MISSING", color = GAP.ink(), fontSize = StoreDensity.T_CAPTION.sp)
                    eps.forEach { Text("${it.method} ${it.url}", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = StoreDensity.T_CAPTION.sp) }
                }
            }
        }
    }
}

private val OK = 0xFF48BB78.toInt()
private val GAP = 0xFFED8936.toInt()
