package org.fossify.phone.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dual-SIM selection: who wins when several rules apply, and what happens with no SIM or one. */
class SimSelectionTest {
    private val a = SimInfo(subscriptionId = 11, slotIndex = 0, displayName = "Home")
    private val b = SimInfo(subscriptionId = 22, slotIndex = 1, displayName = "Work")

    @Test
    fun noSimSendsNothingSpecial() {
        assertEquals(SimChoice.NoSim, SimSelection.choose(emptyList(), picked = 11))
    }

    @Test
    fun oneSimIsAlwaysUsedWithoutAsking() {
        assertEquals(SimChoice.Use(a), SimSelection.choose(listOf(a)))
        assertEquals(SimChoice.Use(a), SimSelection.choose(listOf(a), picked = 99))
    }

    @Test
    fun twoSimsWithNoPreferenceAsk() {
        val choice = SimSelection.choose(listOf(b, a))
        assertTrue(choice is SimChoice.Ask)
        assertEquals(listOf(a, b), (choice as SimChoice.Ask).sims)
    }

    @Test
    fun theOwnersPickBeatsEverything() {
        assertEquals(SimChoice.Use(b), SimSelection.choose(listOf(a, b), picked = 22, lastUsedInThread = 11, appPreferred = 11, systemDefault = 11))
    }

    @Test
    fun theThreadKeepsItsLineBeforeAnyDefault() {
        assertEquals(SimChoice.Use(b), SimSelection.choose(listOf(a, b), lastUsedInThread = 22, appPreferred = 11, systemDefault = 11))
    }

    @Test
    fun theAppPreferenceBeatsTheSystemDefault() {
        assertEquals(SimChoice.Use(a), SimSelection.choose(listOf(a, b), appPreferred = 11, systemDefault = 22))
        assertEquals(SimChoice.Use(b), SimSelection.choose(listOf(a, b), systemDefault = 22))
    }

    @Test
    fun aSubscriptionThatIsNoLongerActiveIsSkipped() {
        assertEquals(SimChoice.Use(b), SimSelection.choose(listOf(a, b), picked = 99, lastUsedInThread = 77, appPreferred = 55, systemDefault = 22))
        assertTrue(SimSelection.choose(listOf(a, b), picked = 99, lastUsedInThread = 77) is SimChoice.Ask)
    }
}
