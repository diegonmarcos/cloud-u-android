package com.diegonmarcos.cloudnav

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudnav.maps.MapsProviderClient.SearchHit
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/** The candidates a forward search offers: the top `ui.search_choices`, never fewer than one. */
object SearchChoices {
    fun top(hits: List<SearchHit>, n: Int): List<SearchHit> = hits.take(n.coerceAtLeast(1))

    /** A candidate's line in the picker: the title, over the subtitle when there is one. */
    fun label(h: SearchHit): String = if (h.subtitle.isNullOrBlank()) h.title else "${h.title}\n${h.subtitle}"
}

object NavSearchTags {
    const val PICKER = "search:picker"
    const val CANCEL = "search:picker:cancel"
    fun choice(i: Int) = "search:picker:$i"
    fun chip(id: String) = "chip:$id"
}

/**
 * Offers the top forward-search candidates and lets the user pick the right one (instead of
 * auto-taking the first hit). One candidate is taken directly. A page holds one of these and
 * calls [Host] once in its composition. The caller handles the empty case before offering.
 */
class ResultPicker {
    private var pending by mutableStateOf<Pair<List<SearchHit>, (SearchHit) -> Unit>?>(null)

    fun offer(hits: List<SearchHit>, onPick: (SearchHit) -> Unit) {
        if (hits.isEmpty()) return
        val top = SearchChoices.top(hits, com.diegonmarcos.cloudnav.maps.BuildConfig.UI_SEARCH_CHOICES)
        if (top.size == 1) onPick(top[0]) else pending = top to onPick
    }

    @Composable
    fun Host() {
        val cur = pending ?: return
        ChooseResultDialog(cur.first, onPick = { pending = null; cur.second(it) }, onDismiss = { pending = null })
    }
}

@Composable
fun ChooseResultDialog(hits: List<SearchHit>, onPick: (SearchHit) -> Unit, onDismiss: () -> Unit) {
    val p = LocalKitPalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.surfaceSelected,
        titleContentColor = p.textPrimary,
        textContentColor = p.textPrimary,
        title = { Text("Pick the right place") },
        text = {
            Column(Modifier.testTag(NavSearchTags.PICKER).verticalScroll(rememberScrollState())) {
                hits.forEachIndexed { i, h ->
                    Column(Modifier.fillMaxWidth().testTag(NavSearchTags.choice(i)).clickable(role = Role.Button) { onPick(h) }.padding(vertical = 8.dp)) {
                        Text(h.title, color = p.textPrimary, style = MaterialTheme.typography.bodyLarge)
                        h.subtitle?.takeIf { it.isNotBlank() }?.let { Text(it, color = p.textSecondary, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag(NavSearchTags.CANCEL)) { Text("Cancel", color = p.accent) } },
    )
}

/** A rounded pill over the map: the scope, category and mode islands. */
@Composable
fun NavChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, tag: String = label) {
    val p = LocalKitPalette.current
    val shape = RoundedCornerShape(50)
    Text(label,
        modifier.testTag(NavSearchTags.chip(tag)).clip(shape).background(if (selected) p.accent else p.surface)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        color = if (selected) p.tileInk else p.textPrimary, style = MaterialTheme.typography.labelLarge, maxLines = 1)
}
