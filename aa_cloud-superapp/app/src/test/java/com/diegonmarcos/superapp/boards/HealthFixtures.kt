package com.diegonmarcos.superapp.boards

import com.diegonmarcos.superapp.health.HealthBoardReader.Night
import com.diegonmarcos.superapp.health.HealthBoardReader.Raw
import com.diegonmarcos.superapp.health.HealthBoardReader.Status
import com.diegonmarcos.superapp.health.HealthBoardReader
import java.time.LocalDate

/** Seeded Health Connect values for the board tests and the CI screenshot. Test data only; the app ships none. */
object HealthFixtures {
    fun seeded(today: LocalDate): Raw {
        val days = HealthAgg.window(today, 30)
        val steps = HashMap<LocalDate, Long>(); val dist = HashMap<LocalDate, Double>()
        val rhr = HashMap<LocalDate, Long>(); val sleep = HashMap<LocalDate, Night>()
        days.forEachIndexed { i, d ->
            steps[d] = if (d == today) 11_000L else 7_000L + (i % 7) * 900L
            if (i % 3 != 0) dist[d] = steps[d]!! * 0.72          // every third day has no distance record: estimated
            if (i % 8 != 5) rhr[d] = 54L + (i % 6)               // some days have no reading
            if (i % 9 != 4) sleep[d] = Night(70L + (i % 4) * 10, 95L + (i % 3) * 10, 230L + (i % 5) * 12, 20L + (i % 4) * 6, true)
        }
        rhr[today] = 56L                                          // today always has a reading: the card shows it
        sleep[today] = Night(84, 102, 241, 26, true)
        return Raw(Status.READY, setOf(HealthBoardReader.STEPS, HealthBoardReader.DISTANCE, HealthBoardReader.RESTING_HR,
            HealthBoardReader.SLEEP, HealthBoardReader.HEIGHT), steps, dist, rhr, sleep, 1.78)
    }
}
