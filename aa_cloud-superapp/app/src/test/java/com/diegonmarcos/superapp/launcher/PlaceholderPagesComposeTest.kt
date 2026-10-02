package com.diegonmarcos.superapp.launcher

import android.app.Application
import androidx.compose.ui.test.onNodeWithText
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #773 — the three placeholder pages, Compose since their XML layouts were deleted. Each still
 * shows what its arguments name.
 *
 *   S1 a section placeholder shows its label and names the libs:<id> it stands in for
 *   S2 the drawer placeholder shows the label it was created with
 *   S3 the tablet detail pane prompts toward the master pane
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlaceholderPagesComposeTest : KitPageHarness() {

    @Test
    fun `S1 a section placeholder shows its label and the lib it stands in for`() {
        show(SectionFragment.forSection("feed", "Feeds"))
        compose.onNodeWithText("Feeds").assertExists()
        compose.onNodeWithText("libs:feed", substring = true).assertExists()
    }

    @Test
    fun `S2 the drawer placeholder shows the label it was created with`() {
        show(PlaceholderDrawerFragment.newInstance("Calendar"))
        compose.onNodeWithText("Calendar").assertExists()
        compose.onNodeWithText("No section-specific drawer yet", substring = true).assertExists()
    }

    @Test
    fun `S3 the detail pane prompts toward the master pane`() {
        show(DetailPlaceholderFragment.newInstance())
        compose.onNodeWithText("Select an item").assertExists()
    }
}
