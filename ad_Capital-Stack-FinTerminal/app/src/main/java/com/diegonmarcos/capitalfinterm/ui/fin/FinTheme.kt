package com.diegonmarcos.capitalfinterm.ui.fin

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.capitalfinterm.FinFormat
import com.diegonmarcos.capitalfinterm.R

/**
 * The FinTerm terminal palette (UI spec 1.1), read from res/values/colors.xml (the one place it is spelled). Direction colours
 * are swapped to blue/amber when the user turns on "Colour-blind safe" (Config, preference `fin_cvd`).
 */
@Immutable
data class FinPalette(
    val canvas: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val hairline: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    val up: Color,
    val down: Color,
    val accent: Color,
    val amber: Color,
    val flashUp: Color,
    val flashDown: Color,
    val skeleton: Color,
    val skeletonHi: Color,
) {
    val flat: Color get() = text2
    val impLow: Color get() = text3
    val impMed: Color get() = amber
    val impHigh: Color get() = down

    fun dir(d: FinFormat.Dir): Color = when (d) { FinFormat.Dir.UP -> up; FinFormat.Dir.DOWN -> down; FinFormat.Dir.FLAT -> flat }
    fun dirOf(s: String?): Color = if (s == null || FinLogicUi.missing(s)) text3 else dir(FinFormat.dirOf(s))
    fun importance(n: Int): Color = when (n) { 3 -> impHigh; 2 -> impMed; else -> impLow }
}

val LocalFinPalette = staticCompositionLocalOf<FinPalette> { error("FinTheme not set") }
val LocalReduceMotion = staticCompositionLocalOf { false }

/** Bundled fonts (res/font): Roboto for words, Noto Sans Mono with tabular lining figures for every number that can change. */
object FinFonts {
    val sans = FontFamily(Font(R.font.roboto_regular, FontWeight.Normal), Font(R.font.roboto_medium, FontWeight.Medium), Font(R.font.roboto_medium, FontWeight.SemiBold))
    val mono = FontFamily(Font(R.font.noto_sans_mono_regular, FontWeight.Normal), Font(R.font.noto_sans_mono_medium, FontWeight.Medium), Font(R.font.noto_sans_mono_medium, FontWeight.SemiBold))
}

/** The type scale of UI spec 1.2 (size / line height on the 4dp grid). */
object FinType {
    private const val NUM = "tnum, lnum"
    val display = TextStyle(fontFamily = FinFonts.sans, fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = NUM)
    val kpi = TextStyle(fontFamily = FinFonts.sans, fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = NUM)
    val title = TextStyle(fontFamily = FinFonts.sans, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontFamily = FinFonts.sans, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal)
    val label = TextStyle(fontFamily = FinFonts.sans, fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp)
    val num = TextStyle(fontFamily = FinFonts.mono, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal, fontFeatureSettings = NUM)
    val numStrong = num.copy(fontWeight = FontWeight.SemiBold)
    val numSmall = TextStyle(fontFamily = FinFonts.mono, fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal, fontFeatureSettings = NUM)
    val caption = TextStyle(fontFamily = FinFonts.sans, fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal)
    val symbol = TextStyle(fontFamily = FinFonts.sans, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp)
}

/** The 4dp density grid of UI spec 1.3. */
object FinDim {
    val screenPad = 12.dp
    val cardPad = 12.dp
    val gap = 8.dp
    val rowCompact = 40.dp
    val rowTall = 56.dp
    val rowCalendar = 44.dp
    val rowCalendarValues = 48.dp
    val kpi = 72.dp
    val chartLine = 200.dp
    val chartCandle = 240.dp
    val tape = 28.dp
    val title = 32.dp
    val footer = 24.dp
    val note = 20.dp
    val keyRow = 48.dp
    val tableHeader = 32.dp
    val tableRow = 36.dp
    val dayHeader = 28.dp
    val cardRadius = 12.dp
    val tileRadius = 8.dp
    val chipRadius = 8.dp
    val chip = 32.dp
    val symbolCol = 88.dp
    val numCol = 64.dp
    val lastCol = 72.dp
    /** Island height + its bottom margin + 8dp (Classic 64 + 12 + 8): the island never covers a row in either nav mode. */
    val bottomInset = 84.dp
    /** Width from which the app draws two panes (WindowWidthSizeClass Medium). */
    val twoPane = 600.dp
}

/** The user's colour-blind choice, observable so the palette swaps at once. */
object FinUi {
    var cvd by androidx.compose.runtime.mutableStateOf(false)
        private set
    private var loaded = false
    fun load(ctx: Context) { if (!loaded) { cvd = FinPrefs.cvd(ctx); loaded = true } }
    fun setCvd(ctx: Context, on: Boolean) { cvd = on; FinPrefs.setCvd(ctx, on) }
}

/**
 * The UI's device-only choices (colour-blind palette, chart range per panel, recent searches), kept in the app's one store
 * (EncryptedSecretBox, `_fin_*` slots: no source id starts with "_"), never in plain preferences (rule S4).
 */
object FinPrefs {
    const val CVD = "_fin_cvd"
    private fun box(ctx: Context) = com.diegonmarcos.capitalfinterm.ui.EncryptedSecretBox(ctx)
    fun cvd(ctx: Context): Boolean = box(ctx).get(CVD) == "1"
    fun setCvd(ctx: Context, on: Boolean) { box(ctx).put(CVD, if (on) "1" else "0") }
    fun range(ctx: Context, panel: String, fallback: String): String = box(ctx).get("_fin_range:$panel") ?: fallback
    fun setRange(ctx: Context, panel: String, r: String) { box(ctx).put("_fin_range:$panel", r) }
    fun recent(ctx: Context): List<String> = box(ctx).get("_fin_recent").orEmpty().split('|').filter { it.isNotBlank() }
    fun addRecent(ctx: Context, s: String) {
        val next = (listOf(s) + recent(ctx).filter { it != s }).take(8)
        box(ctx).put("_fin_recent", next.joinToString("|"))
    }
    fun clearRecent(ctx: Context) { box(ctx).remove("_fin_recent") }
}

@Composable
fun finPalette(cvd: Boolean): FinPalette {
    val base = FinPalette(
        canvas = colorResource(R.color.app_background),
        surface = colorResource(R.color.app_surface),
        surfaceRaised = colorResource(R.color.app_surface_raised),
        hairline = colorResource(R.color.app_outline),
        text = colorResource(R.color.app_text),
        text2 = colorResource(R.color.app_text_secondary),
        text3 = colorResource(R.color.fin_text3),
        up = colorResource(R.color.fin_up),
        down = colorResource(R.color.fin_down),
        accent = colorResource(R.color.app_accent),
        amber = colorResource(R.color.fin_amber),
        flashUp = colorResource(R.color.fin_flash_up),
        flashDown = colorResource(R.color.fin_flash_down),
        skeleton = colorResource(R.color.fin_skeleton),
        skeletonHi = colorResource(R.color.fin_skeleton_hi),
    )
    return if (cvd) base.copy(up = colorResource(R.color.fin_cvd_up), down = colorResource(R.color.fin_cvd_down)) else base
}

@Composable
fun FinTheme(cvd: Boolean, reduceMotion: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalFinPalette provides finPalette(cvd), LocalReduceMotion provides reduceMotion, content = content)
}

/** UI-side helpers shared by the components (the pure rules live in [com.diegonmarcos.capitalfinterm.FinLogic]). */
object FinLogicUi {
    fun missing(s: String?): Boolean = com.diegonmarcos.capitalfinterm.FinLogic.missing(s)
}
