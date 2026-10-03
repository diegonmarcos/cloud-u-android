package com.diegonmarcos.cloudsearch.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.diegonmarcos.cloudsearch.R

/*
 * #797 the mockup's components, one Composable each: .icon-btn, .dynamic-island, .card (with its
 * slideUp entrance), .filter-chip, .search-input, .sub-nav-btn, .bottom-nav, .overlay, .side-menu,
 * .profile-menu, .menu-item. Colours come from [LocalGlass], sizes from [Metrics] and [Type].
 */

/**
 * The keyboard is up. Read from the IME's bottom inset, NOT WindowInsets.isImeVisible: that one
 * starts out true until the window first dispatches insets (and under Robolectric it never does),
 * which hid the bottom nav from every test.
 */
@Composable
fun imeOpen(): Boolean = WindowInsets.ime.getBottom(LocalDensity.current) > 0

/** A Phosphor icon (res/drawable/ph_*), tinted. */
@Composable
fun Ph(res: Int, size: Dp = Metrics.icon, tint: Color = LocalGlass.current.text, description: String? = null, modifier: Modifier = Modifier) {
    Icon(painterResource(res), contentDescription = description, tint = tint, modifier = modifier.size(size))
}

/** .ai-nav-icon: the icon filled with the three-stop gradient. */
@Composable
fun AiIcon(res: Int, size: Dp = Metrics.icon, modifier: Modifier = Modifier) {
    val brush = LocalGlass.current.aiIcon
    Icon(
        painterResource(res), contentDescription = null, tint = LocalGlass.current.onIsland,
        modifier = modifier.size(size)
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent { drawContent(); drawRect(brush, blendMode = BlendMode.SrcAtop) },
    )
}

/** A declared icon name, in the gradient when it is the assistant's. */
@Composable
fun DeclaredIcon(name: String, size: Dp = Metrics.icon, tint: Color = LocalGlass.current.text) {
    if (IconCatalog.gradient(name)) AiIcon(IconCatalog.res(name), size) else Ph(IconCatalog.res(name), size, tint)
}

/** .icon-btn: a 34 dp glass circle. */
@Composable
fun IconBtn(res: Int, description: String, tag: String, onClick: () -> Unit) {
    val g = LocalGlass.current
    Box(
        Modifier.size(Metrics.iconBtn).clip(CircleShape).background(g.glass)
            .border(Metrics.hairline, g.glassBorder, CircleShape)
            .clickable(onClick = onClick).testTag(tag),
        contentAlignment = Alignment.Center,
    ) { Ph(res, Metrics.icon, g.text, description) }
}

/** .dynamic-island: the black pill naming where you are, or what is running. */
@Composable
fun Island(icon: String, text: String, modifier: Modifier = Modifier) {
    val g = LocalGlass.current
    Row(
        modifier.semantics(mergeDescendants = true) {}.clip(RoundedCornerShape(Metrics.navRadius)).background(g.island)
            .defaultMinSize(minWidth = Metrics.islandMinWidth)
            .padding(horizontal = Metrics.islandPadH, vertical = Metrics.islandPadV),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.gap, Alignment.CenterHorizontally),
    ) {
        DeclaredIcon(icon, Metrics.iconSm, g.onIsland)
        Text(text, color = g.onIsland, style = Type.style(Type.island, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** .card: glass, 12 dp corners, a hairline border, sliding up as it appears. */
@Composable
fun GlassCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val g = LocalGlass.current
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(Metrics.ENTER_MS)) }
    val shape = RoundedCornerShape(Metrics.cardRadius)
    Column(
        modifier.fillMaxWidth()
            .graphicsLayer { alpha = enter.value; translationY = (1f - enter.value) * Metrics.enter.toPx() }
            .clip(shape).background(g.card).border(Metrics.hairline, g.glassBorder, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(Metrics.cardPad),
        verticalArrangement = Arrangement.spacedBy(Metrics.small),
        content = content,
    )
}

/** .filter-chip; [accent] is the filled "More Filters" one, [selected] an applied filter. */
@Composable
fun Chip(text: String, tag: String, selected: Boolean = false, accent: Boolean = false, icon: Int? = null, onClick: () -> Unit) {
    val g = LocalGlass.current
    val fill = accent || selected
    val shape = RoundedCornerShape(Metrics.chipRadius)
    Row(
        Modifier.clip(shape).background(if (fill) g.accent else g.glass)
            .border(Metrics.hairline, if (fill) g.accent else g.glassBorder, shape)
            .clickable(onClick = onClick).testTag(tag)
            .padding(horizontal = Metrics.chipPadH, vertical = Metrics.chipPadV),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.small),
    ) {
        val fg = if (fill) g.onBadge else g.text
        if (icon != null) Ph(icon, Metrics.iconXs, fg)
        Text(text, color = fg, style = Type.style(Type.chip), maxLines = 1)
    }
}

