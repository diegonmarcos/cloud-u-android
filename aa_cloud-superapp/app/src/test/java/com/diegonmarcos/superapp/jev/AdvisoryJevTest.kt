package com.diegonmarcos.superapp.jev

import com.diegonmarcos.superapp.updater.Advisory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #882 updater_urgency: THE FALLBACK TEST. "Jev has no opinion" (null, an empty string, "normal", anything the
 * engine could not make into one of the three options) keeps the 6 h re-notify window the updater always had.
 */
class AdvisoryJevTest {

    private val sixHours = 6L * 60 * 60 * 1000

    @Test fun noOpinionIsTodaysSixHours() {
        for (u in listOf(null, "", "normal", "unknown", "LOW", "interrupt ")) {
            assertEquals("urgency=$u", sixHours, Advisory.renotifyWindow(u))
        }
    }

    @Test fun onlyAConfidentLowOrInterruptMovesTheWindow() {
        assertEquals(60L * 60 * 1000, Advisory.renotifyWindow(Advisory.URGENCY_INTERRUPT))
        assertEquals(24L * 60 * 60 * 1000, Advisory.renotifyWindow(Advisory.URGENCY_LOW))
        assertTrue(Advisory.renotifyWindow("interrupt") < sixHours && Advisory.renotifyWindow("low") > sixHours)
    }

    @Test fun theQuestionOffersExactlyTheThreeOptionsTheEngineMayAccept() {
        val q = Advisory.urgencyQuestions().getJSONObject("urgency")
        assertEquals("choice", q.getString("type"))
        val keys = q.getJSONObject("criteria").keys().asSequence().toSet()
        assertEquals(setOf("low", "normal", "interrupt"), keys)
    }
}
