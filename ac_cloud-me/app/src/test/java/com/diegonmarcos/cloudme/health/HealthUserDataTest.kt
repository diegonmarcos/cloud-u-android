package com.diegonmarcos.cloudme.health

import android.app.Application
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** User data store, Health Connect aggregation and the dossier text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HealthUserDataTest {
    private val c = HealthTestData.content()

    @Test fun `a fresh install has no data at all`() {
        val d = HealthUserStore.load(RuntimeEnvironment.getApplication())
        assertEquals(HealthUserData(), d)
        assertNull(d.latestMeasure())
    }

    @Test fun `user data survives a save and a load, partial entries included`() {
        val ctx = RuntimeEnvironment.getApplication()
        val d = HealthUserData()
            .withMeasurement(Measurement(2000, 80.0, 182.0, 12.0)).withMeasurement(Measurement(1000, 81.0, null, null))
            .withLab(LabResult("ferritin", 88.0, 5)).copy(
                done = setOf("2026-10-25:lunch"), shopChecked = setOf("w:rice"), shopExtra = listOf("coffee"),
                activity = "light", sleepTargetH = 7.5, dossierNotes = "line1\nline2", dossierSections = setOf("labs"),
            ).withWorkout(WorkoutEntry("2026-10-25", "pull", "deadlift", true, 140.0))
        HealthUserStore.save(ctx, d)
        val back = HealthUserStore.load(ctx)
        assertEquals(d, back)
        assertEquals(listOf(1000L, 2000L), back.measurements.map { it.t })
        assertNull(back.measurements.first().heightCm)
        assertEquals(2000L, back.latestMeasure()!!.t)
    }

    @Test fun `the store is the declared prefs file`() {
        val ctx = RuntimeEnvironment.getApplication()
        HealthUserStore.save(ctx, HealthUserData(dossierNotes = "x"))
        assertTrue(ctx.getSharedPreferences("health_user", 0).getString("data", null)!!.contains("dossier_notes"))
    }

    @Test fun `blank or malformed stored text is an empty record, not a crash`() {
        assertEquals(HealthUserData(), HealthUserData.fromJson(null))
        assertEquals(HealthUserData(), HealthUserData.fromJson(""))
        assertEquals(HealthUserData(), HealthUserData.fromJson("{not json"))
    }

    @Test fun `ticks toggle and a workout entry replaces its own day and exercise`() {
        val d = HealthUserData()
        assertEquals(setOf("a"), d.toggled(d.done, "a"))
        assertEquals(emptySet<String>(), d.copy(done = setOf("a")).toggled(setOf("a"), "a"))
        val w = d.withWorkout(WorkoutEntry("d1", "pull", "dl", false, null)).withWorkout(WorkoutEntry("d1", "pull", "dl", true, 100.0))
        assertEquals(1, w.workout.size)
        assertEquals(100.0, w.workout.single().loadKg!!, 0.0)
    }

    private val zone = ZoneId.of("UTC")
    private fun at(day: Int, h: Int) = Instant.parse("2026-10-%02dT%02d:00:00Z".format(day, h))

    @Test fun `readings group into per day means and sums`() {
        val r = listOf(Reading(at(20, 8), 50.0), Reading(at(20, 20), 52.0), Reading(at(21, 8), 48.0))
        assertEquals(listOf(51.0, 48.0), HealthSeries.dailyMean(r, zone).map { it.value })
        assertEquals(listOf(102.0, 48.0), HealthSeries.dailySum(r, zone).map { it.value })
        assertEquals(LocalDate.of(2026, 10, 20), HealthSeries.dailyMean(r, zone).first().day)
        assertTrue(HealthSeries.dailyMean(emptyList(), zone).isEmpty())
    }

    @Test fun `a sleep session counts stages, excludes awake time and is dated the day it ends`() {
        val n = HealthSeries.night(at(20, 23), at(21, 7), listOf(
            Triple(at(20, 23), at(21, 1), androidx.health.connect.client.records.SleepSessionRecord.STAGE_TYPE_LIGHT),
            Triple(at(21, 1), at(21, 3), androidx.health.connect.client.records.SleepSessionRecord.STAGE_TYPE_DEEP),
            Triple(at(21, 3), at(21, 4), androidx.health.connect.client.records.SleepSessionRecord.STAGE_TYPE_AWAKE),
            Triple(at(21, 4), at(21, 7), androidx.health.connect.client.records.SleepSessionRecord.STAGE_TYPE_REM),
        ), zone)
        assertEquals(LocalDate.of(2026, 10, 21), n.day)
        assertEquals(120.0, n.stageMin.getValue("light"), 0.0)
        assertEquals(120.0, n.stageMin.getValue("deep"), 0.0)
        assertEquals(180.0, n.stageMin.getValue("rem"), 0.0)
        assertEquals(60.0, n.stageMin.getValue("awake"), 0.0)
        assertEquals(7 * 60.0, n.totalMin, 0.0)       // 8 h in bed minus 1 h awake
    }

    @Test fun `sleep debt never goes negative per night`() {
        val nights = listOf(SleepNight(LocalDate.of(2026, 10, 20), 6 * 60.0, emptyMap()), SleepNight(LocalDate.of(2026, 10, 21), 9 * 60.0, emptyMap()))
        assertEquals(1.5, HealthSeries.sleepDebtH(nights, 7.5), 1e-9)   // 1.5 h short, the 1.5 h surplus does not repay it
    }

    @Test fun `the dossier states what is missing instead of inventing values`() {
        val s = HealthDossier.build(c, HealthUserData(), HealthDossier.ALL_SECTIONS.toSet(), emptyList())
        assertEquals(listOf("profile", "labs", "plan", "trackers"), s.map { it.id })
        assertEquals("No measurement entered.", s.first().lines.single())
        assertTrue(s.first { it.id == "labs" }.lines.all { "no result entered" in it })
        assertTrue(s.first { it.id == "trackers" }.lines.single().contains("No Health Connect data"))
        assertEquals(c.plan.timeline.size, s.first { it.id == "plan" }.lines.size)
    }

    @Test fun `the dossier uses the latest entries and respects the section choice`() {
        val u = HealthUserData().withMeasurement(Measurement(1, 80.0, 182.0, 12.0)).withLab(LabResult("zinc", 140.0, 1)).copy(dossierNotes = "ask about iron")
        val s = HealthDossier.build(c, u, setOf("profile", "labs", "notes"), emptyList())
        assertEquals(listOf("profile", "labs", "notes"), s.map { it.id })
        assertTrue(s.first().lines.any { "BMR 1891" in it })
        assertTrue(s.first { it.id == "labs" }.lines.any { "Plasma zinc: 140 ug/dL" in it && "Above" in it })
        assertEquals(listOf("ask about iron"), s.last().lines)
    }

    @Test fun `long report lines wrap on word boundaries`() {
        val w = HealthDossier.wrap("alpha beta gamma delta", 11)
        assertEquals(listOf("alpha beta", "gamma delta"), w)
        assertEquals(listOf("short"), HealthDossier.wrap("short", 95))
    }
}
