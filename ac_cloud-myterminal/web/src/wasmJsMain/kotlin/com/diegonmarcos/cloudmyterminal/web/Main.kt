package com.diegonmarcos.cloudmyterminal.web

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.KitSectionHeader
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.browser.document

// The palette is the web page's own declaration; the kit holds no colour literal.
private val PagePalette = KitPalette(
    surface = Color(0xFF14181D), surfaceSelected = Color(0xFF1E252D),
    textPrimary = Color(0xFFE8EAED), textSecondary = Color(0xFF9AA0A6),
    accent = Color(0xFF7CC4FF), hairline = Color(0xFF2A3038), tileInk = Color(0xFF0B0D10),
)

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(document.body!!) {
        CloudKitTheme(PagePalette) {
            MockPage()
        }
    }
}

private class MockRow(val title: String, val detail: String)

// Mock data only: the Android app keeps the real thing in its own store; the web page has none.
private val MOCK_ROWS = listOf(
    MockRow("local", "bash \u00b7 3 windows"),
    MockRow("build-box", "ssh \u00b7 idle 12 min"),
    MockRow("notes", "tmux \u00b7 detached"),
    MockRow("scratch", "zsh \u00b7 new"),
)

/** Cloud My Terminal on the web: a dense list of mock data (sample data). */
@Composable
private fun MockPage() {
    val p = LocalKitPalette.current
    Box(Modifier.fillMaxSize().padding(12.dp)) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { KitSectionHeader("Sessions", "${MOCK_ROWS.size} entries · sample data") }
            items(MOCK_ROWS, key = { it.title }) { r ->
                KitCard {
                    Text(r.title, color = p.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text(r.detail, color = p.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
        }
    }
}
