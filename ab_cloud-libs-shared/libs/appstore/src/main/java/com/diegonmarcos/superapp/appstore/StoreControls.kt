package com.diegonmarcos.superapp.appstore

import android.content.Context
import android.graphics.drawable.GradientDrawable
import org.json.JSONObject

/**
 * #732 — how the Store's three kinds of control LOOK, read from
 * assets/appstore-controls.json. `tab` selects a subset, `page` opens a page,
 * `action` acts on one app. This object maps a declared style onto a view; it
 * names no style token, colour, icon or caption — those are the asset.
 */
object StoreControls {

    const val ASSET = "appstore-controls.json"

    /** One declared style. Colours are ARGB; null means "none". */
    class Style(
        val fill: Int?, val fillActive: Int?,
        val stroke: Int?, val strokeActive: Int?,
        val text: Int, val textActive: Int,
        val radiusDp: Int, val stretch: Boolean, val bold: Boolean, val chevron: String,
    )

    /** One drawn tab or page entry: caption, icon ('' = none) and the style it wears. */
    class Control(val label: String, val icon: String, val style: Style)

    class Decl(private val styles: Map<String, Style>, private val pages: JSONObject,
               groupTabStyle: String, actionStyle: String) {
        val groupTab: Style = style(groupTabStyle)
        val action: Style = style(actionStyle)

        fun style(token: String): Style = styles[token] ?: PLAIN

        /** A line-2 entry by id. [caption] is the feed's own label when the
         *  entry is a feed; the page's own entries (mesh, perms) declare theirs
         *  here. An undeclared id still draws, as plain text with no icon. */
        fun page(id: String, caption: String? = null): Control {
            val p = pages.optJSONObject(id)
            return Control(caption ?: p?.optString("label")?.takeIf { it.isNotEmpty() } ?: id,
                p?.optString("icon").orEmpty(), style(p?.optString("style").orEmpty()))
        }
    }

    /** What a style token nothing declares draws as: a caption, no chrome. */
    private val PLAIN = Style(null, null, null, null, 0x99FFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0, false, false, "")

    fun load(ctx: Context): Decl = runCatching {
        parse(JSONObject(ctx.assets.open(ASSET).use { it.readBytes().decodeToString() }))
    }.getOrElse { parse(JSONObject()) }

    fun parse(decl: JSONObject): Decl {
        val s = decl.optJSONObject("styles") ?: JSONObject()
        val styles = s.keys().asSequence().filterNot { it.startsWith("_") }.associateWith { k ->
            val o = s.getJSONObject(k)
            Style(argb(o, "fill"), argb(o, "fill_active"), argb(o, "stroke"), argb(o, "stroke_active"),
                argb(o, "text") ?: PLAIN.text, argb(o, "text_active") ?: argb(o, "text") ?: PLAIN.textActive,
                o.optInt("radius_dp"), o.optBoolean("stretch"), o.optBoolean("bold"), o.optString("chevron"))
        }
        return Decl(styles, decl.optJSONObject("pages") ?: JSONObject(),
            decl.optString("group_tab_style"), decl.optString("action_style"))
    }

    private fun argb(o: JSONObject, key: String): Int? =
        o.optString(key).takeIf { it.startsWith("0x") }?.removePrefix("0x")?.toLongOrNull(16)?.toInt()

    /** The one background builder for all three styles. [fillOverride] is an
     *  action's own verb colour, which replaces the style's fill. */
    fun background(ctx: Context, style: Style, active: Boolean, fillOverride: Int? = null) = GradientDrawable().apply {
        val d = ctx.resources.displayMetrics.density
        cornerRadius = style.radiusDp * d
        setColor(fillOverride ?: (if (active) style.fillActive else style.fill) ?: 0)
        val stroke = if (active) style.strokeActive ?: style.stroke else style.stroke
        if (stroke != null) setStroke(d.toInt().coerceAtLeast(1), stroke)
    }
}
