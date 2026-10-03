package com.diegonmarcos.cloudsearch.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.diegonmarcos.cloudsearch.R

/**
 * #797 the owner's mockup's design tokens — its CSS custom properties for light (:root) and dark
 * (.dark), plus the fixed colours its Tailwind classes name — read from res/values/colors.xml.
 * Every screen draws with these; no Kotlin file names a colour literal.
 */
@Immutable
data class Glass(
    val dark: Boolean,
    val glass: Color, val glassBorder: Color, val accent: Color, val bgStart: Color, val bgEnd: Color,
    val text: Color, val text2: Color, val card: Color, val nav: Color, val input: Color, val menu: Color,
    val divider: Color, val field: Color, val tile: Color, val tileBorder: Color, val chatBar: Color,
    val positive: Color, val negative: Color,
    val island: Color, val onIsland: Color, val scrim: Color, val priceBadge: Color, val onBadge: Color,
    val userBubble: Color, val onUserBubble: Color, val aiStart: Color, val aiEnd: Color,
    val aiNav: List<Color>,
) {
    /** The body's 135° gradient. */
    val background: Brush get() = Brush.linearGradient(listOf(bgStart, bgEnd))
    /** from-blue-500 to-purple-500: the assistant's avatar, send button and greeting. */
    val ai: Brush get() = Brush.linearGradient(listOf(aiStart, aiEnd))
    /** .ai-nav-icon's three-stop gradient. */
    val aiIcon: Brush get() = Brush.linearGradient(aiNav)
}

val LocalGlass = staticCompositionLocalOf<Glass> { error("SearchTheme provides the glass tokens") }

@Composable
fun SearchTheme(dark: Boolean, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    fun c(id: Int) = Color(ContextCompat.getColor(ctx, id))
    fun t(d: Int, l: Int) = c(if (dark) d else l)
    val g = Glass(
        dark = dark,
        glass = t(R.color.dark_glass_bg, R.color.light_glass_bg),
        glassBorder = t(R.color.dark_glass_border, R.color.light_glass_border),
        accent = t(R.color.dark_accent, R.color.light_accent),
        bgStart = t(R.color.dark_bg_start, R.color.light_bg_start),
        bgEnd = t(R.color.dark_bg_end, R.color.light_bg_end),
        text = t(R.color.dark_text_main, R.color.light_text_main),
        text2 = t(R.color.dark_text_secondary, R.color.light_text_secondary),
        card = t(R.color.dark_card_bg, R.color.light_card_bg),
        nav = t(R.color.dark_nav_bg, R.color.light_nav_bg),
        input = t(R.color.dark_input_bg, R.color.light_input_bg),
        menu = t(R.color.dark_menu_bg, R.color.light_menu_bg),
        divider = t(R.color.dark_border_light, R.color.light_border_light),
        field = t(R.color.dark_field_bg, R.color.light_field_bg),
        tile = t(R.color.dark_tile_bg, R.color.light_tile_bg),
        tileBorder = t(R.color.dark_tile_border, R.color.light_tile_border),
        chatBar = t(R.color.dark_chat_bar, R.color.light_chat_bar),
        positive = t(R.color.dark_positive, R.color.light_positive),
        negative = t(R.color.dark_negative, R.color.light_negative),
        island = c(R.color.island_bg), onIsland = c(R.color.on_island), scrim = c(R.color.scrim),
        priceBadge = c(R.color.price_badge), onBadge = c(R.color.on_badge),
        userBubble = c(R.color.user_bubble), onUserBubble = c(R.color.on_user_bubble),
        aiStart = c(R.color.ai_start), aiEnd = c(R.color.ai_end),
        aiNav = listOf(c(R.color.ai_nav_1), c(R.color.ai_nav_2), c(R.color.ai_nav_3)),
    )
    // Material controls (text selection, switches, dropdowns) take the same tokens.
    val scheme = if (dark) darkColorScheme(
        primary = g.accent, onPrimary = g.onBadge, secondary = g.accent, background = g.bgStart, onBackground = g.text,
        surface = g.menu, onSurface = g.text, surfaceVariant = g.card, onSurfaceVariant = g.text2, outline = g.glassBorder,
        error = g.negative, surfaceContainer = g.menu, surfaceContainerHigh = g.menu, surfaceContainerLow = g.menu,
    ) else lightColorScheme(
        primary = g.accent, onPrimary = g.onBadge, secondary = g.accent, background = g.bgStart, onBackground = g.text,
        surface = g.menu, onSurface = g.text, surfaceVariant = g.card, onSurfaceVariant = g.text2, outline = g.glassBorder,
        error = g.negative, surfaceContainer = g.menu, surfaceContainerHigh = g.menu, surfaceContainerLow = g.menu,
    )
    MaterialTheme(colorScheme = scheme, typography = Type.material) {
        CompositionLocalProvider(LocalGlass provides g, LocalContentColor provides g.text, content = content)
    }
}

