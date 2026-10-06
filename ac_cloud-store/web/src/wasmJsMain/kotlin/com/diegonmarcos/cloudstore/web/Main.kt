package com.diegonmarcos.cloudstore.web

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport
import com.diegonmarcos.cloudstore.web.generated.Res
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitEmptyState
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.KitSectionHeader
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.browser.document
import kotlinx.browser.window

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
                // #876 part 2: BottomNavHost(NavDecl…) wraps this once libs/bottomnav has commonMain
                StoreScreen()
            }
        }
    }
}

@Composable
private fun StoreScreen() {
    var fleet by remember { mutableStateOf<List<FleetApp>?>(null) }
    LaunchedEffect(Unit) {
        fleet = parseFleet(Res.readBytes("files/constellation-fleet.json").decodeToString())
    }
    Box(Modifier.fillMaxSize().padding(12.dp)) {
        val rows = fleet
        if (rows == null) KitEmptyState(null, "Loading the fleet…") else TileGrid(rows)
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
