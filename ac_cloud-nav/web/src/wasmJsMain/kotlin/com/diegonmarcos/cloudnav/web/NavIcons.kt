package com.diegonmarcos.cloudnav.web

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The glyphs of the navigator's sections (ui.sections[].icon in ac_cloud-nav/build.json), plain
 * path data so the page needs no icon artifact. The Android shell maps the same names to Material
 * icons; the shapes here are the same idea, not a pixel copy.
 */
internal object NavIcons {
    private fun glyph(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(addPathNodes(path), pathFillType = PathFillType.EvenOdd, fill = SolidColor(Color.Black))
            .build()

    private val routes = glyph(
        "routes",
        "M6,3a3,3 0 1,1 0,6a3,3 0 1,1 0,-6zM18,15a3,3 0 1,1 0,6a3,3 0 1,1 0,-6zM8,6h6a4,4 0 0 1 0,8h-4a2,2 0 0 0 0,4h5v2h-5a4,4 0 0 1 0,-8h4a2,2 0 0 0 0,-4H8z",
    )
    private val navigation = glyph(
        "navigation",
        "M12,2L4.5,20.3l0.7,0.7L12,18l6.8,3 0.7,-0.7z",
    )
    private val places = glyph(
        "places",
        "M12,2C8.1,2 5,5.1 5,9c0,5.2 7,13 7,13s7,-7.8 7,-13c0,-3.9 -3.1,-7 -7,-7zM12,11.5a2.5,2.5 0 1,1 0,-5a2.5,2.5 0 1,1 0,5z",
    )
    private val timeline = glyph(
        "timeline",
        "M12,2a10,10 0 1,0 0,20a10,10 0 1,0 0,-20zM12,4a8,8 0 1,1 0,16a8,8 0 1,1 0,-16zM11,7h2v5.2l3.6,2.1l-1,1.7L11,13z",
    )
    private val explored = glyph(
        "explored",
        "M12,10.9a1.1,1.1 0 1,0 0,2.2a1.1,1.1 0 1,0 0,-2.2zM12,2a10,10 0 1,0 0,20a10,10 0 1,0 0,-20zM14.19,14.19L6,18l3.81,-8.19L18,6z",
    )
    private val settings = glyph(
        "settings",
        "M19,12a7,7 0 1,1 -14,0a7,7 0 1,1 14,0zM15.5,12a3.5,3.5 0 1,0 -7,0a3.5,3.5 0 1,0 7,0z" +
            "M11,1h2v3h-2zM11,20h2v3h-2zM1,11h3v2H1zM20,11h3v2h-3z",
    )

    fun of(name: String): ImageVector = when (name) {
        "routes" -> routes
        "navigation" -> navigation
        "places" -> places
        "timeline" -> timeline
        "explored" -> explored
        else -> settings
    }
}
