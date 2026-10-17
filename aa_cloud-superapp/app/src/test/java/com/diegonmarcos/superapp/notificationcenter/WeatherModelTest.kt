package com.diegonmarcos.superapp.notificationcenter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** MET Norway locationforecast/complete parsing and the dense badge head. */
class WeatherModelTest {
    private val utc = ZoneId.of("UTC")

    private fun entry(time: String, temp: Double, p1: Double? = null, p6: Double? = null): String {
        val n1 = p1?.let { ""","next_1_hours":{"summary":{"symbol_code":"rain"},"details":{"probability_of_precipitation":$it}}""" } ?: ""
        val n6 = p6?.let { ""","next_6_hours":{"details":{"probability_of_precipitation":$it}}""" } ?: ""
        return """{"time":"$time","data":{"instant":{"details":{"air_temperature":$temp}}$n1$n6}}"""
    }
    private fun body(vararg e: String) = """{"type":"Feature","properties":{"timeseries":[${e.joinToString(",")}]}}"""

    @Test fun parsesCurrentRangeHoursAndRainMax() {
        val b = body(
            entry("2026-10-17T09:00:00Z", 10.0, p1 = 90.0),   // past hour: ignored
            entry("2026-10-17T10:00:00Z", 12.4, p1 = 10.0),
            entry("2026-10-17T12:00:00Z", 18.0, p1 = 40.0),
            entry("2026-10-17T15:00:00Z", 15.0, p1 = 5.0),
            entry("2026-10-17T20:00:00Z", 9.0, p1 = 80.0),    // beyond the 6 h window
            entry("2026-10-18T00:00:00Z", 7.0),               // tomorrow: not in today's range
        )
        val f = WeatherModel.parse(b, Instant.parse("2026-10-17T10:20:00Z"), utc)!!
        assertEquals(12.4, f.currentC, 0.0)
        assertEquals(18.0, f.maxC, 0.0)
        assertEquals(9.0, f.minC, 0.0)
        assertEquals(40, f.rainPct)
        assertEquals(listOf(10 to 12.4, 12 to 18.0, 15 to 15.0, 20 to 9.0), f.hours)
    }
    @Test fun fallsBackToSixHourProbability() {
        val f = WeatherModel.parse(body(entry("2026-10-17T10:00:00Z", 5.0, p6 = 33.4)), Instant.parse("2026-10-17T10:00:00Z"), utc)!!
        assertEquals(33, f.rainPct)
    }
    @Test fun noProbabilityMeansNullNotZero() {
        val f = WeatherModel.parse(body(entry("2026-10-17T10:00:00Z", 5.0)), Instant.parse("2026-10-17T10:00:00Z"), utc)!!
        assertNull(f.rainPct)
        assertEquals("5°", WeatherModel.headline(f))
    }
    @Test fun hoursAreLocal() {
        val f = WeatherModel.parse(body(entry("2026-10-17T10:00:00Z", 5.0)), Instant.parse("2026-10-17T10:00:00Z"), ZoneId.of("Europe/Paris"))!!
        assertEquals(listOf(12 to 5.0), f.hours)
    }
    @Test fun brokenBodyIsNull() {
        assertNull(WeatherModel.parse(null, Instant.EPOCH, utc))
        assertNull(WeatherModel.parse("{}", Instant.EPOCH, utc))
        assertNull(WeatherModel.parse("not json", Instant.EPOCH, utc))
    }
    @Test fun headlineIsDense() {
        assertEquals("18° · 💧40%", WeatherModel.headline(WeatherModel.Forecast(18.2, 20.0, 9.0, 40, emptyList())))
        assertEquals("0° · 💧0%", WeatherModel.headline(WeatherModel.Forecast(-0.3, 1.0, -2.0, 0, emptyList())))
    }
    @Test fun apiUrlAndUserAgent() {
        assertEquals("https://api.met.no/weatherapi/locationforecast/2.0/complete?lat=48.8566&lon=2.3522", WeatherModel.apiUrl(48.85661, 2.35222))
        assertTrue(WeatherModel.USER_AGENT.contains("github.com"))
    }
}