/** A horizontally scrolling row of chips (.basic-filters). */
@Composable
fun ChipRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Metrics.chipGap),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** .search-input: a glass field with a leading icon; [onSearch] fires on the keyboard's search key. */
@Composable
fun GlassField(
    value: String, onValue: (String) -> Unit, placeholder: String, tag: String,
    modifier: Modifier = Modifier, icon: Int? = R.drawable.ph_magnifying_glass, iconTint: Color = LocalGlass.current.text2,
    number: Boolean = false, onSearch: (() -> Unit)? = null, size: androidx.compose.ui.unit.TextUnit = Type.input,
) {
    val g = LocalGlass.current
    val shape = RoundedCornerShape(Metrics.inputRadius)
    BasicTextField(
        value = value, onValueChange = onValue, singleLine = true,
        textStyle = TextStyle(color = g.text, fontSize = size),
        cursorBrush = SolidColor(g.accent),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (number) KeyboardType.Decimal else KeyboardType.Text,
            imeAction = if (onSearch != null) ImeAction.Search else ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() }),
        modifier = modifier.fillMaxWidth().testTag(tag),
        decorationBox = { inner ->
            Row(
                Modifier.clip(shape).background(g.input).border(Metrics.hairline, g.glassBorder, shape)
                    .padding(horizontal = Metrics.inputPadH, vertical = Metrics.inputPadV),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Metrics.gap),
            ) {
                if (icon != null) Ph(icon, Metrics.iconSm, iconTint)
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, color = g.text2, style = TextStyle(fontSize = size), maxLines = 1)
                    inner()
                }
            }
        },
    )
}

/** .topic-title + .topic-desc. */
@Composable
fun TopicHeader(title: String, desc: String) {
    val g = LocalGlass.current
    Column {
        Text(title, color = g.text, style = Type.style(Type.topicTitle, FontWeight.Bold))
        if (desc.isNotBlank()) Text(desc, color = g.text2, style = Type.style(Type.topicDesc), modifier = Modifier.padding(bottom = Metrics.gap))
    }
}

/** .sub-nav-container: underlined text tabs over a hairline. */
@Composable
fun SubNav(items: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    val g = LocalGlass.current
    Column(Modifier.fillMaxWidth().padding(bottom = Metrics.cardPad)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Metrics.cardPad)) {
            items.forEach { (id, label) ->
                val on = id == selected
                Column(
                    Modifier.clickable { onSelect(id) }.testTag(Tags.subpage(id)).padding(top = Metrics.small),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(label, color = if (on) g.accent else g.text2, style = Type.style(Type.subNav, FontWeight.SemiBold),
                        modifier = Modifier.padding(horizontal = Metrics.tiny, vertical = Metrics.small))
                    Box(Modifier.height(Metrics.underline).fillMaxWidth().clip(RoundedCornerShape(Metrics.tiny)).background(if (on) g.accent else Color.Transparent))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(Metrics.hairline).background(g.divider))
    }
}

/** One .bottom-nav entry. */
data class NavEntry(val id: String, val label: String, val icon: String)

/** .bottom-nav: the app's OWN glass pill; the active icon lifts and its label fades in. */
@Composable
fun BottomNav(entries: List<NavEntry>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val g = LocalGlass.current
    val shape = RoundedCornerShape(Metrics.navRadius)
    Row(
        modifier.fillMaxWidth().padding(horizontal = Metrics.gutter).height(Metrics.navHeight)
            .clip(shape).background(g.nav).border(Metrics.hairline, g.glassBorder, shape)
            .padding(horizontal = Metrics.gap),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        entries.forEach { e ->
            val on = e.id == selected
            val lift by animateDpAsState(if (on) -Metrics.navLift else Metrics.zero, label = "lift")
            val label by animateFloatAsState(if (on) 1f else 0f, label = "label")
            Box(
                Modifier.size(Metrics.navItem).clip(CircleShape)
                    .clickable { onSelect(e.id) }.testTag(Tags.nav(e.id)),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.offset(y = lift)) {
                    if (IconCatalog.gradient(e.icon)) AiIcon(IconCatalog.res(e.icon), Metrics.iconNav)
                    else Ph(IconCatalog.res(e.icon), Metrics.iconNav, if (on) g.accent else g.text2)
                }
                Text(
                    e.label, color = if (on) g.accent else g.text2, style = Type.style(Type.navLabel, FontWeight.Medium),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = Metrics.navLabelBottom)
                        .graphicsLayer { alpha = label; translationY = (1f - label) * Metrics.gap.toPx() },
                )
            }
        }
    }
}

