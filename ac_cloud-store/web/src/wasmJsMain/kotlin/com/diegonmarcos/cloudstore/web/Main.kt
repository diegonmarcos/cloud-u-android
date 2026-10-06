package com.diegonmarcos.cloudstore.web

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport
import com.diegonmarcos.cloudstore.web.generated.Res
import com.diegonmarcos.superapp.bottomnav.BottomNavHost
import com.diegonmarcos.superapp.bottomnav.NavDecl
import com.diegonmarcos.superapp.bottomnav.PageTabs
import com.diegonmarcos.superapp.bottomnav.islandEntries
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitEmptyState
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.KitSectionHeader
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// The palette is the web page's own declaration; the kit holds no colour literal.
private val StorePalette = KitPalette(
    surface = Color(0xFF14181D), surfaceSelected = Color(0xFF1E252D),
    textPrimary = Color(0xFFE8EAED), textSecondary = Color(0xFF9AA0A6),
    accent = Color(0xFF7CC4FF), hairline = Color(0xFF2A3038), tileInk = Color(0xFF0B0D10),
)

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(document.body!!) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            CloudKitTheme(StorePalette) {
                StoreScreen()
            }
        }
    }
}

/** build.json::ui as the page's build copied it (store-nav.json), turned into the one NavDecl the Android shell reads. */
private fun parseNav(text: String): NavDecl {
    val ui = Json.parseToJsonElement(text).jsonObject
    return NavDecl.parse(
        sections = ui["sections"].toString(),
        bottomNav = ui["bottom_nav"].toString(),
        defaultSection = ui["default_section"]?.jsonPrimitive?.contentOrNull ?: "",
    )
}

@Composable
private fun StoreScreen() {
    var fleet by remember { mutableStateOf<List<FleetApp>?>(null) }
    var nav by remember { mutableStateOf<NavDecl?>(null) }
    LaunchedEffect(Unit) {
        nav = parseNav(Res.readBytes("files/store-nav.json").decodeToString())
        fleet = parseFleet(Res.readBytes("files/constellation-fleet.json").decodeToString())
    }
    val decl = nav
    val rows = fleet
    if (decl == null || rows == null) {
        Box(Modifier.fillMaxSize().padding(12.dp)) { KitEmptyState(null, "Loading the fleet…") }
    } else {
        StoreShell(decl, rows)
    }
}

/** The island of the fleet (libs:bottomnav, the same one the Android store draws) over the section's page. */
@Composable
private fun StoreShell(decl: NavDecl, fleet: List<FleetApp>) {
    var selected by remember { mutableStateOf(decl.default()?.id) }
    var page by remember(selected) { mutableStateOf<String?>(null) }
    val entries = decl.islandEntries { rememberVectorPainter(StoreIcons.of(it)) }
    BottomNavHost(entries = entries, selectedId = selected, onSelect = { selected = it.id }) {
        val section = decl.section(selected)
        Column(Modifier.fillMaxSize()) {
            // A section with several pages gets the fleet's pill strip; the store's four have none yet.
            if (section != null && section.pages.size > 1) {
                PageTabs(section.pages, page ?: section.pages.first().id, onSelect = { page = it.id })
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp)) {
                if (section?.id == "cloud") TileGrid(fleet)
                else KitEmptyState(null, "${section?.label ?: "This"} lives in the Android store for now")
            }
        }
    }
}

/** Dense fleet grid: one kit card per row; the action is the release link (install = download). */
@Composable
private fun TileGrid(fleet: List<FleetApp>) {
    val p = LocalKitPalette.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            KitSectionHeader("Cloud Store", "${fleet.size} fleet entries · install opens the release download")
        }
        items(fleet, key = { it.id }) { app ->
            KitCard {
                Text(app.label, color = p.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(
                    (if (app.kind == "app") "app" else "lib") + " · " + app.version.ifBlank { "—" },
                    color = p.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                )
                TextButton(onClick = { window.open(app.releaseUrl, "_blank") }) { Text("Install", color = p.accent) }
            }
        }
    }
}
