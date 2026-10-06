package com.diegonmarcos.superapp.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/** #886 what the topics panel shows. [note] explains where the content came from (and, for on-device ML, why it is only an outline). */
class TopicsState {
    var loading by mutableStateOf(true)
    var topics by mutableStateOf<List<PageTopics.Topic>>(emptyList())
    var engine by mutableStateOf("")
    var note by mutableStateOf("")
    var error by mutableStateOf("")
}

/** Summary by topics, as a sheet over the page: a line naming the engine, the topics, the note. */
@Composable
fun BrowserTopicsPanel(state: TopicsState, onCopy: () -> Unit, onClose: () -> Unit) {
    val p = LocalKitPalette.current
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(onClick = onClose)) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = 620.dp)
                .background(p.surface, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .clickable(remember { MutableInteractionSource() }, null) {}
                .verticalScroll(rememberScrollState()).padding(16.dp).testTag("browser:topics"),
        ) {
            Text("Summary by topics", color = p.textPrimary, style = MaterialTheme.typography.titleLarge)
            if (state.engine.isNotBlank())
                Text(state.engine, color = p.textSecondary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 2.dp))
            if (state.loading) Text("Reading the page…", color = p.textSecondary, modifier = Modifier.padding(vertical = 16.dp))
            if (state.note.isNotBlank())
                Text(state.note, color = p.accent, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp).testTag("browser:topics:note"))
            if (state.error.isNotBlank())
                Text(state.error, color = Color(0xFFFF8A80), modifier = Modifier.padding(top = 8.dp).testTag("browser:topics:error"))
            state.topics.forEach { t ->
                Text(t.title, color = p.textPrimary, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 14.dp))
                t.points.forEach { pt ->
                    Row(Modifier.padding(top = 3.dp)) {
                        Text("•  ", color = p.accent)
                        Text(pt, color = p.textPrimary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                if (state.topics.isNotEmpty()) TextButton(onCopy) { Text("Copy") }
                TextButton(onClose) { Text("Close") }
            }
        }
    }
}
