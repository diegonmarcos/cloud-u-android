package com.diegonmarcos.superapp.cloud

import android.app.Application
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.diegonmarcos.superapp.bottomnav.PageTabsTags
import com.diegonmarcos.superapp.launcher.Sections
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #773 — the container sheet (a C3 tile's tap) in Compose, was a BottomSheetDialog of hand-built Views.
 *
 *   K1 it opens on Infos: the Container and App cards, declared values only
 *   K2 the Actions tab draws Open / Copy and, for a container with a VM, the four container verbs
 *   K3 Open hands the URL to the activity's tile listener and closes the sheet
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ContainerSheetComposeTest : KitPageHarness() {

    private fun anyContainer() = Sections.publicServices().first { it.vm.isNotBlank() && it.publicUrl.isNotBlank() }

    @Test
    fun `K1 Infos draws the Container and App cards`() {
        val c = anyContainer()
        showKit { ContainerSheet.Sheet(c.name, c.name, "", {}) }
        compose.onNodeWithTag("container:pane:infos").assertExists()
        compose.onNodeWithText("Container").assertExists()
        compose.onNodeWithText("App").assertExists()
        compose.onNodeWithText(c.name, substring = true).assertExists()
    }

    @Test
    fun `K2 Actions offers the four container verbs for a container with a VM`() {
        val c = anyContainer()
        showKit { ContainerSheet.Sheet(c.name, c.name, "", {}) }
        compose.onNodeWithTag(PageTabsTags.tab("actions")).performClick()
        for (verb in listOf("Start", "Stop", "Restart", "Update")) compose.onNodeWithTag("container:action:$verb").assertExists()
        compose.onNodeWithTag("container:action:Open").assertExists()
    }

    @Test
    fun `K3 Open closes the sheet`() {
        val c = anyContainer()
        var closed = 0
        showKit { ContainerSheet.Sheet(c.name, c.name, "", { closed++ }) }
        compose.onNodeWithTag(PageTabsTags.tab("actions")).performClick()
        compose.onNodeWithTag("container:action:Open").performClick()
        assertEquals(1, closed)
    }
}
