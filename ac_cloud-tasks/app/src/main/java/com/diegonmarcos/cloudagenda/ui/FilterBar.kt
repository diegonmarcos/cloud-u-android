package com.diegonmarcos.cloudagenda.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudagenda.data.Tag
import com.diegonmarcos.cloudagenda.data.TaskFilter
import com.diegonmarcos.cloudagenda.data.TaskStatus
import com.diegonmarcos.superapp.uikit.LocalKitPalette

object FilterTags {
    const val BAR = "filter_bar"
    const val TAGS_BUTTON = "filter_tags"
    const val TAGS_MENU = "filter_tags_menu"
    const val MANAGE = "filter_manage_tags"
    const val CLEAR = "filter_clear"
    fun status(s: TaskStatus) = "filter_status_${s.id}"
    fun tag(id: String) = "filter_tag_$id"
}

/** A tag's colour as a Compose colour (stored as an ARGB Long). */
fun tagColor(c: Long): Color = Color(c.toInt())

/** A small round colour dot. */
@Composable
fun ColorDot(color: Color, modifier: Modifier = Modifier, size: Int = 10) {
    Box(modifier.size(size.dp).clip(CircleShape).background(color))
}

/**
 * The Tasks page's top filter bar: Status chips (multi-select), a Tags menu (multi-select, with a way into the tag
 * manager) and Clear all, which appears only while a filter is on. Stateless: the choice lives in the controller,
 * which persists it.
 */
@Composable
fun FilterBar(
    filter: TaskFilter,
    tags: List<Tag>,
    onChange: (TaskFilter) -> Unit,
    onManageTags: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalKitPalette.current
    var tagsOpen by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp).testTag(FilterTags.BAR),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (s in TaskStatus.entries) {
            FilterChip(
                selected = s in filter.statuses, onClick = { onChange(filter.toggle(s)) },
                label = { Text(s.label) }, modifier = Modifier.testTag(FilterTags.status(s)),
            )
            Spacer(Modifier.width(6.dp))
        }
        Box {
            FilterChip(
                selected = filter.tagIds.isNotEmpty(), onClick = { tagsOpen = true },
                label = { Text(if (filter.tagIds.isEmpty()) "Tags" else "Tags (${filter.tagIds.size})") },
                modifier = Modifier.testTag(FilterTags.TAGS_BUTTON),
            )
            DropdownMenu(expanded = tagsOpen, onDismissRequest = { tagsOpen = false }, modifier = Modifier.testTag(FilterTags.TAGS_MENU)) {
                if (tags.isEmpty()) {
                    DropdownMenuItem(text = { Text("No tags yet", color = p.textSecondary) }, onClick = {}, enabled = false)
                }
                for (t in tags) {
                    DropdownMenuItem(
                        text = { Text(t.name) },
                        leadingIcon = { ColorDot(tagColor(t.color), size = 12) },
                        trailingIcon = { if (t.id in filter.tagIds) Icon(Icons.Filled.Check, contentDescription = "selected") },
                        onClick = { onChange(filter.toggleTag(t.id)) },
                        modifier = Modifier.testTag(FilterTags.tag(t.id)),
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Manage tags") }, modifier = Modifier.testTag(FilterTags.MANAGE),
                    onClick = { tagsOpen = false; onManageTags() },
                )
            }
        }
        if (filter.active) {
            Spacer(Modifier.width(6.dp))
            TextButton(onClick = { onChange(filter.clear()) }, modifier = Modifier.testTag(FilterTags.CLEAR)) { Text("Clear all") }
        }
    }
}
