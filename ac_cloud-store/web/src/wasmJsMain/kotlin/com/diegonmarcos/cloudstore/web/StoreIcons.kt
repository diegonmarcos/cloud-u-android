package com.diegonmarcos.cloudstore.web

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The glyphs of the store's four sections (ui.sections[].icon in ac_cloud-store/build.json), drawn
 * as plain path data so the page needs no icon artifact. The Android shell maps the same names to
 * Material icons (Cloud, PhoneAndroid, Hub, Settings); the shapes here are the same idea, not a
 * pixel copy.
 */
internal object StoreIcons {
    private fun glyph(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(addPathNodes(path), pathFillType = PathFillType.EvenOdd, fill = SolidColor(Color.Black))
            .build()

    private val cloud = glyph(
        "cloud",
        "M19.35,10.04C18.67,6.59 15.64,4 12,4 9.11,4 6.6,5.64 5.35,8.04 2.34,8.36 0,10.91 0,14c0,3.31 2.69,6 6,6h13c2.76,0 5,-2.24 5,-5 0,-2.64 -2.05,-4.78 -4.65,-4.96z",
    )
    private val phone = glyph(
        "phone",
        "M7,2h10a2,2 0 0 1 2,2v16a2,2 0 0 1 -2,2H7a2,2 0 0 1 -2,-2V4a2,2 0 0 1 2,-2zM7,5v12h10V5z",
    )
    private val mesh = glyph(
        "mesh",
        "M15,12a3,3 0 1,1 -6,0a3,3 0 1,1 6,0zM14,4a2,2 0 1,1 -4,0a2,2 0 1,1 4,0zM8,19a2,2 0 1,1 -4,0a2,2 0 1,1 4,0z" +
            "M20,19a2,2 0 1,1 -4,0a2,2 0 1,1 4,0zM11.4,6h1.2v3h-1.2zM6.4,16.6l2.6,-2.6l0.8,0.8l-2.6,2.6z" +
            "M17.6,16.6l-0.8,0.8l-2.6,-2.6l0.8,-0.8z",
    )
    private val settings = glyph(
        "settings",
        "M19,12a7,7 0 1,1 -14,0a7,7 0 1,1 14,0zM15.5,12a3.5,3.5 0 1,0 -7,0a3.5,3.5 0 1,0 7,0z" +
            "M11,1h2v3h-2zM11,20h2v3h-2zM1,11h3v2H1zM20,11h3v2h-3z",
    )

    fun of(name: String): ImageVector = when (name) {
        "cloud" -> cloud
        "phone" -> phone
        "mesh" -> mesh
        else -> settings
    }
}
