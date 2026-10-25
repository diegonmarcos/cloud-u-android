package com.diegonmarcos.cloudcalc.web

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The glyphs of the calculator's sections (ui.sections[].icon in ac_cloud-calc/build.json), plain
 * path data so the page needs no icon artifact. The Android shell maps the same names to Material
 * icons; the shapes here are the same idea, not a pixel copy.
 */
internal object CalcIcons {
    private fun glyph(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(addPathNodes(path), pathFillType = PathFillType.EvenOdd, fill = SolidColor(Color.Black))
            .build()

    private val calculate = glyph(
        "calculate",
        "M19,3H5C3.9,3 3,3.9 3,5v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V5C21,3.9 20.1,3 19,3zM6,6h5v2H6zM13,16h5v2h-5zM13,12h5v2h-5zM7,12h2v-1.5h1.5v-1.5H9V7.5H7V9H5.5v1.5H7z",
    )
    private val sheet = glyph(
        "sheet",
        "M3,3h18v18H3zM5,5v4h6V5zM13,5v4h6V5zM5,11v3h6v-3zM13,11v3h6v-3zM5,16v3h6v-3zM13,16v3h6v-3z",
    )
    private val ai = glyph(
        "ai",
        "M12,2l2.4,6.6L21,11l-6.6,2.4L12,20l-2.4,-6.6L3,11l6.6,-2.4z",
    )
    private val measure = glyph(
        "measure",
        "M3,17h18v4H3zM5,17v-3h2v3zM9,17v-2h2v2zM13,17v-3h2v3zM17,17v-2h2v2z",
    )
    private val toolkit = glyph(
        "toolkit",
        "M22.7,19l-9.1,-9.1c0.9,-2.3 0.4,-5 -1.5,-6.9 -2,-2 -5,-2.4 -7.4,-1.3L9,6 6,9 1.6,4.7C0.4,7.1 0.9,10.1 2.9,12.1c1.9,1.9 4.6,2.4 6.9,1.5l9.1,9.1c0.4,0.4 1,0.4 1.4,0l2.3,-2.3c0.5,-0.4 0.5,-1.1 0.1,-1.4z",
    )
    private val settings = glyph(
        "settings",
        "M19,12a7,7 0 1,1 -14,0a7,7 0 1,1 14,0zM15.5,12a3.5,3.5 0 1,0 -7,0a3.5,3.5 0 1,0 7,0z" +
            "M11,1h2v3h-2zM11,20h2v3h-2zM1,11h3v2H1zM20,11h3v2h-3z",
    )

    /** The backspace key: a vector, because no bundled font carries U+232B (it drew a missing-glyph box). */
    val backspace = glyph(
        "backspace",
        "M22,3H7c-0.69,0 -1.23,0.35 -1.59,0.88L0,12l5.41,8.11c0.36,0.53 0.9,0.89 1.59,0.89h15c1.1,0 2,-0.9 2,-2V5C24,3.9 23.1,3 22,3zM19,15.59L17.59,17L14,13.41L10.41,17L9,15.59L12.59,12L9,8.41L10.41,7L14,10.59L17.59,7L19,8.41L15.41,12L19,15.59z",
    )

    fun of(name: String): ImageVector = when (name) {
        "calculate" -> calculate
        "table_chart" -> sheet
        "auto_awesome" -> ai
        "speed" -> measure
        "build" -> toolkit
        else -> settings
    }
}
