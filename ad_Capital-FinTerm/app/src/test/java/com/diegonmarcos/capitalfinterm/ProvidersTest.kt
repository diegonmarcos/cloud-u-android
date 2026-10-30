package com.diegonmarcos.capitalfinterm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The provider answers parsed from fixtures shaped like the real ones. */
class ProvidersTest {
    @Test fun dbnomicsSeriesWithNaAndLatest() {
        val json = """{"series":{"docs":[
            {"series_code":"USA.M.IRLT","series_name":"United States","period":["2026-03","2026-04","2026-05"],"value":[4.1,4.2,"NA"]},
            {"series_code":"X","series_name":"Empty","period":["2026-03"],"value":["NA"]}]}}"""
        val s = Providers.dbnomics(json)
        assertEquals(2, s.size)
        assertEquals(Triple("2026-04", 4.2, 4.1), s[0].latest())
        assertNull(s[1].latest())
        assertTrue(Providers.dbnomics("{}").isEmpty())
        assertTrue(Providers.dbnomics("<html>").isEmpty())
    }

    @Test fun worldBankDropsRowsWithoutAValue() {
        val json = """[{"page":1},[
            {"countryiso3code":"DEU","country":{"value":"Germany"},"date":"2025","value":2.17},
            {"countryiso3code":"FRA","country":{"value":"France"},"date":"2025","value":null}]]"""
        assertEquals(listOf(CountryValue("DEU", "Germany", "2025", 2.17)), Providers.worldBank(json))
        assertTrue(Providers.worldBank("""[{"message":[{"id":"120"}]}]""").isEmpty())
    }

    @Test fun imfTakesTheLatestYearOfEachWantedCountry() {
        val json = """{"values":{"GGXWDG_NGDP":{"USA":{"2023":120.1,"2024":121.4},"DEU":{"2024":62.5},"SDN":{"2024":282.3}}}}"""
        val got = Providers.imf(json, "GGXWDG_NGDP", listOf("USA", "DEU", "FRA"), mapOf("USA" to "United States"))
        assertEquals(listOf(CountryValue("USA", "United States", "2024", 121.4), CountryValue("DEU", "DEU", "2024", 62.5)), got)
    }

    @Test fun gdeltArticlesAndSeenDate() {
        val json = """{"articles":[{"url":"https://a.example/x","title":"Yields rise","domain":"a.example","seendate":"20261030T120500Z"},{"title":"","url":"u"}]}"""
        val a = Providers.gdelt(json)
        assertEquals(listOf(Article("Yields rise", "https://a.example/x", "a.example", "2026-10-30 12:05")), a)
        assertTrue(Providers.gdelt("Please limit requests to one every 5 seconds").isEmpty())
        assertEquals("odd", Providers.seen("odd"))
    }
}
