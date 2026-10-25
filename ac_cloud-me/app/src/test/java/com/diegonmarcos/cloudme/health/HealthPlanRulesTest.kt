package com.diegonmarcos.cloudme.health

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Plan to shopping-list derivation and the absorption simulator's rules, over the real declared plan. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HealthPlanRulesTest {
    private val c = HealthTestData.content()
    private fun need(id: String) = HealthFormulas.shopping(c.plan).first { it.item.id == id }

    @Test fun `the weekly list is the plan times seven days, rounded up to whole packs`() {
        assertEquals(7, need("yogurt").packs)               // 7 x 350 g = 2450 g = 7 tubs of 350
        assertEquals(2450.0, need("yogurt").perWeek, 1e-9)
        assertEquals(4, need("chicken").packs)              // 7 x 250 g = 1750 g -> four 500 g packs = 2 kg
        assertEquals(2000.0, need("chicken").buy, 1e-9)
        assertEquals(1, need("rice").packs)                 // 7 x 140 g = 980 g -> one 1 kg bag
        assertEquals(2, need("oats").packs)                 // 560 g -> two 500 g bags
        assertEquals(7.0, need("banana").buy, 1e-9)
        assertEquals(1, need("creatine").packs)             // 35 g
    }

    @Test fun `every item of every window is on the list exactly once`() {
        val ids = c.plan.timeline.flatMap { e -> (e.items + e.supplements).map { it.id } }
        assertEquals(ids.toSet(), HealthFormulas.shopping(c.plan).map { it.item.id }.toSet())
        assertEquals(ids.size, HealthFormulas.shopping(c.plan).size)
    }

    @Test fun `the same item in two windows is summed, and an exact fit does not buy an extra pack`() {
        val yogurt = c.plan.timeline.first().items.first { it.id == "yogurt" }
        val twice = c.plan.copy(timeline = c.plan.timeline + c.plan.timeline.first().copy(id = "again"))
        assertEquals(14, HealthFormulas.shopping(twice).first { it.item.id == "yogurt" }.packs)
        val oneDay = c.plan.copy(daysPerWeek = 1)
        assertEquals(1, HealthFormulas.shopping(oneDay).first { it.item.id == "yogurt" }.packs)
        assertEquals(350.0, yogurt.qty, 1e-9)
    }

    @Test fun `changing the plan data changes the list (it is derived, not written)`() {
        val noDairy = c.plan.copy(timeline = c.plan.timeline.map { e -> e.copy(items = e.items.filterNot { it.id == "yogurt" }) })
        assertTrue(HealthFormulas.shopping(noDairy).none { it.item.id == "yogurt" })
    }

    private val d get() = c.plan.clash
    private fun verdict(p: String, k: String) =
        HealthFormulas.clash(d, d.proteins.first { it.id == p }.tags, d.cofactors.first { it.id == k }.tags)!!

    @Test fun `chicken with vegetables is optimal synergy`() = assertEquals("synergy", verdict("chicken", "none").level)
    @Test fun `chicken with cheese or milk is antagonism`() = assertEquals("antagonism", verdict("chicken", "cheese").level)
    @Test fun `yogurt alone is sub-optimal and yogurt with cheese is antagonism`() {
        assertEquals("suboptimal", verdict("yogurt", "none").level)
        assertEquals("antagonism", verdict("yogurt", "cheese").level)
    }

    @Test fun `the simulator checks your own plan window by window`() {
        fun lvl(id: String) = HealthFormulas.clashForWindow(d, c.plan.timeline.first { it.id == id })?.level
        assertEquals("suboptimal", lvl("lunch"))      // yogurt blocks its own minerals: why it is cordoned to lunch
        assertEquals("synergy", lvl("dinner"))        // zero dairy
        assertEquals("synergy", lvl("prelift"))
        assertNull(lvl("train"))                       // no food in the window
        val dairyDinner = c.plan.timeline.first { it.id == "dinner" }.let { e -> e.copy(items = e.items + c.plan.timeline.first().items.first { it.id == "yogurt" }.copy(role = "side")) }
        assertEquals("antagonism", HealthFormulas.clashForWindow(d, dairyDinner)?.level)
    }
}
