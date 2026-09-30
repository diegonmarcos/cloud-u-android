package com.diegonmarcos.cloudc3.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudc3.Declarations

/**
 * #648 the chrome vocabulary every tab draws with, declared ONCE. A screen composes these
 * and holds no dp, sp or colour of its own — the metrics come from [C3Metrics] and the ink
 * from [C3Theme]'s scheme, so the density of the whole app is one edit (the #621
 * DriveMetrics precedent). test/test-c3-shell.sh fails the build on a literal in a screen.
 */

/** What a tab shows when it has nothing to show, or does not exist. Never a blank pane. */
@Composable
fun EmptyState(icon: String, title: String, detail: String) {
    Column(
        Modifier.fillMaxSize().padding(C3Metrics.gutter),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = IconCatalog.painter(icon),
            contentDescription = null,
            modifier = Modifier.size(C3Metrics.tileSize),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(C3Metrics.gutter))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (detail.isNotBlank()) {
            Spacer(Modifier.height(C3Metrics.small))
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** A titled box standing on the page — the one card shape every tab uses. */
@Composable
fun C3Card(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(C3Metrics.corner))
            .background(MaterialTheme.colorScheme.surface)
            .border(C3Metrics.hairline, MaterialTheme.colorScheme.outline, RoundedCornerShape(C3Metrics.corner))
            .padding(C3Metrics.cardPadding),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(C3Metrics.gap))
        content()
    }
}

/**
 * The sub-page strip a content tab wears, built from that tab's DECLARED page list. The
 * same mechanism for all three tabs that have sub-pages, so adding a page is a build.json
 * edit and never a new strip.
 */
@Composable
fun PageStrip(
    pages: List<Declarations.PageDecl>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    LazyRow(
        Modifier.fillMaxWidth().padding(horizontal = C3Metrics.gutter),
        horizontalArrangement = Arrangement.spacedBy(C3Metrics.gap),
    ) {
        items(pages, key = { it.id }) { page ->
            val on = page.id == selectedId
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (on) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                    )
                    .clickable { onSelect(page.id) }
                    .padding(horizontal = C3Metrics.gutter, vertical = C3Metrics.inner),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(C3Metrics.inner),
            ) {
                Icon(
                    painter = IconCatalog.painter(page.icon),
                    contentDescription = null,
                    modifier = Modifier.size(C3Metrics.glyphSmall),
                    tint = if (on) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    page.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (on) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** One key/value line — the unit an ops list is made of. */
@Composable
fun C3Row(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = C3Metrics.rowHeight)
            .padding(vertical = C3Metrics.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A page body that is declared but not yet built, saying so in its own words. */
@Composable
fun NotBuiltYet(page: Declarations.PageDecl) {
    Box(Modifier.fillMaxSize()) {
        EmptyState(icon = page.icon, title = page.label, detail = "")
    }
}
