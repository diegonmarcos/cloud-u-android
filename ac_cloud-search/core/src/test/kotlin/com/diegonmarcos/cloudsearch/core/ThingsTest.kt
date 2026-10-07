package com.diegonmarcos.cloudsearch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #903 Things: the pure half — item to shop type, the Overpass query and answer, the shelf and price-service answers, the table. */
class ThingsTest {
    private val t: Things.Config get() = Fixtures.cfg.things!!
    private val berlin = Things.Area(52.52, 13.405, 20, "Berlin")
    private val now = Parsers.isoDay("2026-10-02")!!

    private fun store(osm: String, name: String, brand: String? = name, km: Double = 1.0, site: String? = null) =
        Things.Store(osm, name, brand, "electronics", 52.5, 13.4, site, null, km)

    // ── declaration ──────────────────────────────────────────────────────────────────────────────
    @Test fun theShippedDeclarationIsConsistentAndTwentyKmByDefault() {
        assertTrue(Things.problems(t).isEmpty())
        assertEquals(20, t.defaultRadiusKm)
        assertEquals(listOf(5, 10, 20, 50), t.radiusStepsKm)
        assertEquals(1, t.clampRadius(0)); assertEquals(100, t.clampRadius(500)); assertEquals(20, t.clampRadius(20))
        assertNotNull(t.category("electronics")); assertNull(t.category("nope"))
        assertEquals("general", t.fallback.id)
    }

    private fun problemsAfter(edit: (org.json.JSONObject) -> Unit): List<String> {
        val j = Fixtures.searchJson().getJSONObject("things")
        edit(j)
        return Things.problems(Things.config(j))
    }

    @Test fun anInconsistentDeclarationIsNamed() {
        assertTrue(problemsAfter { it.put("min_radius_km", 30) }.any { "radius" in it })
        assertTrue(problemsAfter { it.put("default_radius_km", 500) }.any { "radius" in it })
        assertTrue(problemsAfter { it.put("min_radius_km", 0) }.any { "radius" in it })
        assertTrue(problemsAfter { it.getJSONArray("radius_steps_km").put(999) }.any { "radius_steps_km" in it })
        assertTrue(problemsAfter { it.put("categories", org.json.JSONArray()) }.any { "no category" in it })
        assertTrue(problemsAfter { it.getJSONArray("categories").put(it.getJSONArray("categories").getJSONObject(0)) }.any { "duplicate" in it })
        assertTrue(problemsAfter { it.getJSONArray("categories").getJSONObject(0).put("shops", org.json.JSONArray()) }.any { "no shop type" in it })
        assertTrue(problemsAfter { it.getJSONObject("fallback").put("shops", org.json.JSONArray()) }.any { "no shop type" in it })
        assertTrue(problemsAfter { it.getJSONArray("categories").getJSONObject(0).put("words", org.json.JSONArray()) }.any { "no word" in it })
        assertTrue(problemsAfter { it.put("geocoder_url", "http://x/{q}") }.any { "geocoder_url must be https" in it })
        assertTrue(problemsAfter { it.getJSONObject("shelf_prices").put("url", "http://x") }.any { "shelf url must be https" in it })
        assertTrue(problemsAfter { it.getJSONObject("price_service").put("url", "http://x/{q}") }.any { "price_service url must be https" in it })
        assertTrue(problemsAfter { it.put("overpass_urls", org.json.JSONArray()) }.any { "no overpass_urls" in it })
        assertTrue(problemsAfter { it.put("overpass_urls", org.json.JSONArray(listOf("http://o"))) }.any { "http://o must be https" in it })
        assertTrue(problemsAfter { it.put("geocoder_url", "https://x/") }.any { "no {q}" in it })
        assertTrue(problemsAfter { it.getJSONObject("price_service").put("url", "https://x/") }.any { "price_service url has no {q}" in it })
        assertTrue(problemsAfter { it.put("max_stores", 0) }.any { "inconsistent" in it })
        assertTrue(problemsAfter { it.put("max_rows", 0) }.any { "inconsistent" in it })
        assertTrue(problemsAfter { it.put("overpass_limit", 1) }.any { "inconsistent" in it })
    }

    @Test fun aThingsPageNeedsTheDeclaration() {
        val j = Fixtures.searchJson()
        j.remove("things")
        assertTrue(runCatching { SearchConfig.parse(j) }.exceptionOrNull()?.message.orEmpty().contains("things is not declared"))
    }

