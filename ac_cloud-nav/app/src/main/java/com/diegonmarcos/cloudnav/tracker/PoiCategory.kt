package com.diegonmarcos.cloudnav.tracker

/** The category icon set for visits. Maps an OSM tag (Nominatim class/type, Photon osm_key/osm_value, Overpass key) to one icon. */
enum class PoiCategory(val label: String) {
    FOOD("Food and drink"), SHOP("Shopping"), LODGING("Lodging"), TRANSIT("Transit"), HEALTH("Health"), EDUCATION("Education"),
    WORK("Work"), HOME("Home"), PARK("Park and nature"), CULTURE("Culture"), FUEL("Fuel and charging"), PARKING("Parking"),
    SPORT("Sport"), WORSHIP("Worship"), MONEY("Bank"), OTHER("Place");

    companion object {
        private val FOOD_T = setOf("restaurant", "cafe", "fast_food", "bar", "pub", "food_court", "biergarten", "ice_cream")
        private val HEALTH_T = setOf("hospital", "clinic", "doctors", "dentist", "pharmacy", "veterinary", "chemist")
        private val EDU_T = setOf("school", "university", "college", "kindergarten", "library")
        private val HOME_T = setOf("residential", "house", "apartments", "detached", "terrace", "dormitory")
        private val CULTURE_T = setOf("museum", "theatre", "cinema", "arts_centre", "gallery", "attraction", "artwork", "monument", "castle")
        private val PARK_T = setOf("park", "garden", "nature_reserve", "playground", "beach", "forest", "wood", "water")
        private val FUEL_T = setOf("fuel", "charging_station")
        private val LODGING_T = setOf("hotel", "hostel", "motel", "guest_house", "apartment", "camp_site", "chalet")
        private val TRANSIT_T = setOf("station", "bus_station", "bus_stop", "stop_position", "platform", "halt", "tram_stop", "subway_entrance", "aerodrome", "terminal", "ferry_terminal", "taxi")
        private val SPORT_T = setOf("fitness_centre", "sports_centre", "stadium", "swimming_pool", "pitch", "golf_course", "gym")

        fun of(osmClass: String?, osmType: String?): PoiCategory {
            val c = osmClass?.lowercase(); val t = osmType?.lowercase()
            return when {
                t in FOOD_T -> FOOD
                t in HEALTH_T || c == "healthcare" -> HEALTH
                t in EDU_T -> EDUCATION
                t in FUEL_T -> FUEL
                t in LODGING_T || c == "lodging" -> LODGING
                t in TRANSIT_T || c == "railway" || c == "aeroway" || c == "public_transport" -> TRANSIT
                t == "parking" || t == "parking_space" || t == "bicycle_parking" -> PARKING
                t == "place_of_worship" -> WORSHIP
                t == "bank" || t == "atm" -> MONEY
                t in SPORT_T || c == "sport" -> SPORT
                t in CULTURE_T || c == "historic" -> CULTURE
                t in PARK_T || c == "natural" -> PARK
                (c == "building" || c == "landuse" || c == "place") && t in HOME_T -> HOME
                c == "office" || t == "office" || t == "coworking_space" -> WORK
                c == "shop" || t in setOf("supermarket", "mall", "marketplace", "convenience") -> SHOP
                c == "tourism" -> CULTURE
                c == "leisure" -> PARK
                else -> OTHER
            }
        }

        fun parse(s: String?): PoiCategory = values().firstOrNull { it.name.equals(s, true) } ?: OTHER
    }
}
