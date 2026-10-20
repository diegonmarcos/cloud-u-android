package com.diegonmarcos.clouddialer.web

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The glyphs of the island (ui.sections[].icon in build.json), plain path data so the page needs no
 * icon artifact. The Android shell maps the same names to its own icons; these shapes are the same
 * idea, picked by keyword, not a pixel copy.
 */
internal object GenericIcons {
    private fun glyph(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(addPathNodes(path), pathFillType = PathFillType.EvenOdd, fill = SolidColor(Color.Black))
            .build()

    private val home = glyph("home", "M12,3L2,12h3v8h6v-6h2v6h6v-8h3z")
    private val gear = glyph(
        "gear",
        "M19,12a7,7 0 1,1 -14,0a7,7 0 1,1 14,0zM15.5,12a3.5,3.5 0 1,0 -7,0a3.5,3.5 0 1,0 7,0z" +
            "M11,1h2v3h-2zM11,20h2v3h-2zM1,11h3v2H1zM20,11h3v2h-3z",
    )
    private val search = glyph(
        "search",
        "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z",
    )
    private val star = glyph("star", "M12,2l3.1,6.3 6.9,1 -5,4.9 1.2,6.8L12,17.8 5.8,21l1.2,-6.8 -5,-4.9 6.9,-1z")
    private val person = glyph("person", "M12,4a4,4 0 1,1 0,8a4,4 0 1,1 0,-8zM4,20c0,-4 4,-6 8,-6s8,2 8,6z")
    private val doc = glyph("doc", "M6,2h8l6,6v14H6zM14,3.5V9h5.5z")
    private val bubble = glyph("bubble", "M4,4h16v12H8l-4,4z")
    private val clock = glyph(
        "clock",
        "M12,2a10,10 0 1,0 0,20a10,10 0 1,0 0,-20zM11,6h2v6l4,2 -1,1.7 -5,-3z",
    )
    private val tile = glyph("tile", "M5,5h14v14H5zM8,8v8h8V8z")

    fun of(name: String): ImageVector {
        val n = name.lowercase()
        return when {
            "home" in n -> home
            "config" in n || "setting" in n || "tools" in n -> gear
            "search" in n -> search
            "star" in n || "favo" in n -> star
            "person" in n || "profile" in n || "contact" in n || n.endsWith("me") || "id" == n || "ids" == n -> person
            "folder" in n || "storage" in n || "document" in n || "pages" in n || "writer" in n || "vault" in n || "vcards" in n -> doc
            "chat" in n || "sms" in n || "assistant" in n || "send" in n || "agent" in n -> bubble
            "history" in n || "recent" in n || "agenda" in n || "event" in n || "task" in n || "timeline" in n -> clock
            else -> tile
        }
    }
}