    // ── the item ─────────────────────────────────────────────────────────────────────────────────
    @Test fun anItemIsMatchedToTheShopTypesThatSellIt() {
        assertEquals("electronics", Things.categoryFor("Laptop", t)?.id)
        assertEquals("diy", Things.categoryFor("Bohrmaschine", t)?.id)           // a stem starts the compound
        assertEquals("furniture", Things.categoryFor("Esstisch", t)?.id)          // a long word ends it
        assertEquals("furniture", Things.categoryFor("großer Tisch", t)?.id)
        assertEquals("groceries", Things.categoryFor("vegetable", t)?.id)         // the longer word wins over the "table" at its end
        assertEquals("drugstore", Things.categoryFor("Shampoo, 250 ml", t)?.id)
        assertNull(Things.categoryFor("xyzzy", t))
        assertNull(Things.categoryFor("   ", t))
        assertNull("a word never matches in the middle of a token", Things.categoryFor("gestuhlt", t))
        assertNull("a word under three letters never matches", Things.categoryFor("ab", t.copy(categories = listOf(Things.Category("x", "x", listOf("a"), listOf("ab"))))))
        assertEquals("a short word of exactly three letters matches", "x", Things.categoryFor("usb", t.copy(categories = listOf(Things.Category("x", "x", listOf("a"), listOf("usb")))))?.id)
        assertNull("a short word does not end a token", Things.categoryFor("hubusb", t.copy(categories = listOf(Things.Category("x", "x", listOf("a"), listOf("usb"))))))
        assertEquals("a five-letter word does end one", "x", Things.categoryFor("gartentisch", t.copy(categories = listOf(Things.Category("x", "x", listOf("a"), listOf("tisch")))))?.id)
        assertEquals(listOf("a", "b1"), Things.tokens("A, b1!"))
    }

    @Test fun distanceIsGreatCircle() {
        assertEquals(0.0, Things.haversineKm(52.52, 13.405, 52.52, 13.405), 1e-9)
        assertEquals(504.0, Things.haversineKm(52.52, 13.405, 48.137, 11.575), 3.0)
        assertEquals(111.19, Things.haversineKm(0.0, 0.0, 1.0, 0.0), 0.05)
        assertEquals(111.19 * Math.cos(Math.toRadians(60.0)), Things.haversineKm(60.0, 0.0, 60.0, 1.0), 0.05)
    }

    // ── OpenStreetMap ────────────────────────────────────────────────────────────────────────────
    @Test fun theOverpassQueryAsksTheShopTypesWithinTheRadius() {
        val q = Things.overpassQuery(berlin, t.category("electronics")!!, 500)
        assertEquals("[out:json][timeout:25];nwr[\"shop\"~\"^(electronics|computer|mobile_phone|hifi|video_games|camera)$\"](around:20000,52.52000,13.40500);out center 500;", q)
        // a hostile shop value cannot break out of the regex
        val evil = Things.overpassQuery(berlin, Things.Category("e", "e", listOf("a\"];out;"), emptyList()), 5)
        assertTrue(evil, evil.contains("^(aout)$"))
    }

    @Test fun storesComeNearestFirstAndOnlyThoseWithAPlaceAndAName() {
        val stores = Things.parseStores(Fixtures.text("overpass-electronics.json"), berlin, 10)
        assertEquals(listOf("Euronics XXL Mitte", "Kleiner PC-Laden", "Cyberport", "MediaMarkt"), stores.map { it.name })
        assertTrue(stores.zipWithNext().all { (a, b) -> a.km <= b.km })
        val mm = stores.first { it.osm == "node/322490364" }
        assertEquals("MediaMarkt", mm.brand)
        assertEquals("Grunerstraße 20, 10179 Berlin", mm.address)
        assertEquals("https://www.mediamarkt.de/", mm.website)
        assertEquals("a way is placed by its centre", 52.5203, stores.first { it.osm == "way/9001" }.lat, 1e-9)
        assertEquals("a store with only a brand is named by it", "Cyberport", stores.first { it.osm == "node/9002" }.name)
        assertEquals("https://pc-laden.example/", stores.first { it.osm == "node/9005" }.website)
        assertNull(stores.first { it.osm == "node/9002" }.address)
        assertTrue("no name and no brand: dropped", stores.none { it.osm == "node/9003" })
        assertTrue("outside the radius: dropped", stores.none { it.osm == "node/9004" })
        assertTrue("no geometry: dropped", stores.none { it.osm == "relation/9006" })
        assertEquals(2, Things.parseStores(Fixtures.text("overpass-electronics.json"), berlin, 2).size)
        assertEquals("the cluster is ~1.5 km from here", 0, Things.parseStores(Fixtures.text("overpass-electronics.json"), berlin.copy(lat = 52.535, radiusKm = 1), 10).size)
        assertEquals(4, Things.parseStores(Fixtures.text("overpass-electronics.json"), berlin.copy(lat = 52.535, radiusKm = 2), 10).size)
        assertTrue(Things.parseStores("{}", berlin, 5).isEmpty())
    }