/** A colour of colors.xml by name (an engine's declared accent, a vertical's chart colour); the accent when unknown. */
@Composable
fun namedColor(name: String): Color {
    val ctx = LocalContext.current
    val id = ctx.resources.getIdentifier(name, "color", ctx.packageName)
    return if (id == 0) LocalGlass.current.accent else Color(ContextCompat.getColor(ctx, id))
}

/** The mockup's spacing, declared once (its px at 1 px = 1 dp): no screen holds a dp literal. */
object Metrics {
    val zero: Dp = 0.dp
    val hairline: Dp = 1.dp
    val underline: Dp = 2.dp
    val tiny: Dp = 2.dp
    val small: Dp = 4.dp
    val gap: Dp = 8.dp
    val gutter: Dp = 16.dp
    val topOffset: Dp = 15.dp
    val iconBtn: Dp = 34.dp
    val islandPadH: Dp = 16.dp
    val islandPadV: Dp = 6.dp
    val islandMinWidth: Dp = 120.dp
    val contentTop: Dp = 70.dp
    val contentBottom: Dp = 96.dp
    val cardRadius: Dp = 12.dp
    val cardPad: Dp = 10.dp
    val chipPadH: Dp = 10.dp
    val chipPadV: Dp = 4.dp
    val chipRadius: Dp = 10.dp
    val chipGap: Dp = 6.dp
    val inputRadius: Dp = 10.dp
    val inputPadV: Dp = 8.dp
    val inputPadH: Dp = 12.dp
    val navHeight: Dp = 60.dp
    val navRadius: Dp = 30.dp
    val navBottom: Dp = 20.dp
    val navItem: Dp = 50.dp
    val navLift: Dp = 6.dp
    val navLabelBottom: Dp = 4.dp
    val sideMenuTop: Dp = 40.dp
    const val SIDE_MENU_FRACTION = 0.8f
    val profileWidth: Dp = 220.dp
    val profileTop: Dp = 60.dp
    val profileRadius: Dp = 20.dp
    val menuItemPad: Dp = 10.dp
    val menuRadius: Dp = 10.dp
    val placeholderHeight: Dp = 80.dp
    val placeholderRadius: Dp = 8.dp
    val chartHeight: Dp = 80.dp
    val barGap: Dp = 6.dp
    val tileRadius: Dp = 8.dp
    val tilePad: Dp = 6.dp
    val avatar: Dp = 24.dp
    val profileAvatar: Dp = 48.dp
    val aiAvatar: Dp = 32.dp
    val robot: Dp = 64.dp
    val chatBarRadius: Dp = 28.dp
    val sendButton: Dp = 40.dp
    val bubbleRadius: Dp = 24.dp
    val bubbleTail: Dp = 4.dp
    val bubblePadH: Dp = 16.dp
    val bubblePadV: Dp = 12.dp
    const val BUBBLE_MAX_FRACTION = 0.85f
    val modelMaxWidth: Dp = 160.dp
    val iconXs: Dp = 11.dp
    val iconSm: Dp = 14.dp
    val icon: Dp = 18.dp
    val iconNav: Dp = 21.dp
    val iconLg: Dp = 30.dp
    val enter: Dp = 20.dp
    const val ENTER_MS = 400
    /** Bar shades of a chart, strongest first (the mockup's -500 … -200 steps). */
    val barAlphas = listOf(1f, 0.8f, 0.6f, 0.45f, 0.35f, 0.3f)
}

/** The mockup's type scale (rem × 16 = sp). */
object Type {
    val topicTitle: TextUnit = 16.sp
    val topicDesc: TextUnit = 10.4.sp
    val subNav: TextUnit = 11.2.sp
    val chip: TextUnit = 10.4.sp
    val input: TextUnit = 12.sp
    val cardTitle: TextUnit = 12.8.sp
    val cardSubtitle: TextUnit = 10.4.sp
    val badge: TextUnit = 9.6.sp
    val island: TextUnit = 12.8.sp
    val navLabel: TextUnit = 9.6.sp
    val menuTitle: TextUnit = 20.sp
    val menuItem: TextUnit = 13.6.sp
    val label: TextUnit = 9.sp
    val small: TextUnit = 10.sp
    val tiny: TextUnit = 8.sp
    val body: TextUnit = 11.sp
    val calcTitle: TextUnit = 12.sp
    val chat: TextUnit = 15.sp
    val greeting: TextUnit = 24.sp
    val greetingSub: TextUnit = 14.sp
    val chatInput: TextUnit = 14.sp

    fun style(size: TextUnit, weight: FontWeight = FontWeight.Normal) = TextStyle(fontSize = size, fontWeight = weight)

    /** Material controls inside the app read the same small scale. */
    val material: Typography = Typography(
        bodyLarge = style(chatInput), bodyMedium = style(body), bodySmall = style(small),
        labelLarge = style(island, FontWeight.SemiBold), labelMedium = style(chip), labelSmall = style(label),
        titleMedium = style(cardTitle, FontWeight.SemiBold), titleSmall = style(calcTitle, FontWeight.Bold),
    )
}
