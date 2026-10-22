package com.diegonmarcos.superapp.notificationcenter

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.notificationcenter.WeatherLocation.Fix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The persisted fix: lat/lon/accuracy/time/source survive a "process death" (a new store over the same file). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WeatherFixStoreTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()

    @Test fun roundTripsEveryFieldAcrossANewInstance() {
        val f = Fix(40.4168, -3.7038, 27.5f, 1_700_000_000_000L, "network")
        assertNull(WeatherFixStore(ctx).load())
        assertTrue(WeatherFixStore(ctx).save(f))
        assertEquals(f, WeatherFixStore(ctx).load())
    }

    @Test fun olderOrEqualOrInvalidNeverReplacesAndNothingClears() {
        val s = WeatherFixStore(ctx)
        val f = Fix(48.85, 2.35, 10f, 2_000L, "gps")
        s.save(f)
        assertFalse(s.save(Fix(1.0, 1.0, 5f, 1_000L, "network")))
        assertFalse(s.save(f.copy(lat = 2.0)))
        assertFalse(s.save(Fix(Double.NaN, 0.0, 1f, 9_000L, "network")))
        assertEquals(f, WeatherFixStore(ctx).load())
        val newer = Fix(41.0, 2.0, 5f, 3_000L, "network")
        assertTrue(s.save(newer))
        assertEquals(newer, WeatherFixStore(ctx).load())
    }

    @Test fun forecastCacheSurvivesToo() {
        val c = WeatherFixStore.Cached("Madrid", 40.4, -3.7, "{\"a\":1}", 5L)
        WeatherFixStore(ctx).saveForecast(c)
        assertEquals(c, WeatherFixStore(ctx).loadForecast())
        assertNotNull(WeatherFixStore(ctx).loadForecast())
    }
}