    @Test fun aTypedCityIsGeocodedToAnAreaOfTheAskedRadius() {
        val a = Things.parseGeocode(Fixtures.text("nominatim-koeln.json"), 20)!!
        assertEquals(50.938361, a.lat, 1e-6); assertEquals(6.959974, a.lon, 1e-6)
        assertEquals("Köln", a.label); assertEquals(20, a.radiusKm)
        assertEquals("50.94,6.96,20", a.key)
        assertEquals("Hof", Things.parseGeocode("""[{"lat":"1","lon":"2","display_name":"Hof, Bayern, Deutschland"}]""", 5)!!.label)
        assertEquals("?", Things.parseGeocode("""[{"lat":"1","lon":"2","display_name":""}]""", 5)!!.label)
        assertNull(Things.parseGeocode("[]", 5))
        assertNull(Things.parseGeocode("""[{"lat":"x","lon":"2"}]""", 5))
        assertNull(Things.parseGeocode("""[{"lat":"1","lon":"y"}]""", 5))
    }

    // ── Open Prices ──────────────────────────────────────────────────────────────────────────────
    @Test fun shelfPricesAreTheMatchingPerUnitDatedOnesAtAPlace() {
        val body = Fixtures.text("open-prices.json")
        val s = Things.parseShelf(body, "spülmittel", now, 365)
        assertEquals(1, s.size)
        assertEquals(Things.Shelf("node/5991258686", "Spülmittel Limone", 1.99, "EUR", Parsers.isoDay("2026-09-23"), true, "https://prices.openfoodfacts.org/products/4001499964541"), s[0])
        assertEquals("way/33447830", Things.parseShelf(body, "kakao hafer", now, 365).ifEmpty { Things.parseShelf(body, "cacao", now, 365) }.first().osm)
        assertEquals("a brand is part of what is named", "node/5991258686", Things.parseShelf(body, "frosch limone", now, 365).single().osm)
        assertEquals("a missing currency is read as euro", "EUR", Things.parseShelf(body, "monster", now, 365).single().currency)
        assertNull("a missing date is kept, undated", Things.parseShelf(body, "monster", now, 365).single().date)
        assertEquals("every word must be named", 0, Things.parseShelf(body, "spülmittel zitrone", now, 365).size)
        assertEquals("no words, no match", 0, Things.parseShelf(body, "  ", now, 365).size)
        assertEquals("a price older than the limit is dropped", 0, Things.parseShelf(body, "spülmittel", now + 400L * 86_400_000L, 365).size)
        assertEquals("a price on the limit day is kept", 1, Things.parseShelf(body, "spülmittel", Parsers.isoDay("2026-09-23")!! + 365L * 86_400_000L, 365).size)
        assertEquals(0, Things.parseShelf("{}", "x", now, 365).size)
        val perKg = """{"items":[{"id":1,"price":2.0,"price_per":"KILOGRAM","product_name":"Käse","location_osm_type":"NODE","location_osm_id":1,"date":"2026-09-30"},
            {"id":2,"price":3.0,"price_per":"UNIT","product_name":"Käse","location_osm_type":"NODE","location_osm_id":2,"date":"2026-09-30"},
            {"id":3,"price":4.0,"product_name":"Käse","location_osm_type":"NODE","date":"2026-09-30"},
            {"id":4,"price":5.0,"product_name":"Käse","location_osm_id":4,"date":"2026-09-30"},
            {"id":5,"product_name":"Käse","location_osm_type":"NODE","location_osm_id":5,"date":"2026-09-30"},
            {"id":6,"price":6.0,"location_osm_type":"NODE","location_osm_id":6,"date":"2026-09-30"}]}"""
        assertEquals("a price per kilogram is not comparable; one with no place, no price or no name is not a store price", listOf("node/2"), Things.parseShelf(perKg, "käse", now, 365).map { it.osm })
    }

