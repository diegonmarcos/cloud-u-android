package com.diegonmarcos.superapp.bottomnav

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Every number and colour of the search-html bar, restated by SearchHtmlIslandTest as literals. */
public object SearchHtmlTokens {
    public val height: Dp = 60.dp
    public val radius: Dp = 30.dp
    public val sideGutter: Dp = 16.dp
    public val maxWidth: Dp = 400.dp
    public val bottomMargin: Dp = 20.dp
    public val innerPad: Dp = 8.dp
    public val cell: Dp = 50.dp
    public val lift: Dp = 6.dp
    public val labelBottom: Dp = 4.dp
    public val iconSize: Dp = 21.dp
    public val hairline: Dp = 1.dp
    public val labelSize: TextUnit = 9.6.sp

    public fun fill(dark: Boolean): Color = if (dark) Color(0xB30F172A) else Color(0x99FFFFFF)
    public fun border(dark: Boolean): Color = if (dark) Color(0x1AFFFFFF) else Color(0x66FFFFFF)
    public fun accent(dark: Boolean): Color = if (dark) Color(0xFF0A84FF) else Color(0xFF007AFF)
    public fun idle(dark: Boolean): Color = if (dark) Color(0xFF94A3B8) else Color(0xFF5A5A5E)

    /** `.ai-nav-icon`: linear-gradient(135deg, #ff416c, #8a2387, #24d292). */
    public val gradient: List<Color> = listOf(Color(0xFFFF416C), Color(0xFF8A2387), Color(0xFF24D292))
}

/**
 * Cloud Search's `.bottom-nav` as the owner's HTML mockup draws it (cloud-search-mockup.html, the
 * `.bottom-nav` / `.nav-item` / `.nav-icon` / `.nav-label` / `.ai-nav-icon` rules): the ONE declared
 * exception to the fleet's island look ([NavStyle.SearchHtml]).
 *
 * It is a VARIANT OF THIS LIBRARY, not an app's own nav: an app does not pass a colour, a size or a
 * shape, it declares `build.json::ui.style = "search-html"` and the nav-shape guard (rule N8) lets
 * exactly the apps named in nav-shape.json::variants do so. Every other app keeps [BottomNavIsland].
 * It is Android-only (src/main): the web page has no Cloud Search.
 *
 * The look: a 60 dp glass pill inset 16 dp from each side (at most 400 dp wide) with a hairline
 * border, five 50 dp round cells spaced around, an icon per cell and a label that is hidden until
 * its cell is selected: then the icon lifts 6 dp, the label fades up from the cell's bottom edge
 * and both take the accent colour. [dark] picks the mockup's `.dark` or `:root` tokens and
 * [gradientIds] are the entries whose icon is drawn in the `.ai-nav-icon` gradient. The tags are the
 * island's own (item, icon, label), so a test reaches this bar the way it reaches the fleet's.
 */
@Composable
public fun SearchHtmlIsland(
    entries: List<BottomNavEntry>,
    selectedId: String?,
    onSelect: (BottomNavEntry) -> Unit,
    dark: Boolean,
    modifier: Modifier = Modifier,
    gradientIds: Set<String> = emptySet(),
) {
    val t = SearchHtmlTokens
    val shape = RoundedCornerShape(t.radius)
    val labelStyle = TextStyle(
        fontSize = t.labelSize,
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontStyle = FontStyle.Normal,
    )
    // As the fleet's island: an app's MaterialTheme text style must not reach the labels.
    CompositionLocalProvider(LocalTextStyle provides TextStyle.Default) {
        // The system bars are READ, never consumed (#477): a shell that already cleared them adds none.
        Box(
            modifier.fillMaxWidth()
                .windowInsetsPadding(bottomNavInsets().only(WindowInsetsSides.Bottom))
                .padding(bottom = t.bottomMargin, start = t.sideGutter, end = t.sideGutter),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Row(
                Modifier.widthIn(max = t.maxWidth).fillMaxWidth().height(t.height)
                    .testTag(TAG_ISLAND)
                    .clip(shape).background(t.fill(dark)).border(t.hairline, t.border(dark), shape)
                    .padding(horizontal = t.innerPad),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                entries.forEach { e ->
                    val on = e.id == selectedId
                    val ink = if (on) t.accent(dark) else t.idle(dark)
                    val lift by animateDpAsState(if (on) -t.lift else 0.dp, label = "search_nav_lift")
                    val label by animateFloatAsState(if (on) 1f else 0f, label = "search_nav_label")
                    Box(
                        Modifier.size(t.cell).clip(CircleShape)
                            .testTag(itemTag(e.id))
                            .selectable(
                                selected = on,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = FleetIndication,
                                role = Role.Tab,
                                onClick = { onSelect(e) },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.offset(y = lift)) {
                            val iconMod = Modifier.size(t.iconSize).testTag(iconTag(e.id))
                            if (e.id in gradientIds) {
                                val brush = Brush.linearGradient(t.gradient)
                                Icon(
                                    e.icon, contentDescription = null, tint = Color.White,
                                    modifier = iconMod
                                        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                                        .drawWithContent { drawContent(); drawRect(brush, blendMode = BlendMode.SrcAtop) },
                                )
                            } else Icon(e.icon, contentDescription = null, tint = ink, modifier = iconMod)
                        }
                        Text(
                            e.label, color = ink, style = labelStyle, textAlign = TextAlign.Center,
                            maxLines = 1, overflow = TextOverflow.Clip, softWrap = false,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = t.labelBottom)
                                .testTag(labelTag(e.id))
                                .graphicsLayer { alpha = label; translationY = (1f - label) * 8.dp.toPx() },
                        )
                    }
                }
            }
        }
    }
}
