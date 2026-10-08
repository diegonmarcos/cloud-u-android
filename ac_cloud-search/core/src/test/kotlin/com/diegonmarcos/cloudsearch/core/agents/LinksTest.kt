package com.diegonmarcos.cloudsearch.core.agents

import com.diegonmarcos.cloudsearch.core.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinksTest {
    private val rules = Fixtures.cfg.agents!!.agent("house_search")!!.links
    private val html = Fixtures.text("wg-gesucht-alert.html")
    private val txt = Fixtures.text("wg-gesucht-alert.txt")

    @Test fun listingLinksOfAnHtmlAlertAreFoundOncePerListing() {
        val l = Links.extract("", html, rules)
        assertEquals(listOf("11223344", "11223355", "9988776"), l.map { it.id })
        assertEquals("https://www.wg-gesucht.de/wg-zimmer-in-Berlin-Friedrichshain.11223344.html", l[0].url)
        assertEquals("https://www.wg-gesucht.de/en/wg-zimmer-in-Berlin-Neukoelln.11223355.html", l[1].url)
        assertEquals("https://www.wg-gesucht.de/1-zimmer-wohnungen-in-Berlin-Mitte.9988776.html", l[2].url)
    }

    @Test fun accountMessagingAndForeignLinksAreNotListings() {
        val all = Links.extract("", html, rules).map { it.url }
        for (bad in listOf("mein-wg-gesucht", "abmelden", "nachricht", "impressum", "evil.example", "javascript", "mailto", "55555555", "11223399"))
            assertTrue(bad, all.none { it.contains(bad) })
        assertTrue("a five-digit minimum keeps a short number out", all.none { it.contains(".12.html") })
    }

    @Test fun plainTextLinksAreFoundWithPunctuationAndBracketsStripped() {
        val l = Links.extract(txt, "", rules)
        assertEquals(listOf("11223344", "7766554", "4433221"), l.map { it.id })
        assertEquals("https://www.wg-gesucht.de/wohnungen-in-Berlin-Prenzlauer-Berg.7766554.html", l[1].url)
        assertEquals("https://www.wg-gesucht.de/wg-zimmer-in-Berlin-Wedding.4433221.html", l[2].url)
    }

    @Test fun anAmpEntityInTextDoesNotBreakTheLink() {
        val l = Links.extract(txt, "", rules)
        assertTrue(l.none { it.url.contains("utm") || it.url.contains("&") })
    }

    @Test fun oneListingIsOneLinkAcrossPartsAndSlugs() {
        val both = Links.extract(txt, html, rules)
        assertEquals(listOf("11223344", "11223355", "9988776", "7766554", "4433221").sorted(), both.map { it.id }.sorted())
        assertEquals(both.size, both.map { it.id }.toSet().size)
        // first sight wins: the html part came first
        assertEquals("https://www.wg-gesucht.de/wg-zimmer-in-Berlin-Friedrichshain.11223344.html", both.first { it.id == "11223344" }.url)
    }

    @Test fun hostMustBeTheAllowedOneOrASubdomain() {
        assertNotNull(Links.canonical("https://www.wg-gesucht.de/x.1234567.html", rules))
        assertNotNull(Links.canonical("https://wg-gesucht.de/x.1234567.html", rules))
        assertNotNull(Links.canonical("HTTPS://WWW.WG-GESUCHT.DE/x.1234567.html", rules))
        assertNull(Links.canonical("https://notwg-gesucht.de/x.1234567.html", rules))
        assertNull(Links.canonical("https://wg-gesucht.de.evil.example/x.1234567.html", rules))
        assertNull(Links.canonical("ftp://www.wg-gesucht.de/x.1234567.html", rules))
        assertNull(Links.canonical("not a url", rules))
        assertNull(Links.canonical("https://www.wg-gesucht.de/", rules))
    }

    @Test fun anHttpLinkIsUpgradedToHttps() {
        assertEquals("https://www.wg-gesucht.de/x.1234567.html", Links.canonical("http://www.wg-gesucht.de/x.1234567.html#top", rules)!!.url)
    }

    @Test fun excludedPathsIncludeLanguagePrefixes() {
        assertNull(Links.canonical("https://www.wg-gesucht.de/en/nachricht.html?id=1234567", rules))
        assertNull(Links.canonical("https://www.wg-gesucht.de/de/mein-wg-gesucht.1234567.html", rules))
        assertNull(Links.canonical("https://www.wg-gesucht.de/nachricht.1234567.html", rules))
    }

    @Test fun idMustEndThePathAndBeBounded() {
        assertEquals("12345678", Links.canonical("https://www.wg-gesucht.de/12345678.html", rules)!!.id)
        assertNull(Links.canonical("https://www.wg-gesucht.de/x.1234567.php", rules))
        assertNull(Links.canonical("https://www.wg-gesucht.de/x.1234.html", rules))
        assertNull(Links.canonical("https://www.wg-gesucht.de/x1234567.html", rules))
        assertEquals("1234567", Links.canonical("https://www.wg-gesucht.de/x.1234567.html", rules)!!.id)
    }

    @Test fun nothingInNothingOut() {
        assertTrue(Links.extract("", "", rules).isEmpty())
        assertTrue(Links.extract("no links here", "<p>none</p>", rules).isEmpty())
    }
}