    // ── the price service ────────────────────────────────────────────────────────────────────────
    @Test fun thePriceServiceAnswerIsReadAsPublished() {
        val b = Things.parseBackend(Fixtures.text("price-service.json"))
        assertEquals(listOf("obi", "euronics"), b.offers.map { it.adapter })
        val obi = b.offers[0]
        assertEquals("ok", obi.status); assertEquals(24.49, obi.price!!, 1e-9); assertEquals("EUR", obi.currency)
        assertEquals("CMI 600 W Bohrmaschine C-SBM-500 B", obi.title)
        assertEquals("https://www.obi.de/p/1001049/cmi-600-w-bohrmaschine-c-sbm-500-b", obi.url)
        assertNotNull(obi.at)
        assertEquals(listOf("obi", "euronics", "mediamarkt", "saturn", "dm", "ikea", "hornbach"), b.adapters.map { it.id })
        assertTrue(b.adapters.first { it.id == "obi" }.enabled)
        assertFalse(b.adapters.first { it.id == "mediamarkt" }.enabled)
        assertEquals(listOf("mediamarkt", "media markt"), b.adapters.first { it.id == "mediamarkt" }.brands)
        assertTrue(b.adapters.first { it.id == "mediamarkt" }.why.contains("403"))
    }

    @Test fun aPriceIsOnlyReadFromAnOkAnswer() {
        val body = """{"results":[{"adapter":"a","label":"A","status":"no_match","detail":"none","price":9.99,"title":"x","search_url":"https://a/s"},
            {"adapter":"b","label":"B","status":"ok","price":5.5,"currency":"CHF","title":"y","url":"https://b/p","fetched_at":7},
            {"adapter":"c","label":"C","status":"ok","title":"z"}],"adapters":[]}"""
        val o = Things.parseBackend(body).offers
        assertNull("a no_match answer never carries a price", o[0].price); assertNull(o[0].currency)
        assertEquals("https://a/s", o[0].url)
        assertEquals("none", o[0].detail)
        assertEquals(5.5, o[1].price!!, 1e-9); assertEquals("CHF", o[1].currency); assertEquals(7L, o[1].at)
        assertNull("ok without a price is no price", o[2].price)
        assertTrue(Things.parseBackend("""{}""").offers.isEmpty())
    }

    @Test fun aStoreBelongsToAChainByWholeWordsOfItsBrand() {
        val obi = Things.Adapter("obi", "OBI", true, listOf("obi"), "")
        val dm = Things.Adapter("dm", "dm", true, listOf("dm", "dm-drogerie markt"), "")
        assertTrue(Things.chainOf(store("n/1", "OBI Markt Mitte", "OBI"), obi))
        assertTrue(Things.chainOf(store("n/1", "OBI", null), obi))
        assertTrue("without a brand tag the name decides", Things.chainOf(store("n/1", "obi baumarkt", null), obi))
        assertTrue(Things.chainOf(store("n/1", "Marke", "dm-drogerie markt"), dm))
        assertFalse("a word inside another is not the chain", Things.chainOf(store("n/1", "Hobi", "Hobi Shop"), obi))
        assertFalse(Things.chainOf(store("n/1", "Obito", "Obito"), obi))
        assertFalse(Things.chainOf(store("n/1", "Adm", "Adm"), dm))
        assertEquals(listOf("euronics", "obi"), Things.brandsOf(listOf(store("n/1", "x", "OBI"), store("n/2", "y", "Euronics"), store("n/3", "z", "obi"))))
        assertEquals(listOf("kleiner laden"), Things.brandsOf(listOf(store("n/4", "Kleiner Laden", null))))
    }

    // ── the table ────────────────────────────────────────────────────────────────────────────────
    private fun backend(vararg offers: Things.Offer) = Things.Backend(
        offers.toList(),
        listOf(
            Things.Adapter("obi", "OBI", true, listOf("obi"), ""), Things.Adapter("euronics", "Euronics", true, listOf("euronics"), ""),
            Things.Adapter("mediamarkt", "MediaMarkt", false, listOf("mediamarkt"), "answers 403 to automated clients"),
        ),
    )
    private fun ok(a: String, p: Double) = Things.Offer(a, a, "ok", "", p, "EUR", "$a item", "https://$a/p", 5L)
    private fun no(a: String, s: String) = Things.Offer(a, a, s, "", null, null, null, null, 5L)

