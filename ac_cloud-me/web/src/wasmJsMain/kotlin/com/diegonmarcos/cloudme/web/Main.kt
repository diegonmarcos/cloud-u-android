package com.diegonmarcos.cloudme.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.diegonmarcos.cloudme.web.generated.Res
import com.diegonmarcos.superapp.bottomnav.BottomNavHost
import com.diegonmarcos.superapp.bottomnav.NavDecl
import com.diegonmarcos.superapp.bottomnav.PageTabs
import com.diegonmarcos.superapp.bottomnav.islandEntries
import com.diegonmarcos.fleet.web.FleetWebTheme
import com.diegonmarcos.superapp.uikit.KitEmptyState
import com.diegonmarcos.superapp.uikit.KitPalette
import kotlinx.browser.document
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// The palette is the web page's own declaration; the kit holds no colour literal.
private val NavPalette = KitPalette(
    surface = Color(0xFF14181D), surfaceSelected = Color(0xFF1E252D),
    textPrimary = Color(0xFFE8EAED), textSecondary = Color(0xFF9AA0A6),
    accent = Color(0xFF7CC4FF), hairline = Color(0xFF2A3038), tileInk = Color(0xFF0B0D10),
)

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(document.body!!) {
        FleetWebTheme(NavPalette) {
            NavScreen()
        }
    }
}

/** build.json::ui as the page's build copied it (me-nav.json), turned into the one NavDecl the Android shell reads. */
private fun parseNav(text: String): NavDecl {
    val ui = Json.parseToJsonElement(text).jsonObject
    return NavDecl.parse(
        sections = ui["sections"].toString(),
        bottomNav = ui["bottom_nav"].toString(),
        defaultSection = ui["default_section"]?.jsonPrimitive?.contentOrNull ?: "",
    )
}

@Composable
private fun NavScreen() {
    var nav by remember { mutableStateOf<NavDecl?>(null) }
    LaunchedEffect(Unit) {
        nav = parseNav(Res.readBytes("files/me-nav.json").decodeToString())
    }
    val decl = nav
    if (decl == null) {
        Box(Modifier.fillMaxSize().padding(12.dp)) { KitEmptyState(null, "Loading Cloud Me…") }
    } else {
        NavShell(decl)
    }
}

/** The island of the fleet (libs:bottomnav, the one the Android app draws) over the section's page. */
@Composable
private fun NavShell(decl: NavDecl) {
    var selected by remember { mutableStateOf(decl.default()?.id) }
    var page by remember(selected) { mutableStateOf<String?>(null) }
    val entries = decl.islandEntries { rememberVectorPainter(GenericIcons.of(it)) }
    BottomNavHost(entries = entries, selectedId = selected, onSelect = { selected = it.id }) {
        val section = decl.section(selected)
        val pageId = page ?: section?.pages?.firstOrNull()?.id
        Column(Modifier.fillMaxSize()) {
            if (section != null && section.pages.size > 1) {
                PageTabs(section.pages, pageId ?: section.pages.first().id, onSelect = { page = it.id })
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp)) {
                // No store on the web: every page of every section, Buro and LLC included, is the
                // declared page (build.json::ui) with an honest empty state, never invented rows.
                val where = listOfNotNull(section?.label, section?.pages?.firstOrNull { it.id == pageId }?.label)
                    .distinct().joinToString(" \u203a ").ifEmpty { "This" }
                KitEmptyState(null, "$where has no data on the web yet; it lives in the Android app")
            }
        }
    }
}
