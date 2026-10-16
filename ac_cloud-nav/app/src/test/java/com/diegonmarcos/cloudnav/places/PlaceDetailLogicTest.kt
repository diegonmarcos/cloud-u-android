package com.diegonmarcos.cloudnav.places

import com.diegonmarcos.cloudnav.PoiField
import com.diegonmarcos.cloudnav.SearchChoices
import com.diegonmarcos.cloudnav.maps.MapsProviderClient.SearchHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Places detail card's data (rows, distance, leftover tags) and the search picker's candidates. */
class PlaceDetailLogicTest {
    private val fields = listOf(
        PoiField("phone", listOf("contact:phone"), "Phone", "📞", "phone"),
        PoiField("website", listOf("contact:website"), "Website", "🌐", "url"),
        PoiField("opening_hours", emptyList(), "Hours", "🕒", "text"),
    )
    private fun hit(tags: Map<String, String>, type: String? = "fast_food") = SearchHit("Cafe Uno", "Main St", 40.0, -3.0, type, tags)

    @Test fun rowsFollowTheFieldOrderAndAltKeys() {
        val d = PlaceDetailLogic.build(hit(mapOf("contact:phone" to "+34 1", "website" to "x.es", "name" to "Cafe Uno", "cuisine" to "coffee")), Double.NaN, Double.NaN, fields)
        assertEquals(listOf("contact:phone", "website"), d.rows.map { it.key })
        assertEquals("Fast food", d.meta)
        assertEquals(mapOf("cuisine" to "coffee"), d.extra)   // name and the keys a row showed are not repeated
    }

    @Test fun distanceIsAddedWhenTheOriginIsKnown() {
        val near = PlaceDetailLogic.build(hit(emptyMap()), 40.0, -3.0 + 0.0045, fields)   // ~380 m
        assertTrue(near.meta, near.meta.startsWith("Fast food  ·  ") && near.meta.endsWith(" m away"))
        val far = PlaceDetailLogic.build(hit(emptyMap(), type = null), 41.0, -3.0, fields)  // ~111 km
        assertEquals("111.2 km away", far.meta)
    }

    @Test fun resolveAndUrl() {
        assertNull(PlaceDetailLogic.resolve(mapOf("phone" to " "), fields[0]))
        assertEquals("contact:phone" to "1", PlaceDetailLogic.resolve(mapOf("contact:phone" to "1"), fields[0]))
        assertEquals("https://x.es", PlaceDetailLogic.urlOf("x.es")); assertEquals("http://x.es", PlaceDetailLogic.urlOf("http://x.es"))
        assertEquals(0.0, PlaceDetailLogic.haversineKm(1.0, 2.0, 1.0, 2.0), 1e-9)
    }

    @Test fun pickerOffersAtMostNAndAtLeastOne() {
        val hits = (1..6).map { SearchHit("p$it", null, 0.0, 0.0, null) }
        assertEquals(3, SearchChoices.top(hits, 3).size)
        assertEquals(1, SearchChoices.top(hits, 0).size)
        assertEquals("p1", SearchChoices.label(hits[0]))
        assertEquals("Cafe Uno\nMain St", SearchChoices.label(hit(emptyMap())))
    }
}
