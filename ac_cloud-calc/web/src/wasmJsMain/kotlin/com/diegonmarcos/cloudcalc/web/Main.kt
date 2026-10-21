package com.diegonmarcos.cloudcalc.web

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport
import com.diegonmarcos.cloudcalc.web.generated.Res
import com.diegonmarcos.superapp.bottomnav.BottomNavHost
import com.diegonmarcos.superapp.bottomnav.NavDecl
import com.diegonmarcos.superapp.bottomnav.PageTabs
import com.diegonmarcos.superapp.bottomnav.islandEntries
import com.diegonmarcos.fleet.web.FleetWebTheme
import com.diegonmarcos.superapp.uikit.KitEmptyState
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.browser.document
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// The palette is the web page's own declaration; the kit holds no colour literal.
private val CalcPalette = KitPalette(
    surface = Color(0xFF14181D), surfaceSelected = Color(0xFF1E252D),
    textPrimary = Color(0xFFE8EAED), textSecondary = Color(0xFF9AA0A6),
    accent = Color(0xFF7CC4FF), hairline = Color(0xFF2A3038), tileInk = Color(0xFF0B0D10),
)

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(document.body!!) {
        FleetWebTheme(CalcPalette) {
            CalcScreen()
        }
    }
}

/** build.json::ui as the page's build copied it (calc-nav.json), turned into the one NavDecl the Android shell reads. */
private fun parseNav(text: String): NavDecl {
    val ui = Json.parseToJsonElement(text).jsonObject
    return NavDecl.parse(
        sections = ui["sections"].toString(),
        bottomNav = ui["bottom_nav"].toString(),
        defaultSection = ui["default_section"]?.jsonPrimitive?.contentOrNull ?: "",
    )
}

@Composable
private fun CalcScreen() {
    var nav by remember { mutableStateOf<NavDecl?>(null) }
    LaunchedEffect(Unit) {
        nav = parseNav(Res.readBytes("files/calc-nav.json").decodeToString())
    }
    val decl = nav
    if (decl == null) {
        Box(Modifier.fillMaxSize().padding(12.dp)) { KitEmptyState(null, "Loading Cloud Calc…") }
    } else {
        CalcShell(decl)
    }
}

/** The island of the fleet (libs:bottomnav, the one the Android app draws) over the section's page. */
@Composable
private fun CalcShell(decl: NavDecl) {
    var selected by remember { mutableStateOf(decl.default()?.id) }
    var page by remember(selected) { mutableStateOf<String?>(null) }
    val entries = decl.islandEntries { rememberVectorPainter(CalcIcons.of(it)) }
    BottomNavHost(entries = entries, selectedId = selected, onSelect = { selected = it.id }) {
        val section = decl.section(selected)
        val pageId = page ?: section?.pages?.firstOrNull()?.id
        Column(Modifier.fillMaxSize()) {
            if (section != null && section.pages.size > 1) {
                PageTabs(section.pages, pageId ?: section.pages.first().id, onSelect = { page = it.id })
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp)) {
                if (section?.id == "calculator" && pageId == "calc") Keypad()
                else KitEmptyState(null, "${section?.label ?: "This"} lives in the Android app for now")
            }
        }
    }
}

private val KEYS = listOf(
    listOf("C", "⌫", "%", "÷"),
    listOf("7", "8", "9", "×"),
    listOf("4", "5", "6", "-"),
    listOf("1", "2", "3", "+"),
    listOf("0", ".", "="),
)

private fun press(expr: String, key: String): String = when (key) {
    "C" -> ""
    "⌫" -> expr.dropLast(1)
    "=" -> Eval.of(expr)?.let { Eval.show(it) } ?: expr
    else -> expr + key
}

/** Calculator > Calc: the Standard keypad. The live result is shown under the expression as you type. */
@Composable
private fun Keypad() {
    val p = LocalKitPalette.current
    var expr by remember { mutableStateOf("") }
    val live = Eval.of(expr)?.let { Eval.show(it) } ?: ""
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.End) {
            Text(expr.ifEmpty { "0" }, color = p.textPrimary, style = MaterialTheme.typography.headlineMedium, maxLines = 2, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
            Text(live, color = p.textSecondary, style = MaterialTheme.typography.titleMedium, maxLines = 1, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
        }
        for (row in KEYS) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (key in row) {
                    val op = key in setOf("÷", "×", "-", "+", "=")
                    Box(
                        Modifier.weight(if (key == "=") 2f else 1f).heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (op) p.accent else p.surfaceSelected)
                            .clickable { expr = press(expr, key) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (key == "⌫") Icon(rememberVectorPainter(CalcIcons.backspace), "Backspace", tint = p.textPrimary, modifier = Modifier.size(22.dp))
                        else Text(key, color = if (op) p.tileInk else p.textPrimary, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}
