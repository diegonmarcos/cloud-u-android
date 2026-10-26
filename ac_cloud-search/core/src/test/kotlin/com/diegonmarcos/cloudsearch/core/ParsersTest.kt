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

    @Test fun openverse() {
        val l = Parsers.parse("openverse", Fixtures.text("openverse-images.json"), src("openverse-images"))
        assertEquals(3, l.size)
        assertEquals("1c5442f6-6bb6-4ab7-b603-f598e7579dd2", l[0].id)
        assertEquals("Cat Fish 2", l[0].title)
        assertEquals("admiller • flickr", l[0].subtitle)
        assertEquals("https://www.flickr.com/photos/32426194@N00/3481540500", l[0].url)
        assertEquals("https://api.openverse.org/v1/images/1c5442f6-6bb6-4ab7-b603-f598e7579dd2/thumb/", l[0].image)
        assertEquals(listOf("CC BY 2.0"), l[0].tags)
        assertEquals(listOf("CC BY-NC-SA 2.0"), l[1].tags)
        assertNull(l[0].price)
    }

    @Test fun archiveOrg() {
        val l = Parsers.parse("archive_org", Fixtures.text("archive-movies.json"), src("archive-video"))
        assertEquals(4, l.size)
        assertEquals("Experime1940", l[0].id)
        assertEquals("Experiments in the Revival of Organisms", l[0].title)
        assertEquals("Techfilm Studio, Moscow • 1940", l[0].subtitle)
        assertEquals("https://archive.org/details/Experime1940", l[0].url)
        assertEquals("https://archive.org/services/img/Experime1940", l[0].image)
        assertEquals("an item with no creator or year has an empty subtitle", "", l[1].subtitle)
        assertEquals("NASA/Johnson Space Center • 1969", l[3].subtitle)
    }

    @Test fun tvmaze() {
        val l = Parsers.parse("tvmaze", Fixtures.text("tvmaze-shows.json"), src("tvmaze-shows"))
        assertEquals(3, l.size)
        assertEquals("3616", l[0].id)
        assertEquals("Matrix", l[0].title)
        assertEquals("1993 • USA Network", l[0].subtitle)
        assertEquals("https://www.tvmaze.com/shows/3616/matrix", l[0].url)
        assertEquals(Parsers.isoDay("1993-03-01"), l[0].date)
        assertEquals(listOf("Drama", "Ended"), l[0].tags)
        assertEquals("a web channel stands in for the network", "2012 • Gaia", l[2].subtitle)
        assertTrue(l[0].image!!.startsWith("https://static.tvmaze.com/"))
    }

    @Test fun openLibrary() {
        val l = Parsers.parse("open_library", Fixtures.text("open-library.json"), src("open-library-books"))
        assertEquals(4, l.size)
        assertEquals("/works/OL893414W", l[0].id)
        assertEquals("Dune", l[0].title)
        assertEquals("Frank Herbert • 1965", l[0].subtitle)
        assertEquals("https://openlibrary.org/works/OL893414W", l[0].url)
        assertEquals("https://covers.openlibrary.org/b/id/11481354-M.jpg", l[0].image)
        assertEquals("open_library:/works/OL893414W", l[0].key)
    }

    @Test fun atomYouTubeFeed() {
        val items = Parsers.feed("atom", Fixtures.text("youtube-feed.xml"), "Google for Developers")
        assertEquals(4, items.size)
        assertEquals("🔊 Voice design and voice replication with Gemini 3.8 Flash TTS", items[0].title)
        assertEquals("https://www.youtube.com/shorts/CsF5oqVV5YE", items[0].url)
        assertEquals(java.time.ZonedDateTime.parse("2026-10-21T23:00:08+00:00").toInstant().toEpochMilli(), items[0].date) // the entry's own date, not the channel's
        assertTrue(items[0].text.startsWith("Google DeepMind"))
        assertFalse(items[0].text.contains("<"))
        assertEquals("Google for Developers", items[0].feed)
        assertEquals("https://www.youtube.com/watch?v=SOF3_evPje8", items[1].url)
    }

    @Test fun googleNewsRssFeed() {
        val items = Parsers.feed("rss", Fixtures.text("google-news-technology.xml"), "Google News Technology")
        assertEquals(5, items.size)
        assertEquals("Hinge damage suspected to be behind iPhone Duo screen cracks - AppleInsider", items[0].title)
        assertTrue(items[0].url!!.startsWith("https://news.google.com/rss/articles/"))
        assertEquals(java.time.ZonedDateTime.parse("2026-10-25T21:28:00Z").toInstant().toEpochMilli(), items[0].date)
        assertFalse("the escaped markup of the description is stripped", items[0].text.contains("<"))
    }

    @Test fun anAtomFeedWithoutAnAlternateLinkOrDateStillParses() {
        val xml = """<feed xmlns="http://www.w3.org/2005/Atom"><entry><title>T</title><link href="https://e.org/x"/><updated>2026-01-02T03:04:05Z</updated><summary>S</summary></entry></feed>"""
        val i = Parsers.atom(xml, "f").single()
        assertEquals("https://e.org/x", i.url)
        assertEquals("S", i.text)
        assertEquals(java.time.ZonedDateTime.parse("2026-01-02T03:04:05Z").toInstant().toEpochMilli(), i.date)
        assertNull(Parsers.atom("""<feed><entry><title>T</title></entry></feed>""", "f").single().url)
    }

    @Test fun rss() {
        val items = Parsers.rss(Fixtures.text("tagesschau-verbraucher.xml"), "tagesschau Verbraucher")
        assertEquals(6, items.size)
        assertEquals("Tankrabatt für Autofahrer, aber kein Nachlass beim Heizen: ungerecht?", items[0].title)
        assertEquals("https://www.tagesschau.de/wirtschaft/verbraucher/heizen-tankrabatt-debatte-100.html", items[0].url)
        assertEquals(java.time.ZonedDateTime.parse("2026-10-02T11:22:31+02:00").toInstant().toEpochMilli(), items[0].date) // the item's own pubDate, not the channel's
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
