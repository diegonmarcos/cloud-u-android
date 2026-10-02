package com.diegonmarcos.cloudsearch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every parser against a response saved from the real source on 2026-10-02. */
class ParsersTest {
    private val cfg = Fixtures.cfg
    private fun src(id: String) = cfg.sources.getValue(id)

    @Test fun bundesagentur() {
        val l = Parsers.parse("ba", Fixtures.text("ba-jobsuche.json"), src("ba-jobsuche"))
        assertEquals(4, l.size)
        val a = l[0]
        assertEquals("12016-10005410012-S", a.id)
        assertEquals("Entwickler m/w/d - Einkaufscenter *", a.title)
        assertEquals("PerZukunft Arbeitsvermittlung GmbH & Co. KG • Berlin", a.subtitle)
        assertEquals(13.9, a.price!!, 0.0)
        assertEquals("h", a.unit)
        assertEquals("EUR", a.currency)
        assertEquals("https://www.arbeitsagentur.de/jobsuche/jobdetail/12016-10005410012-S", a.url)
        assertEquals(Parsers.isoDay("2026-09-24"), a.date)
        assertTrue(a.verified)
        assertTrue("Full-time" in a.tags)
        assertEquals("ba-jobsuche:12016-10005410012-S", a.key)
        val b = l[1]
        assertNull(b.price)
        assertNull(b.unit)
        assertFalse("Full-time" in b.tags)
    }

    @Test fun arbeitnow() {
        val l = Parsers.parse("arbeitnow", Fixtures.text("arbeitnow.json"), src("arbeitnow"))
        assertEquals(6, l.size)
        assertEquals("software-engineer-price-comparison-portal-berlin-233707", l[0].id)
        assertEquals("Preiswecker • Berlin", l[0].subtitle)
        assertEquals(1786516800_000L, l[0].date)
        assertEquals(false, l[0].remote)
        assertEquals(listOf("Full-time", "Software Engineering", "E-Commerce"), l[0].tags)
        assertEquals(true, l[3].remote)
        assertFalse(l[0].verified)
    }

    @Test fun openPrices() {
        val l = Parsers.parse("open_prices", Fixtures.text("open-prices.json"), src("open-prices"))
        assertEquals(6, l.size)
        assertEquals("34207", l[0].id)
        assertNull("no currency in the answer means none shown, never a guess", l[0].currency)
        assertNull(l[0].date)
        assertEquals("Boisson au cacao et à l'avoine", l[1].title)
        assertEquals(1.15, l[1].price!!, 0.0)
        assertEquals("EUR", l[1].currency)
        assertEquals(Parsers.isoDay("2026-10-01"), l[1].date)
        assertEquals("Lidl • Berlin", l[1].subtitle)
        assertTrue("a price with a proof is verified", l[1].verified)
        assertEquals("4000647576902", l[3].title) // empty product name falls back to the barcode
        assertTrue("Discount" in l[3].tags)
        assertEquals("https://prices.openfoodfacts.org/products/4337256112260", l[4].url)
    }

    @Test fun openFoodFacts() {
        val l = Parsers.parse("off_search", Fixtures.text("off-search.json"), src("open-food-facts"))
        assertEquals(4, l.size)
        assertEquals("Hafermilch", l[0].title)
        assertEquals("Lidl • 1pcs", l[0].subtitle)
        assertEquals(listOf("Nutri-Score C"), l[0].tags)
        assertNull(l[0].price)
        assertEquals("https://world.openfoodfacts.org/product/4056489626633", l[0].url)
        assertEquals(emptyList<String>(), l[3].tags) // "unknown" is not a grade
    }

    @Test fun rss() {
        val items = Parsers.rss(Fixtures.text("tagesschau-verbraucher.xml"), "tagesschau Verbraucher")
        assertEquals(6, items.size)
        assertEquals("Tankrabatt für Autofahrer, aber kein Nachlass beim Heizen: ungerecht?", items[0].title)
        assertEquals("https://www.tagesschau.de/wirtschaft/verbraucher/heizen-tankrabatt-debatte-100.html", items[0].url)
        assertEquals(java.time.ZonedDateTime.parse("2026-10-02T14:06:54+02:00").toInstant().toEpochMilli(), items[0].date)
        assertFalse(items[0].text.contains("<"))
    }

    @Test fun listingRoundTripsThroughJson() {
        for (l in Parsers.parse("ba", Fixtures.text("ba-jobsuche.json"), src("ba-jobsuche")) +
            Parsers.parse("arbeitnow", Fixtures.text("arbeitnow.json"), src("arbeitnow"))) {
            assertEquals(l, Listing.fromJson(l.toJson()))
        }
    }

    @Test fun stripAndDays() {
        assertEquals("a b c", Parsers.strip("<p>a</p>&nbsp;b\n <b>c</b>"))
        assertNull(Parsers.isoDay(null))
        assertNull(Parsers.isoDay("garbage"))
        assertEquals(86_400_000L, Parsers.isoDay("1970-01-02T10:00:00"))
    }

    @Test(expected = IllegalArgumentException::class) fun unknownParserFails() {
        Parsers.parse("nope", "{}", src("arbeitnow"))
    }
}