    @Test fun theTableIsCheapestFirstThenNearestAndNeverInventsAPrice() {
        val stores = listOf(
            store("node/1", "OBI Nord", "OBI", km = 3.0, site = "https://obi.de"),
            store("node/2", "Euronics Süd", "Euronics", km = 1.0),
            store("node/3", "MediaMarkt", "MediaMarkt", km = 0.5, site = "https://mm.de"),
            store("node/4", "Kleiner Laden", "Kleiner Laden", km = 0.2, site = "https://laden.example"),
            store("node/5", "OBI Süd", "OBI", km = 2.0),
        )
        val shelf = listOf(Things.Shelf("node/4", "Item", 9.0, "EUR", 100L, true, "https://p/4"), Things.Shelf("node/4", "Item big", 7.5, "EUR", 90L, false, null))
        val rows = Things.compare(stores, shelf, backend(ok("obi", 24.49), ok("euronics", 30.0)), "", 10)
        assertEquals(listOf(7.5, 24.49, 24.49, 30.0, null), rows.map { it.price })
        assertEquals("the nearer of two equal prices comes first", listOf("node/4", "node/5", "node/1", "node/2", "node/3"), rows.map { it.store.osm })
        assertEquals("the cheapest shelf price at that place", Things.Source.SHELF, rows[0].source)
        assertEquals("Item big", rows[0].title); assertEquals(90L, rows[0].at); assertNull(rows[0].url)
        assertEquals(Things.Source.ONLINE, rows[1].source); assertEquals("https://obi/p", rows[1].url); assertEquals(5L, rows[1].at)
        assertEquals(Things.Source.NONE, rows[4].source); assertNull(rows[4].currency); assertEquals("https://mm.de", rows[4].url)
        assertEquals("MediaMarkt: answers 403 to automated clients", rows[4].note)
        assertEquals("", rows[0].note)
    }

    @Test fun theCheaperOfAShelfAndAnOnlinePriceWins() {
        val s = listOf(store("node/1", "OBI", "OBI"))
        val dearShelf = listOf(Things.Shelf("node/1", "t", 50.0, "EUR", 1L, true, null))
        assertEquals(Things.Source.ONLINE, Things.compare(s, dearShelf, backend(ok("obi", 24.0)), "", 5)[0].source)
        val cheapShelf = listOf(Things.Shelf("node/1", "t", 10.0, "EUR", 1L, true, null))
        assertEquals(Things.Source.SHELF, Things.compare(s, cheapShelf, backend(ok("obi", 24.0)), "", 5)[0].source)
    }

    @Test fun everyStoreWithoutAPriceSaysWhy() {
        val s = { b: String -> store("node/9", b, b) }
        fun note(name: String, be: Things.Backend?, why: String = "") = Things.compare(listOf(s(name)), emptyList(), be, why, 5)[0].note
        assertEquals("no price source for this chain", note("Hofer", backend()))
        assertEquals("no shelf price here; price service: HTTP 500", note("Hofer", null, "price service: HTTP 500"))
        assertEquals("OBI: not asked", note("OBI", backend()))
        assertEquals("OBI: no product of that name on the site", note("OBI", backend(no("obi", "no_match"))))
        assertEquals("OBI: the site published no prices", note("OBI", backend(no("obi", "no_offers"))))
        assertEquals("OBI: the site does not allow automated reading", note("OBI", backend(no("obi", "blocked"))))
        assertEquals("OBI: lookup failed", note("OBI", backend(no("obi", "error"))))
        assertEquals("MediaMarkt: answers 403 to automated clients", note("MediaMarkt", backend()))
    }

    @Test fun theTableIsCappedAtTheDeclaredRows() {
        val stores = (1..5).map { store("node/$it", "S$it", "S$it", km = it.toDouble()) }
        assertEquals(3, Things.compare(stores, emptyList(), null, "x", 3).size)
        assertEquals(listOf("node/1", "node/2", "node/3"), Things.compare(stores, emptyList(), null, "x", 3).map { it.store.osm })
    }
}
