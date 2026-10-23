package com.diegonmarcos.cloudnav.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1145: full addresses and POI name + category from the open geocoders (Nominatim, Photon, Overpass), parsed and merged. */
class AddressTest {
    private val nominatim = """{"place_id":1,"class":"amenity","type":"cafe","name":"Cafe Central","display_name":"Cafe Central, Herrengasse 14, Wien, Austria",
        "address":{"amenity":"Cafe Central","house_number":"14","road":"Herrengasse","suburb":"Innere Stadt","city":"Wien","state":"Wien","postcode":"1010","country":"Austria"}}"""
    private val photon = """{"features":[{"properties":{"name":"Bakery Z","street":"Calle Mayor","housenumber":"3","postcode":"28013","city":"Madrid","state":"Madrid","country":"Spain","district":"Centro","osm_key":"shop","osm_value":"bakery"}}]}"""
    private val overpass = """{"elements":[{"type":"node","tags":{"highway":"bus_stop"}},{"type":"node","tags":{"name":"Gym One","leisure":"fitness_centre","addr:street":"Main St","addr:housenumber":"9"}}]}"""

    @Test fun `nominatim gives street, postcode, city, state, country and the POI tag`() {
        val g = AddressParser.parse("nominatim_public", nominatim)!!
        assertEquals("Herrengasse 14", g.street); assertEquals("1010", g.postcode); assertEquals("Wien", g.city)
        assertEquals("Wien", g.state); assertEquals("Austria", g.country); assertEquals("Innere Stadt", g.neighborhood)
        assertEquals("Cafe Central", g.name); assertEquals(PoiCategory.FOOD, g.category); assertTrue(g.isPoi)
    }

    @Test fun `photon gives the same fields`() {
        val g = AddressParser.parse("photon_public", photon)!!
        assertEquals("Calle Mayor 3", g.street); assertEquals("28013", g.postcode); assertEquals("Spain", g.country)
        assertEquals("Centro", g.neighborhood); assertEquals(PoiCategory.SHOP, g.category)
    }

    @Test fun `overpass picks the first named POI and ignores unnamed objects`() {
        val g = AddressParser.overpass(overpass)!!
        assertEquals("Gym One", g.name); assertEquals(PoiCategory.SPORT, g.category); assertEquals("Main St 9", g.street)
        assertNull(AddressParser.overpass("""{"elements":[]}"""))
    }

    @Test fun `garbage and unknown providers parse to null instead of throwing`() {
        assertNull(AddressParser.parse("nominatim_public", "not json"))
        assertNull(AddressParser.parse("radar", nominatim))
        assertNull(AddressParser.parse("nominatim_public", """{"error":"Unable to geocode"}"""))
    }

    @Test fun `the overpass query is bounded around the point and asks for named POI keys`() {
        val q = AddressParser.overpassQuery(48.2082, 16.3738)
        assertTrue(q.contains("around:40,48.208200,16.373800")); assertTrue(q.contains("[name]")); assertTrue(q.contains("amenity|shop|tourism"))
    }

    @Test fun `a plain address plus a POI merges to the POI name over the address`() {
        val road = Geocoded(name = "Main St", street = "Main St 9", city = "X", country = "Y", osmClass = "highway", osmType = "residential")
        assertFalse(road.isPoi)
        val m = AddressParser.merge(road, AddressParser.overpass(overpass))!!
        assertEquals("Gym One", m.name); assertEquals(PoiCategory.SPORT, m.category); assertEquals("X", m.city)
    }

    @Test fun `filling a place keeps the owner's name and existing fields, fills only the gaps`() {
        val g = AddressParser.parse("nominatim_public", nominatim)!!
        val p = Place(1.0, 2.0, name = "My office", city = "Vienna").withGeocoded(g)
        assertEquals("My office", p.name); assertEquals("Vienna", p.city)
        assertEquals("Herrengasse 14", p.street); assertEquals("1010", p.postcode); assertEquals("Wien", p.state); assertEquals("Austria", p.country)
        assertEquals(PoiCategory.FOOD, p.poi)
        assertFalse(p.needsAddress)
        assertEquals("Herrengasse 14, 1010, Innere Stadt, Vienna, Wien, Austria", p.address)
    }

    @Test fun `a POI-less place gets the address, its label falls back to the street`() {
        val p = Place(1.0, 2.0).withGeocoded(Geocoded(street = "Elm Rd 1", city = "Town", postcode = "9", state = "S", country = "C", osmClass = "building", osmType = "house"))
        assertEquals("Elm Rd 1, Town", p.label); assertEquals(PoiCategory.HOME, p.poi)
    }

    @Test fun `only located places with missing address parts are looked up`() {
        assertFalse(Place(null, null).needsAddress)
        assertTrue(Place(1.0, 2.0, street = "a", postcode = "1", state = "s").needsAddress)
        assertFalse(Place(1.0, 2.0, street = "a", postcode = "1", state = "s", country = "c").needsAddress)
    }

    @Test fun `the cache json round-trips and an empty answer is still a cache entry`() {
        val g = AddressParser.parse("nominatim_public", nominatim)!!
        assertEquals(g, Geocoded.fromJson(g.toJson()))
        assertNotNull(Geocoded.fromJson(Geocoded().toJson()))
        assertEquals(PlaceNamer.key(48.20821, 16.37381), PlaceNamer.key(48.20824, 16.37384))
    }

    @Test fun `network calls are spaced at least the fair-use gap`() {
        assertEquals(PlaceNamer.MIN_GAP_MS, PlaceNamer.waitMs(1_000, 1_000))
        assertEquals(100L, PlaceNamer.waitMs(1_000, 1_000 + PlaceNamer.MIN_GAP_MS - 100))
        assertEquals(0L, PlaceNamer.waitMs(1_000, 1_000 + PlaceNamer.MIN_GAP_MS))
    }

    @Test fun `category icons cover the common OSM tags`() {
        val cases = mapOf(("amenity" to "restaurant") to PoiCategory.FOOD, ("shop" to "supermarket") to PoiCategory.SHOP,
            ("tourism" to "hotel") to PoiCategory.LODGING, ("railway" to "station") to PoiCategory.TRANSIT, ("amenity" to "pharmacy") to PoiCategory.HEALTH,
            ("amenity" to "school") to PoiCategory.EDUCATION, ("office" to "company") to PoiCategory.WORK, ("building" to "apartments") to PoiCategory.HOME,
            ("leisure" to "park") to PoiCategory.PARK, ("tourism" to "museum") to PoiCategory.CULTURE, ("amenity" to "fuel") to PoiCategory.FUEL,
            ("amenity" to "parking") to PoiCategory.PARKING, ("amenity" to "place_of_worship") to PoiCategory.WORSHIP, ("amenity" to "bank") to PoiCategory.MONEY,
            ("highway" to "residential") to PoiCategory.OTHER)
        for ((tag, want) in cases) assertEquals("$tag", want, PoiCategory.of(tag.first, tag.second))
        assertEquals(PoiCategory.OTHER, PoiCategory.of(null, null)); assertEquals(PoiCategory.OTHER, PoiCategory.parse("nope"))
    }
}
