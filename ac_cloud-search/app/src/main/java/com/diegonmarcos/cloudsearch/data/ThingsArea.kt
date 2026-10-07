package com.diegonmarcos.cloudsearch.data

import android.content.Context
import com.diegonmarcos.cloudsearch.core.SearchEngine
import com.diegonmarcos.cloudsearch.core.Things

/**
 * #903 Where the Things search is centred, in the order the owner asked for: the coarse location fix
 * (when "use my location" is on and the permission is held), else the city typed in settings
 * (geocoded, cached), else the app's selected city. The radius is the setting, default 20 km.
 * [why] says why a better choice was not used, so the page never silently searches elsewhere.
 */
object ThingsArea {
    enum class How { LOCATION, TYPED, CITY }
    enum class Why { NONE, PERMISSION, NO_FIX, UNKNOWN_CITY }

    data class Resolved(val area: Things.Area, val how: How, val why: Why, val typed: String, val status: SearchEngine.SourceStatus?)

    fun resolve(s: Services, ctx: Context): Resolved {
        val t = s.cfg.things ?: throw IllegalStateException("build.json::search.things is not declared")
        val radius = t.clampRadius(s.prefs.radiusKm)
        var why = Why.NONE
        if (s.prefs.useLocation) {
            val fix = Locator.fix(ctx)
            if (fix != null) return Resolved(Things.Area(fix.lat, fix.lon, radius, ""), How.LOCATION, Why.NONE, "", null)
            why = if (Locator.granted(ctx)) Why.NO_FIX else Why.PERMISSION
        }
        val typed = s.prefs.thingsCity
        if (typed.isNotBlank()) {
            val (area, status) = s.things.geocode(typed, radius)
            if (area != null) return Resolved(area, How.TYPED, why, typed, status)
            why = Why.UNKNOWN_CITY
        }
        val c = s.cfg.city(s.prefs.city)
        return Resolved(Things.Area(c.lat, c.lon, radius, c.label), How.CITY, why, typed, null)
    }
}
