package com.diegonmarcos.cloudagenda

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.diegonmarcos.cloudagenda.agent.MailItem
import com.diegonmarcos.cloudagenda.agent.MailLink
import com.diegonmarcos.cloudagenda.data.AgentConfig
import com.diegonmarcos.cloudagenda.data.ThemeMode
import com.diegonmarcos.cloudagenda.ui.LocalDarkTheme
import com.diegonmarcos.cloudagenda.ui.MailPickerDialog
import com.diegonmarcos.cloudagenda.ui.MailPickerTags
import com.diegonmarcos.cloudagenda.ui.TasksPalettes
import com.diegonmarcos.cloudagenda.ui.TasksTheme
import com.diegonmarcos.cloudagenda.ui.agentBanner
import com.diegonmarcos.cloudagenda.agent.AgentStatus
import com.diegonmarcos.superapp.uikit.KitState
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The light / dark / system theme applied through CloudKitTheme, the mail link format, the mail picker and the agent banner. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemeAndMailTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules = composeChain(compose)

    private fun seen(mode: ThemeMode, systemDark: Boolean): Triple<Boolean, Color, Color> {
        var out: Triple<Boolean, Color, Color>? = null
        compose.setContent {
            TasksTheme(mode, systemDark) {
                out = Triple(LocalDarkTheme.current, LocalKitPalette.current.surface, MaterialTheme.colorScheme.background)
            }
        }
        compose.waitForIdle()
        return out!!
    }

    @Test fun `Light is light even on a dark device`() {
        assertEquals(Triple(false, TasksPalettes.light.surface, TasksPalettes.light.surface), seen(ThemeMode.LIGHT, systemDark = true))
    }

    @Test fun `Dark draws the dark palette even when the device is light`() {
        assertEquals(Triple(true, TasksPalettes.dark.surface, TasksPalettes.dark.surface), seen(ThemeMode.DARK, systemDark = false))
    }

    @Test fun `System draws dark on a dark device`() {
        assertEquals(Triple(true, TasksPalettes.dark.surface, TasksPalettes.dark.surface), seen(ThemeMode.SYSTEM, systemDark = true))
    }

    @Test fun `the two palettes really differ in every role the pages paint with`() {
        val l = TasksPalettes.light; val d = TasksPalettes.dark
        assertNotEquals(l.surface, d.surface); assertNotEquals(l.textPrimary, d.textPrimary); assertNotEquals(l.textSecondary, d.textSecondary)
        assertNotEquals(l.surfaceSelected, d.surfaceSelected); assertNotEquals(l.accent, d.accent); assertNotEquals(l.hairline, d.hairline)
    }

    // ── mail links and the picker ───────────────────────────────────────────────────────────────────────

    @Test fun `a mail link round-trips, ids with slashes and spaces included, and a foreign string is not one`() {
        val ref = MailLink.build("acc/1", "msg 42")
        assertEquals("acc/1" to "msg 42", MailLink.parse(ref))
        assertNull(MailLink.parse("https://example.com"))
        assertNull(MailLink.parse("mail:"))
        assertNull(MailLink.parse("mail:onlyaccount"))
    }

    private val m1 = MailItem("m1", "acc", "inbox", "Dentist appointment", "Praxis Klein", "p@k.de", 1_700_000_000_000, false)
    private val m2 = MailItem("m2", "acc", "inbox", "Invoice 77", "", "billing@x.com", 1_700_000_100_000, true)

    @Test fun `the picker lists mail, filters by subject and returns the picked message`() {
        var picked: MailItem? = null
        val source = FakeMailSource(listOf(m1, m2))
        compose.setContent { TasksTheme(ThemeMode.LIGHT) { MailPickerDialog(source, { picked = it }, {}) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTagCount(MailPickerTags.item("m1")) == 1 }
        compose.onNodeWithTag(MailPickerTags.item("m2")).assertIsDisplayed()
        compose.onNodeWithTag(MailPickerTags.SEARCH).performTextInput("dentist")
        compose.waitUntil(5_000) { compose.onAllNodesWithTagCount(MailPickerTags.item("m2")) == 0 }
        compose.onNodeWithTag(MailPickerTags.item("m1")).performClick()
        assertEquals(m1, picked)
        assertEquals("mail:acc/m1", picked!!.ref)
    }

    @Test fun `when Cloud Mail is not there the picker says why`() {
        compose.setContent { TasksTheme(ThemeMode.LIGHT) { MailPickerDialog(FakeMailSource(reason = "Cloud Mail is not installed"), {}, {}) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTagCount(MailPickerTags.EMPTY) == 1 }
        compose.onNodeWithTag(MailPickerTags.EMPTY).assertIsDisplayed()
    }

    // ── the agent banner ────────────────────────────────────────────────────────────────────────────────

    @Test fun `the banner never dresses a missing backend up as a result`() {
        assertEquals(KitState.IDLE, agentBanner(AgentConfig(enabled = false)).second)
        val nd = agentBanner(AgentConfig(enabled = true, lastStatus = AgentStatus.BACKEND_NOT_DEPLOYED, lastDetail = "no route"))
        assertEquals("Agent backend not deployed: no route", nd.first)
        assertEquals(KitState.WARN, nd.second)
        assertEquals(KitState.OK, agentBanner(AgentConfig(enabled = true, lastStatus = AgentStatus.OK, lastDetail = "2 new proposals")).second)
        assertEquals(KitState.BAD, agentBanner(AgentConfig(enabled = true, lastStatus = AgentStatus.ERROR, lastDetail = "x")).second)
        assertTrue(agentBanner(AgentConfig(enabled = true)).first.startsWith("Saved."))
    }
}

private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTagCount(tag: String): Int =
    onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes(atLeastOneRootRequired = false).size
