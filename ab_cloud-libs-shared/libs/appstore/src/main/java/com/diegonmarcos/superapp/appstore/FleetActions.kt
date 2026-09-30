package com.diegonmarcos.superapp.appstore

import android.content.Context
import org.json.JSONObject

/**
 * Store ▸ Cloud: which buttons a fleet row and its Details sheet draw, in
 * which order, under which caption — read from [ASSET], never written in the
 * fragment. The code only maps an [Action.id] to what it does, and an id it
 * has no handler for is drawn as a button that says so rather than dropped.
 */
object FleetActions {

    const val ASSET = "appstore-fleet-actions.json"

    class Action(val id: String, val label: String, val color: Int, val detail: String)

    fun row(ctx: Context): List<Action> = read(ctx, "row")
    fun details(ctx: Context): List<Action> = read(ctx, "details")

    /** The declared caption for [id], wherever it is drawn — so a dialog that
     *  offers "App settings" names it exactly as the sheet does. */
    fun label(ctx: Context, id: String): String =
        (row(ctx) + details(ctx)).firstOrNull { it.id == id }?.label ?: id

    private fun read(ctx: Context, key: String): List<Action> {
        val doc = ctx.assets.open(ASSET).use { JSONObject(it.readBytes().decodeToString()) }
        val arr = doc.getJSONArray(key)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Action(o.getString("id"), o.getString("label"),
                o.optString("color", "0xFF2A2A33").removePrefix("0x").toLong(16).toInt(),
                o.optString("detail"))
        }
    }
}
