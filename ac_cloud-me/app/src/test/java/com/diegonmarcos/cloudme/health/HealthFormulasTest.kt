package com.diegonmarcos.cloudme.health

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The declared formulas evaluated (BMR = 370 + 21.6 x LBM, TDEE multiplier, sauna loss, gut flow curve, circadian wrap). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HealthFormulasTest {
    private val c = HealthTestData.content()
    private val f = c.formulas

    @Test fun `anthropometrics from mass height and body fat`() {
        val a = HealthFormulas.anthro(f, 80.0, 182.0, 12.0, null)!!
        assertEquals(70.4, a.lbm, 1e-9)
        assertEquals(70.4 / (1.82 * 1.82), a.ffmi, 1e-9)
        assertEquals(1891, a.bmr)            // 370 + 21.6 * 70.4 = 1890.64
        assertEquals(2969, a.tdee)           // 1891 x 1.57 (the declared default, hard training)
        assertEquals("Above average", a.ffmiBand)
    }

    @Test fun `the activity level picks its declared multiplier`() {
        assertEquals(Math.round(1891 * 1.2).toInt(), HealthFormulas.anthro(f, 80.0, 182.0, 12.0, "sedentary")!!.tdee)
        assertEquals(Math.round(1891 * 1.725).toInt(), HealthFormulas.anthro(f, 80.0, 182.0, 12.0, "very_hard")!!.tdee)
        assertEquals("an unknown level falls back to the default", 2969, HealthFormulas.anthro(f, 80.0, 182.0, 12.0, "nope")!!.tdee)
    }

    @Test fun `a missing or impossible input gives no numbers, never a guess`() {
        assertNull(HealthFormulas.anthro(f, null, 182.0, 12.0, null))
        assertNull(HealthFormulas.anthro(f, 80.0, null, 12.0, null))
        assertNull(HealthFormulas.anthro(f, 80.0, 182.0, null, null))
        assertNull(HealthFormulas.anthro(f, 0.0, 182.0, 12.0, null))
        assertNull(HealthFormulas.anthro(f, 80.0, 182.0, 100.0, null))
    }

    @Test fun `sauna loses 47_5 mg of sodium chloride a minute`() {
        val s = HealthFormulas.sauna(f, 20)
        assertEquals(950, s.naclMg)
        assertEquals(374, s.sodiumMg)
        assertEquals(0.95 / 6.0, s.saltTeaspoons, 1e-9)
        assertEquals(1900, HealthFormulas.sauna(f, 40).naclMg)
    }

    @Test fun `fluid to replace is 150 percent of the mass lost`() {
        assertEquals(1200, HealthFormulas.fluidToReplaceMl(f, 80.0, 79.2))
        assertNull(HealthFormulas.fluidToReplaceMl(f, 80.0, 80.0))
        assertNull(HealthFormulas.fluidToReplaceMl(f, 79.0, 80.0))
        assertNull(HealthFormulas.fluidToReplaceMl(f, null, 80.0))
    }

    @Test fun `gut flow follows the declared curve`() {
        assertEquals(100.0, HealthFormulas.gutFlowPercent(f, 0.0), 1e-9)
        assertEquals(70.0, HealthFormulas.gutFlowPercent(f, 50.0), 1e-9)
        assertEquals(55.0, HealthFormulas.gutFlowPercent(f, 60.0), 1e-9)
        assertEquals(20.0, HealthFormulas.gutFlowPercent(f, 100.0), 1e-9)
        assertEquals(20.0, HealthFormulas.gutFlowPercent(f, 150.0), 1e-9)
    }

    @Test fun `the circadian curve hits its points and wraps past midnight`() {
        val p = f.circadian.single().points
        assertEquals(95.0, HealthFormulas.circadianAt(p, 420.0), 1e-9)
        assertEquals(45.0, HealthFormulas.circadianAt(p, 750.0), 1e-9)
        assertEquals(12.0, HealthFormulas.circadianAt(p, 1380.0), 1e-9)
        // midnight sits 60 of the 480 minutes between 23:00 (12) and 07:00 (95)
        assertEquals(12.0 + 83.0 * 60.0 / 480.0, HealthFormulas.circadianAt(p, 0.0), 1e-9)
        assertEquals(HealthFormulas.circadianAt(p, 0.0), HealthFormulas.circadianAt(p, 1440.0), 1e-9)
    }

    @Test fun `meal to training gaps use the fat rule`() {
        val gaps = HealthFormulas.mealGaps(f, c.plan)
        assertEquals(listOf("lunch", "prelift"), gaps.map { it.meal.id })
        assertEquals(listOf(360, 90), gaps.map { it.minutes })
        assertTrue(gaps.all { it.ok })
        val fatty = c.plan.copy(timeline = c.plan.timeline.map { if (it.id == "prelift") it.copy(fat = true) else it })
        val g = HealthFormulas.mealGaps(f, fatty).first { it.meal.id == "prelift" }
        assertEquals(f.hemoFatGapMin, g.needed)
        assertTrue("90 min is under the 120 a fatty meal needs", !g.ok)
    }

    @Test fun `biomarker status against min and max`() {
        val ferritin = c.plan.biomarkers.first { it.id == "ferritin" }
        val zinc = c.plan.biomarkers.first { it.id == "zinc" }
        assertEquals(HealthFormulas.Status.Below, HealthFormulas.biomarkerStatus(ferritin, 60.0))
        assertEquals(HealthFormulas.Status.InRange, HealthFormulas.biomarkerStatus(ferritin, 120.0))
        assertEquals(HealthFormulas.Status.Below, HealthFormulas.biomarkerStatus(zinc, 80.0))
        assertEquals(HealthFormulas.Status.InRange, HealthFormulas.biomarkerStatus(zinc, 100.0))
        assertEquals(HealthFormulas.Status.Above, HealthFormulas.biomarkerStatus(zinc, 140.0))
        assertEquals("90 to 130 ug/dL", HealthFormulas.rangeText(zinc))
    }

    @Test fun `the next event wraps to tomorrow`() {
        assertEquals("lunch", HealthFormulas.nextEvent(c.plan, 0)!!.id)
        assertEquals("train", HealthFormulas.nextEvent(c.plan, 17 * 60 + 1)!!.id)
        assertEquals("lunch", HealthFormulas.nextEvent(c.plan, 23 * 60 + 30)!!.id)
        assertNotNull(HealthFormulas.nextEvent(c.plan, 600))
    }
}
