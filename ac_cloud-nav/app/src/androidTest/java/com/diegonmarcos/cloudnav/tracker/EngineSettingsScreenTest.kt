package com.diegonmarcos.cloudnav.tracker

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Configs > Tracker page under the fleet palette: groups, the source rule, steppers, the permission rationale, the explanation. */
@RunWith(AndroidJUnit4::class)
class EngineSettingsScreenTest {
    @get:Rule val rule = createComposeRule()

    private var last: EngineConfig? = null
    private var requested: Perm? = null

    private fun show(ui: EngineUi) {
        last = null; requested = null
        rule.setContent {
            CloudKitTheme(TrackerInk.palette()) {
                EngineSettingsScreen(ui, EngineActions(onConfig = { last = it }, onRequest = { requested = it }))
            }
        }
    }

    private fun tap(tag: String) = rule.onNodeWithTag(tag).performScrollTo().performClick()

    @Test fun showsTheThreeGroupsAndTheExplanation() {
        show(EngineUi())
        for (t in listOf(EngineTags.SRC_GPS, EngineTags.SRC_CELL, EngineTags.ACC_TIME, EngineTags.ACC_STEPS, EngineTags.CELL_TIME, EngineTags.MOTION, EngineTags.EXPLAIN))
            rule.onNodeWithTag(t).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("How this engine works").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(EngineTags.value(EngineTags.ACC_TIME)).assertTextEquals("4 h")
        rule.onNodeWithTag(EngineTags.value(EngineTags.ACC_STEPS)).assertTextEquals("2000 steps")
        rule.onNodeWithTag(EngineTags.value(EngineTags.CELL_TIME)).assertTextEquals("1 h")
        rule.onNodeWithTag(EngineTags.value(EngineTags.MOTION)).assertTextEquals("100 steps")
    }

    @Test fun lastSourceCannotBeSwitchedOff() {
        show(EngineUi(EngineConfig(useGps = true, useCell = false)))
        tap(EngineTags.SRC_GPS)
        assertNull(last)
        rule.onNodeWithTag(EngineTags.HINT).performScrollTo().assertIsDisplayed()
    }

    @Test fun gpsOffMovesTheAccurateFixToCellTowers() {
        show(EngineUi(EngineConfig(useGps = true, useCell = true)))
        tap(EngineTags.SRC_GPS)
        assertEquals(false, last!!.useGps)
        assertEquals(Provider.NETWORK, SourceRules.accurateProvider(last!!))
    }

    @Test fun gpsOffPageSaysSo() {
        show(EngineUi(EngineConfig(useGps = false, useCell = true)))
        rule.onNodeWithText("GPS is off: accurate fixes use cell towers.").performScrollTo().assertIsDisplayed()
    }

    @Test fun steppersMoveThroughPresets() {
        show(EngineUi())
        tap(EngineTags.plus(EngineTags.ACC_TIME)); assertEquals(6, last!!.accurateEveryHours)
        tap(EngineTags.minus(EngineTags.ACC_STEPS)); assertEquals(1000, last!!.accurateEverySteps)
        tap(EngineTags.plus(EngineTags.MOTION)); assertEquals(200, last!!.motionEverySteps)
        tap(EngineTags.plus(EngineTags.CELL_TIME)); assertEquals(2, last!!.cellEveryHours)
    }

    @Test fun triggerCheckboxesToggle() {
        show(EngineUi())
        tap(EngineTags.ACC_STEPS); assertEquals(false, last!!.accurateBySteps)
        tap(EngineTags.MOTION); assertEquals(false, last!!.motionBySteps)
    }

    @Test fun enableSwitchTurnsTheEngineOn() {
        show(EngineUi())
        tap(EngineTags.ENABLED)
        assertTrue(last!!.enabled)
    }

    @Test fun permissionIsAskedWithARationaleFirst() {
        show(EngineUi(granted = setOf(Perm.LOCATION)))
        tap(EngineTags.grant(Perm.BACKGROUND))
        assertNull(requested)                                   // nothing asked yet
        rule.onNodeWithText(Perm.BACKGROUND.why).assertIsDisplayed()
        rule.onNodeWithTag(KitTags.DIALOG_CONFIRM).performClick()
        assertEquals(Perm.BACKGROUND, requested)
    }

    @Test fun decliningTheRationaleAsksNothing() {
        show(EngineUi())
        tap(EngineTags.grant(Perm.ACTIVITY))
        rule.onNodeWithTag(KitTags.DIALOG_DISMISS).performClick()
        assertNull(requested)
    }

    @Test fun missingStepSensorIsSaid() {
        show(EngineUi(hasStepSensor = false))
        rule.onNodeWithText("This phone has no step counter: step triggers and estimates are unavailable.").assertIsDisplayed()
    }
}
