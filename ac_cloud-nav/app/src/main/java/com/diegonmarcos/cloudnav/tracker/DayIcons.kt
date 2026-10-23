package com.diegonmarcos.cloudnav.tracker

import androidx.annotation.DrawableRes
import com.diegonmarcos.cloudnav.R

/** Vector icons for the Day page and its map: one per transport mode, per stay kind and per POI category. Tint at the call site. */
object DayIcons {
    @DrawableRes fun mode(m: Mode): Int = when (m) {
        Mode.WALK -> R.drawable.ic_mode_walk; Mode.RUN -> R.drawable.ic_mode_run; Mode.BIKE -> R.drawable.ic_mode_bike
        Mode.CAR -> R.drawable.ic_mode_car; Mode.TRAIN -> R.drawable.ic_mode_train; Mode.PLANE -> R.drawable.ic_mode_plane
        Mode.UNKNOWN -> R.drawable.ic_mode_unknown
    }

    @DrawableRes fun poi(c: PoiCategory): Int = when (c) {
        PoiCategory.FOOD -> R.drawable.ic_poi_food; PoiCategory.SHOP -> R.drawable.ic_poi_shop; PoiCategory.LODGING -> R.drawable.ic_poi_lodging
        PoiCategory.TRANSIT -> R.drawable.ic_poi_transit; PoiCategory.HEALTH -> R.drawable.ic_poi_health; PoiCategory.EDUCATION -> R.drawable.ic_poi_education
        PoiCategory.WORK -> R.drawable.ic_poi_work; PoiCategory.HOME -> R.drawable.ic_poi_home; PoiCategory.PARK -> R.drawable.ic_poi_park
        PoiCategory.CULTURE -> R.drawable.ic_poi_culture; PoiCategory.FUEL -> R.drawable.ic_poi_fuel; PoiCategory.PARKING -> R.drawable.ic_poi_parking
        PoiCategory.SPORT -> R.drawable.ic_poi_sport; PoiCategory.WORSHIP -> R.drawable.ic_poi_worship; PoiCategory.MONEY -> R.drawable.ic_poi_money
        PoiCategory.OTHER -> R.drawable.ic_stay_place
    }

    /** The icon of a stay: a sleep stay shows the moon, a visit its POI category (the pin when none is known). */
    @DrawableRes fun stay(s: DaySeg.Stay): Int = if (s.kind == StayKind.SLEEP) R.drawable.ic_stay_sleep else poi(s.place?.poi ?: PoiCategory.OTHER)
}
