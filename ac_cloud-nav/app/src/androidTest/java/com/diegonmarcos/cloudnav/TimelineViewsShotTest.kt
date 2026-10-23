package com.diegonmarcos.cloudnav

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.diegonmarcos.cloudnav.maps.MapsDb
import com.diegonmarcos.cloudnav.maps.MapsDemo
import com.diegonmarcos.cloudnav.maps.TimelineState
import com.diegonmarcos.cloudnav.maps.TlKind
import com.diegonmarcos.cloudnav.tracker.EngineDb
import com.diegonmarcos.superapp.bottomnav.PageTabsTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/**
 * #1114 evidence: the OLD tables (stops, step samples) are seeded exactly as the trackers wrote them, the app migrates them into
 * THE timeline store on first view, and Timeline > Daily / Stops / Steps are drawn over it. Each page is screenshotted to
 * /data/local/tmp/tl-<page>.png (test-cloud-nav.yml pulls them into the uploaded evidence).
 */
@RunWith(AndroidJUnit4::class)
class TimelineViewsShotTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val zone = ZoneId.systemDefault()
    private fun t(day: LocalDate, h: Int, m: Int = 0) = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    private fun seedDay(db: MapsDb, eng: EngineDb, day: LocalDate): Long {
        fun stop(a: Long, b: Long, lat: Double, lon: Double, place: String, hood: String) {
            val id = db.insertStop(MapsDb.StopRow(a, b, lat, lon))
            db.enrichStop(id, place, hood, "Madrid", "Spain")
        }
        stop(t(day, 0), t(day, 7, 30), 40.4168, -3.7038, "Home", "Centro")
        stop(t(day, 9), t(day, 17, 30), 40.4300, -3.6800, "Office", "Salamanca")
        stop(t(day, 18, 30), t(day, 19, 30), 40.4090, -3.7100, "Gym", "Arganzuela")
        var steps = 0L
        // walking after each stop: 15-minute engine samples
        for (start in listOf(t(day, 7, 30) to 90, t(day, 17, 30) to 60, t(day, 19, 30) to 60)) {
            for (k in 1..start.second / 15) { eng.addSample(start.first + k * 15 * 60_000L, 400L, null); steps += 400L }
        }
        return steps
    }

    private fun shot(name: String) {
        val inst = InstrumentationRegistry.getInstrumentation()
        Thread.sleep(1_500)   // let the list settle
        val pfd = inst.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/tl-$name.png")
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }   // returns when the command ended
    }

    @Test fun daily_stops_and_steps_are_views_over_the_one_timeline_store() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val db = MapsDb.get(ctx); val eng = EngineDb.get(ctx)
        db.clearAll(); MapsDemo.resetSeedFlag(ctx)
        eng.writableDatabase.execSQL("DELETE FROM step_samples"); eng.writableDatabase.execSQL("DELETE FROM engine_fixes")
        TimelineState(ctx).migratedAt = 0L          // the legacy tables are all there is: the first view must migrate them
        val today = LocalDate.now()
        val seeded = seedDay(db, eng, today.minusDays(1)) + seedDay(db, eng, today.minusDays(2))
        assertEquals("nothing in the store before the first view", 0, db.segmentCount())

        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitUntil(30_000) { compose.onAllNodesWithTag("bottomnav_item_timeline").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("bottomnav_item_timeline").performClick()
                compose.waitUntil(30_000) { compose.onAllNodesWithTag(PageTabsTags.tab("daily")).fetchSemanticsNodes().isNotEmpty() }

                compose.onNodeWithTag(PageTabsTags.tab("daily")).performClick()
                compose.waitUntil(30_000) { compose.onAllNodesWithText("Office", substring = true).fetchSemanticsNodes().isNotEmpty() }
                shot("daily")

                compose.onNodeWithTag(PageTabsTags.tab("stops")).performClick()
                compose.waitUntil(30_000) { compose.onAllNodesWithText("Gym", substring = true).fetchSemanticsNodes().isNotEmpty() }
                shot("stops")

                compose.onNodeWithTag(PageTabsTags.tab("steps")).performClick()
                compose.waitForIdle()
                shot("steps")
            }
            // the store those three pages read: start/sleep, move, stop, move, stop, move, stop - per day, steps conserved
            val segs = db.segmentsBetween(0L, System.currentTimeMillis())
            val kinds = segs.filter { it.startMs >= t(today.minusDays(1), 0) && it.endMs <= t(today, 0) }.map { it.kind }
            assertEquals(listOf(TlKind.SLEEP, TlKind.MOVE, TlKind.STOP, TlKind.MOVE, TlKind.STOP, TlKind.MOVE), kinds.take(6))
            assertTrue("a stop per seeded stop", segs.count { it.kind != TlKind.MOVE } >= 6)
            assertTrue("steps conserved: ${segs.sumOf { it.steps }} vs $seeded", Math.abs(segs.sumOf { it.steps } - seeded) <= 5)
            assertTrue("the migration marker is set", TimelineState(ctx).migratedAt > 0L)
        } finally {
            db.clearAll(); MapsDemo.resetSeedFlag(ctx)
            eng.writableDatabase.execSQL("DELETE FROM step_samples")
        }
    }
}
