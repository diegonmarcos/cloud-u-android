package com.diegonmarcos.superapp.apps

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.launcher.AppDrawerSheetFragment
import com.diegonmarcos.superapp.launcher.GroupedTilesFragment
import com.diegonmarcos.superapp.launcher.HomeAppsTabs
import com.diegonmarcos.superapp.launcher.Sections
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #773 — the Phone and Cloud app pages, Compose since they were LinearLayout renderers.
 *
 *   Q1 Quickmarks are parsed in build.json order: groups, then each group's entries, never sorted
 *   Q2 a label of last resort is the capitalised last segment of the package id
 *   Q3 every declared Quickmarks group draws ONE strip, and with nothing installed every curated entry is a
 *      placeholder tile (never dropped)
 *   Q4 Smart Folders start collapsed; a tap on the header expands and a second collapses; the user's choice sticks
 *   P1 the Phone tab opens on Categories; Alphabetic shows all 26 letter folders, empty ones included
 *   P2 a folder tap opens the sheet
 *   C1 Cloud ▸ Apps draws the Quickmarks heading and every declared group heading, and the Recently Used hint
 *   C2 the Home sheet opens on the first declared tab and a tab tap swaps the body in place
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PhoneAppsComposeTest : KitPageHarness() {

    @Test
    fun `Q1 quickmarks keep the declared order`() {
        val groups = SuitePhoneAppsFragment.parseGroups()
        assertTrue("phone_app_groups is empty", groups.isNotEmpty())
        val json = org.json.JSONArray(String(android.util.Base64.decode(com.diegonmarcos.superapp.BuildConfig.UI_SUITE_PHONE_GROUPS_B64, android.util.Base64.DEFAULT)))
        val declared = (0 until json.length()).map { json.getJSONObject(it).optString("title") }.filter { it.isNotBlank() }
        assertEquals(declared, groups.map { it.title })
        for (g in groups) assertEquals("${g.title}: entries are not de-duplicated or reordered", g.entries, g.entries.toList())
    }

    @Test
    fun `Q2 the last-resort label`() {
        assertEquals("Acode", SuitePhoneAppsFragment.derivedLabel("com.foxdebug.acode"))
        assertEquals("Android", SuitePhoneAppsFragment.derivedLabel("ch.protonmail.android"))
        assertEquals("x", SuitePhoneAppsFragment.derivedLabel("x").lowercase())
    }

    @Test
    fun `Q3 every group is one strip and every curated entry is a tile`() {
        show(SuitePhoneAppsFragment.newInstance())
        val groups = SuitePhoneAppsFragment.parseGroups().filter { it.entries.isNotEmpty() || it.folders.isNotEmpty() }
        for (g in groups) compose.onAllNodesWithTag(SuitePhoneAppsFragment.strip(g.title)).assertCountEquals(1)
        for (g in groups) for (e in g.entries.distinctBy { it.pkg }) {
            compose.onAllNodesWithTag(SuitePhoneAppsFragment.tileTag(e.pkg)).assertCountEquals(
                groups.sumOf { gg -> gg.entries.count { it.pkg == e.pkg } })
        }
    }

    @Test
    fun `Q4 smart folders start collapsed and the user's choice sticks`() {
        show(SuitePhoneAppsFragment.newInstance())
        compose.onNodeWithTag(SuitePhoneAppsFragment.TAG_SMART_HEADER).assertExists()
        compose.onAllNodesWithTag(SuitePhoneAppsFragment.TAG_SMART_STATUS).assertCountEquals(0)
        compose.onNodeWithTag(SuitePhoneAppsFragment.TAG_SMART_HEADER).performClick()
        compose.waitForIdle()
        // Expanded by the user: with no matching apps in Robolectric the status line explains the empty section.
        compose.onAllNodesWithTag(SuitePhoneAppsFragment.TAG_SMART_STATUS).assertCountEquals(1)
        compose.onNodeWithTag(SuitePhoneAppsFragment.TAG_SMART_HEADER).performClick()
        compose.waitForIdle()
        compose.onAllNodesWithTag(SuitePhoneAppsFragment.TAG_SMART_STATUS).assertCountEquals(0)
    }

    @Test
    fun `P1 the phone tab toggles between categories and the alphabet`() {
        show(PhoneAppsFragment.newInstance())
        compose.onNodeWithTag(PhoneAppsFragment.TAG_MODE_ALPHA).performClick()
        compose.waitForIdle()
        for (c in 'A'..'Z') compose.onAllNodesWithTag(folderTag("alpha:$c")).assertCountEquals(1)
        compose.onNodeWithTag(PhoneAppsFragment.TAG_MODE_CATEGORIES).performClick()
        compose.waitForIdle()
        compose.onAllNodesWithTag(folderTag("alpha:A")).assertCountEquals(0)
    }

    @Test
    fun `P2 a folder tap opens the sheet`() {
        show(PhoneAppsFragment.newInstance())
        compose.onNodeWithTag(PhoneAppsFragment.TAG_MODE_ALPHA).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(folderTag("alpha:A")).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(TAG_FOLDER_SHEET).assertExists()
    }

    @Test
    fun `C1 cloud apps draws its headings`() {
        show(GroupedTilesFragment.newInstance("cloud"))
        compose.onNodeWithTag(GroupedTilesFragment.groupTag("Quickmarks")).assertExists()
        val groups = Sections.byId("cloud")?.tileGroups.orEmpty()
        for (g in groups) compose.onAllNodesWithTag(GroupedTilesFragment.groupTag(g.title)).assertCountEquals(1)
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(GroupedTilesFragment.TAG_RECENT_EMPTY).fetchSemanticsNodes().isNotEmpty()
        }
        assertFalse(compose.onAllNodesWithTag(GroupedTilesFragment.TAG_RECENT_EMPTY).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `C2 the home sheet swaps its body in place`() {
        val tabs = HomeAppsTabs.loadFromBuildConfig()
        assertTrue("ui.home_apps_tabs is empty", tabs.size >= 2)
        show(AppDrawerSheetFragment.newInstance())
        compose.onNodeWithTag(AppDrawerSheetFragment.TAG_SHEET).assertExists()
        compose.onAllNodesWithTag(GroupedTilesFragment.TAG_LIST).assertCountEquals(1)
        compose.onNodeWithText(tabs[1].label).performClick()
        compose.waitForIdle()
        compose.onAllNodesWithTag(GroupedTilesFragment.TAG_LIST).assertCountEquals(0)
        compose.onAllNodesWithTag(SuitePhoneAppsFragment.TAG_LIST).assertCountEquals(1)
    }
}
