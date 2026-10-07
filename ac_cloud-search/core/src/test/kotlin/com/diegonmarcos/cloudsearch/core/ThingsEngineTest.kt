package com.diegonmarcos.cloudsearch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** #903 The Things engine against a fake network and a real cache directory: the sources, the cache, the offline view, the sign-in. */
class ThingsEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakeHttp : Http {
        val calls = mutableListOf<String>()
        val headers = mutableListOf<Map<String, String>>()
        var down = false
        var overpassFirstDown = false
        var service: (String) -> Http.Response = { Http.Response(200, Fixtures.text("price-service.json")) }
        override fun get(url: String, headers: Map<String, String>, timeoutMs: Int): Http.Response {
            calls += url; this.headers += headers
            if (down) throw java.io.IOException("offline")
            return when {
                "overpass-api.de" in url && overpassFirstDown -> Http.Response(504, "")
                "overpass" in url -> Http.Response(200, Fixtures.text("overpass-electronics.json"))
                "prices.openfoodfacts" in url -> Http.Response(200, Fixtures.text("open-prices.json"))
                "nominatim" in url -> Http.Response(200, if ("Atlantis" in url) "[]" else Fixtures.text("nominatim-koeln.json"))
                "/scrappers/prices" in url -> service(url)
                else -> Http.Response(404, "")
            }
        }
        override fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Int) = Http.Response(405, "")
    }

    private var now = Parsers.isoDay("2026-10-02")!!
    private val http = FakeHttp()
    private var token: String? = "tok-123"
    private fun engine() = ThingsEngine(Fixtures.cfg, http, Cache(tmp.root), { now }, { token })
    private val berlin = Things.Area(52.52, 13.405, 20, "Berlin")
    private fun status(r: ThingsEngine.Result, id: String) = r.statuses.first { it.id == id }

    @Test fun aSearchAsksTheStoresTheShelfAndTheSiteServiceAndSortsTheTable() {
        val r = engine().compare(berlin, "Laptop")
        assertEquals("electronics", r.category.id); assertTrue(r.matched)
        assertEquals(4, r.storesFound)
        assertEquals(SearchEngine.State.OK, status(r, ThingsEngine.ID_STORES).state)
        assertEquals(4, status(r, ThingsEngine.ID_STORES).count)
        assertEquals(SearchEngine.State.OK, status(r, ThingsEngine.ID_SERVICE).state)
        assertEquals(4, r.rows.size)
        assertTrue("the Overpass query names the category's shop types and the radius", http.calls.any { "overpass" in it && "electronics%7Ccomputer" in it && "around%3A20000%2C52.52000%2C13.41000" in it })
        assertTrue(http.calls.any { "prices.openfoodfacts" in it && "radius_km=20" in it && "lat=52.52" in it })
        val svc = http.calls.first { "/scrappers/prices" in it }
        assertTrue(svc, svc.contains("q=Laptop") && svc.contains("brands=cyberport%2Ceuronics%2Ckleiner%20pc-laden%2Cmediamarkt"))
        // Euronics has a real online price in the saved answer; the rest say why not
        val euro = r.rows.first { it.store.name.startsWith("Euronics") }
        assertEquals(Things.Source.ONLINE, euro.source); assertEquals(212.99, euro.price!!, 1e-9)
        assertEquals(euro, r.rows.first())
        assertTrue(r.rows.drop(1).all { it.price == null && it.note.isNotBlank() })
        assertEquals("MediaMarkt: Answers 403 to automated clients (verified 2026-10-07).", r.rows.first { it.store.name == "MediaMarkt" }.note)
    }

    @Test fun theTokenAuthorisesTheServiceCallAndIsNeverCachedOrInTheKey() {
        engine().compare(berlin, "Laptop")
        val i = http.calls.indexOfFirst { "/scrappers/prices" in it }
        assertEquals(mapOf("Authorization" to "Bearer tok-123"), http.headers[i])
        assertTrue(http.headers.filterIndexed { n, _ -> n != i }.all { it.isEmpty() })
        assertTrue("no cache file holds the token", tmp.root.listFiles()!!.none { it.readText().contains("tok-123") })
        assertTrue(http.calls.none { "tok-123" in it })
    }

    @Test fun withoutTheFleetSignInTheServiceIsSkippedAndSaysSo() {
        token = null
        val r = engine().compare(berlin, "Laptop")
        assertTrue(http.calls.none { "/scrappers/prices" in it })
        val st = status(r, ThingsEngine.ID_SERVICE)
        assertEquals(SearchEngine.State.SKIPPED, st.state); assertTrue(st.detail.contains("fleet sign-in"))
        assertTrue(r.rows.all { it.price == null })
        assertTrue(r.rows.all { it.note.startsWith("no shelf price here; store prices need the fleet sign-in") })
        token = "  "
        assertEquals(SearchEngine.State.SKIPPED, status(engine().compare(berlin, "Laptop"), ThingsEngine.ID_SERVICE).state)
    }

    @Test fun anItemNoCategoryNamesFallsBackToGeneralStoresAndSaysSo() {
        val r = engine().compare(berlin, "xyzzy")
        assertEquals("general", r.category.id); assertEquals(false, r.matched)
        assertTrue(http.calls.any { "overpass" in it && "department_store" in it })
    }

    @Test fun anEmptyItemAsksOnlyForTheStores() {
        val r = engine().compare(berlin, "  ")
        assertEquals(SearchEngine.State.SKIPPED, status(r, ThingsEngine.ID_SHELF).state)
        assertEquals("needs a search term", status(r, ThingsEngine.ID_SERVICE).detail)
        assertTrue(http.calls.none { "prices.openfoodfacts" in it || "/scrappers/prices" in it })
        assertEquals(4, r.rows.size)
    }

    @Test fun storesAreCachedPerAreaAndCategoryAndTheOtherSourcesPerTheirTtl() {
        val e = engine()
        e.compare(berlin, "Laptop")
        val asked = http.calls.size
        val again = e.compare(berlin, "Laptop")
        assertEquals("everything fresh comes from the cache", asked, http.calls.size)
        assertEquals(SearchEngine.State.CACHED, status(again, ThingsEngine.ID_STORES).state)
        e.compare(berlin.copy(radiusKm = 10), "Laptop")
        assertTrue("another radius is another area", http.calls.size > asked)
        val n = http.calls.size
        e.compare(berlin, "Handy")
        assertEquals("the same category in the same area reuses the stores (shelf and service ask again for the new item)", 1, http.calls.drop(n).count { "/scrappers/prices" in it })
        assertTrue(http.calls.drop(n).none { "overpass" in it })
        now += Fixtures.cfg.cacheTtlMinutes * 60_000L
        e.compare(berlin, "Laptop")
        assertTrue("the shelf prices are refetched after their ttl, the stores are not", http.calls.drop(n).count { "prices.openfoodfacts" in it } == 1 && http.calls.drop(n).none { "overpass" in it })
        now += Fixtures.cfg.things!!.storesTtlHours * 3_600_000L
        val before = http.calls.size
        e.compare(berlin, "Laptop")
        assertTrue("the stores are refetched after theirs", http.calls.drop(before).any { "overpass" in it })
    }

    @Test fun offlineShowsTheLastAnswersMarkedStaleAndANeverFetchedOneAsAnError() {
        val e = engine()
        e.compare(berlin, "Laptop")
        now += Fixtures.cfg.things!!.storesTtlHours * 3_600_000L
        http.down = true
        val r = e.compare(berlin, "Laptop")
        val st = status(r, ThingsEngine.ID_STORES)
        assertEquals(SearchEngine.State.STALE, st.state); assertEquals(4, st.count); assertTrue(st.detail.contains("offline"))
        assertEquals(Parsers.isoDay("2026-10-02"), st.at)
        assertEquals(4, r.rows.size)
        assertEquals(SearchEngine.State.STALE, status(r, ThingsEngine.ID_SERVICE).state)
        val fresh = ThingsEngine(Fixtures.cfg, http, Cache(tmp.newFolder()), { now }, { token }).compare(berlin, "Laptop")
        assertEquals(SearchEngine.State.ERROR, status(fresh, ThingsEngine.ID_STORES).state)
        assertTrue(status(fresh, ThingsEngine.ID_STORES).detail.contains("offline"))
        assertEquals(0, fresh.rows.size)
        assertEquals("no store to ask about", status(fresh, ThingsEngine.ID_SERVICE).detail)
        assertEquals(SearchEngine.State.ERROR, status(fresh, ThingsEngine.ID_SHELF).state)
    }

    @Test fun anOverloadedOverpassInstanceFallsThroughToTheNext() {
        http.overpassFirstDown = true
        val r = engine().compare(berlin, "Laptop")
        assertEquals(SearchEngine.State.OK, status(r, ThingsEngine.ID_STORES).state)
        assertEquals(2, http.calls.count { "overpass" in it })
        assertTrue(http.calls.first { "overpass" in it }.contains("overpass-api.de"))
    }

    @Test fun aPriceServiceFaultKeepsTheStoresAndNamesTheFault() {
        http.service = { Http.Response(401, "") }
        var r = engine().compare(berlin, "Laptop")
        assertEquals(SearchEngine.State.ERROR, status(r, ThingsEngine.ID_SERVICE).state)
        assertEquals("HTTP 401: not authorised", status(r, ThingsEngine.ID_SERVICE).detail)
        assertEquals(4, r.rows.size)
        assertTrue(r.rows.all { it.price == null && it.note.contains("price service: HTTP 401: not authorised") })
        http.service = { Http.Response(502, "") }
        r = ThingsEngine(Fixtures.cfg, http, Cache(tmp.newFolder()), { now }, { token }).compare(berlin, "Laptop")
        assertEquals("HTTP 502", status(r, ThingsEngine.ID_SERVICE).detail)
        http.service = { Http.Response(200, "not json") }
        r = ThingsEngine(Fixtures.cfg, http, Cache(tmp.newFolder()), { now }, { token }).compare(berlin, "Laptop")
        assertEquals(SearchEngine.State.ERROR, status(r, ThingsEngine.ID_SERVICE).state)
        assertTrue(status(r, ThingsEngine.ID_SERVICE).detail.contains("unreadable"))
        assertTrue(r.rows.all { it.price == null })
    }

    @Test fun anUnreadableStoresOrShelfAnswerIsAnErrorNotAnEmptyList() {
        val bad = object : Http {
            override fun get(url: String, headers: Map<String, String>, timeoutMs: Int) = Http.Response(200, "<html>")
            override fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Int) = Http.Response(405, "")
        }
        val r = ThingsEngine(Fixtures.cfg, bad, Cache(tmp.newFolder()), { now }, { null }).compare(berlin, "Laptop")
        assertEquals(SearchEngine.State.ERROR, status(r, ThingsEngine.ID_STORES).state)
        assertTrue(status(r, ThingsEngine.ID_STORES).detail.startsWith("unreadable answer"))
        assertEquals(SearchEngine.State.ERROR, status(r, ThingsEngine.ID_SHELF).state)
        assertTrue(status(r, ThingsEngine.ID_SHELF).detail.startsWith("unreadable answer"))
    }

    @Test fun theCentreLeavesThePhoneRoundedToAboutAKilometre() {
        val r = engine().compare(Things.Area(52.123456, 13.987654, 20, "here"), "Laptop")
        assertEquals(52.12, r.area.lat, 1e-9); assertEquals(13.99, r.area.lon, 1e-9)
        assertTrue(http.calls.none { "52.123" in it || "13.987" in it })
        assertTrue(http.calls.any { "lat=52.12" in it && "lon=13.99" in it })
    }

    @Test fun theRadiusIsClampedToTheDeclaredRange() {
        assertEquals(100, engine().compare(berlin.copy(radiusKm = 5000), "Laptop").area.radiusKm)
        assertEquals(1, engine().compare(berlin.copy(radiusKm = 0), "Laptop").area.radiusKm)
    }

    @Test fun aTypedCityBecomesAnAreaThroughTheCacheAndAnUnknownOneIsSaid() {
        val e = engine()
        val (a, st) = e.geocode("  Köln ", 20)
        assertEquals("Köln", a!!.label); assertEquals(SearchEngine.State.OK, st.state)
        assertTrue(http.calls.single().contains("q=K%C3%B6ln"))
        assertEquals(SearchEngine.State.CACHED, e.geocode("köln", 20).second.state)
        assertEquals("a city is the same place whatever its radius", 1, http.calls.size)
        assertEquals(5, e.geocode("Köln", 5).first!!.radiusKm)
        assertEquals(100, e.geocode("Köln", 9999).first!!.radiusKm)
        val (none, st2) = e.geocode("Atlantis", 20)
        assertNull(none); assertEquals(SearchEngine.State.ERROR, st2.state); assertTrue(st2.detail.contains("Atlantis"))
        val (blank, st3) = e.geocode("   ", 20)
        assertNull(blank); assertEquals(SearchEngine.State.SKIPPED, st3.state)
        http.down = true
        val (off, st4) = ThingsEngine(Fixtures.cfg, http, Cache(tmp.newFolder()), { now }, { token }).geocode("Bonn", 20)
        assertNull(off); assertEquals(SearchEngine.State.ERROR, st4.state)
    }

    @Test fun theDebugJsonCarriesTheRowsHonestly() {
        val e = engine()
        val j = e.json(e.compare(berlin, "Laptop"))
        assertEquals("electronics", j.getString("category")); assertEquals(true, j.getBoolean("category_matched"))
        assertEquals(20, j.getJSONObject("area").getInt("radius_km"))
        val rows = j.getJSONArray("rows")
        assertEquals(4, rows.length())
        assertEquals("online", rows.getJSONObject(0).getString("source")); assertEquals(212.99, rows.getJSONObject(0).getDouble("price"), 1e-9)
        assertTrue(rows.getJSONObject(1).isNull("price")); assertEquals("none", rows.getJSONObject(1).getString("source"))
        assertTrue(rows.getJSONObject(1).getString("note").isNotBlank())
    }
}
