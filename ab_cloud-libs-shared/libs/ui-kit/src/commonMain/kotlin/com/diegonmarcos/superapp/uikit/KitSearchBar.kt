package com.diegonmarcos.superapp.uikit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow

/** Test tags of [KitSearchBar]. */
object KitSearchTags {
    const val BAR = "kit:search"
    const val FIELD = "kit:search:field"
    const val CLEAR = "kit:search:clear"
}

/**
 * A one-line, data-dense search field: a drawn magnifier, the text on the [KitDensity.body]
 * scale, and a clear (×) that appears only while there is text. The keyboard's action is Go,
 * which calls [onSubmit] — the host decides what "go" means (Cloud ▸ Apps launches its top
 * match). Stateless: the host owns [query], so it can clear it on Back or after a launch.
 *
 * The magnifier is drawn rather than a vector resource so this file stays wasm-pure (#876).
 */
@Composable
fun KitSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search",
    clearLabel: String = "Clear search",
) {
    val p = LocalKitPalette.current
    val shape = RoundedCornerShape(KitDensity.corner)
    Row(
        modifier
            .testTag(KitSearchTags.BAR)
            .fillMaxWidth()
            .height(KitDensity.glyph + KitDensity.small)
            .clip(shape)
            .background(p.surface)
            .border(KitDensity.rule, p.hairline, shape)
            .padding(start = KitDensity.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ink = p.textSecondary
        Canvas(Modifier.size(KitDensity.medium)) {
            val stroke = KitDensity.rule.toPx() * 1.5f
            val r = size.minDimension * 0.34f
            val c = Offset(r + stroke, r + stroke)
            drawCircle(ink, radius = r, center = c, style = Stroke(stroke))
            val d = r * 0.7071f
            drawLine(ink, Offset(c.x + d, c.y + d), Offset(size.width, size.height), stroke)
        }
        Box(Modifier.weight(1f).padding(horizontal = KitDensity.small), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(placeholder, color = p.textSecondary, fontSize = KitDensity.body, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = p.textPrimary, fontSize = KitDensity.body),
                cursorBrush = SolidColor(p.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onSubmit() }),
                modifier = Modifier.fillMaxWidth().testTag(KitSearchTags.FIELD),
            )
        }
        if (query.isNotEmpty()) {
            Box(
                Modifier
                    .testTag(KitSearchTags.CLEAR)
                    .size(KitDensity.glyph)
                    .clickable(role = Role.Button) { onQueryChange("") }
                    .semantics { contentDescription = clearLabel },
                contentAlignment = Alignment.Center,
            ) {
                Text("×", color = p.textSecondary, fontSize = KitDensity.title)
            }
        } else {
            Box(Modifier.size(KitDensity.small))
        }
    }
}