/** .overlay: dims and blurs nothing it cannot; a tap closes whatever menu is open. */
@Composable
fun BoxScope.Scrim(visible: Boolean, onDismiss: () -> Unit) {
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.matchParentSize()) {
        Box(
            Modifier.fillMaxSize().background(LocalGlass.current.scrim)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        )
    }
}

/** .side-menu: 80 % wide from the left (Categories, Extensive Filters, Chat Sessions). */
@Composable
fun BoxScope.SideMenu(visible: Boolean, tag: String, content: @Composable ColumnScope.() -> Unit) {
    val g = LocalGlass.current
    AnimatedVisibility(
        visible,
        enter = slideInHorizontally { -it }, exit = slideOutHorizontally { -it },
        modifier = Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(Metrics.SIDE_MENU_FRACTION),
    ) {
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier.weight(1f).fillMaxHeight().background(g.menu).verticalScroll(rememberScrollState())
                    .padding(start = Metrics.gutter, end = Metrics.gutter, top = Metrics.sideMenuTop, bottom = Metrics.gutter)
                    .testTag(tag),
                verticalArrangement = Arrangement.spacedBy(Metrics.gap),
                content = content,
            )
            Box(Modifier.width(Metrics.hairline).fillMaxHeight().background(g.glassBorder))
        }
    }
}

/** .profile-menu: a 220 dp popover under the profile button, scaling out of its corner. */
@Composable
fun BoxScope.ProfilePopover(visible: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val g = LocalGlass.current
    val shape = RoundedCornerShape(Metrics.profileRadius)
    AnimatedVisibility(
        visible,
        enter = scaleIn(initialScale = 0.9f, transformOrigin = TransformOrigin(1f, 0f)) + fadeIn(),
        exit = scaleOut(targetScale = 0.9f, transformOrigin = TransformOrigin(1f, 0f)) + fadeOut(),
        modifier = Modifier.align(Alignment.TopEnd).padding(top = Metrics.profileTop, end = Metrics.gutter),
    ) {
        Column(
            Modifier.width(Metrics.profileWidth).clip(shape).background(g.menu).border(Metrics.hairline, g.glassBorder, shape)
                .verticalScroll(rememberScrollState()).padding(Metrics.gutter),
            verticalArrangement = Arrangement.spacedBy(Metrics.small),
            content = content,
        )
    }
}

/** A menu's title row, with a close button when [onClose] is given. */
@Composable
fun MenuTitle(text: String, onClose: (() -> Unit)? = null, closeDescription: String = "") {
    Row(Modifier.fillMaxWidth().padding(bottom = Metrics.gap), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), color = LocalGlass.current.text, style = Type.style(Type.menuTitle, FontWeight.Bold))
        if (onClose != null) IconBtn(R.drawable.ph_x, closeDescription, Tags.CLOSE_MENU, onClose)
    }
}

/** .menu-item: icon + text, a soft highlight. */
@Composable
fun MenuItem(text: String, tag: String, color: Color = LocalGlass.current.text, icon: @Composable () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Metrics.menuRadius)).clickable(onClick = onClick).testTag(tag).padding(Metrics.menuItemPad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.cardPad),
    ) {
        icon()
        Text(text, color = color, style = Type.style(Type.menuItem))
    }
}

/** The uppercase, tracked micro-label the mockup puts over every field. */
@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), color = LocalGlass.current.text2, style = Type.style(Type.label, FontWeight.Bold), modifier = modifier)
}

/** A hairline divider in the theme's light border colour. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Spacer(modifier.fillMaxWidth().height(Metrics.hairline).background(LocalGlass.current.divider))
}

/** .card-badge: a price (green) or a tag (accent). */
@Composable
fun Badge(text: String, color: Color) {
    Text(
        text, color = LocalGlass.current.onBadge, style = Type.style(Type.badge, FontWeight.Medium), maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(Metrics.chipGap)).background(color).padding(horizontal = Metrics.chipGap, vertical = Metrics.tiny),
    )
}
