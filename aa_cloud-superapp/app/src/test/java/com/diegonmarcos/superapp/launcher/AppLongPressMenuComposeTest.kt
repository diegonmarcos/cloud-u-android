package com.diegonmarcos.superapp.launcher

import android.app.Application
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #773 — the app long-press menu in Compose (was a Dialog of hand-built Views).
 *
 *   M1 it draws the Configs section: App info, Uninstall, Update
 *   M2 the URLs section is always drawn - links, or the reason there are none (#677)
 *   M3 tapping a row closes the menu
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppLongPressMenuComposeTest : KitPageHarness() {

    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `M1 the Configs rows are drawn`() {
        showKit { AppLongPressMenu.Menu(ctx, ctx.packageName) {} }
        compose.onNodeWithTag("longpress:menu").assertExists()
        for (row in listOf("App info", "Uninstall", "Update")) compose.onNodeWithTag("longpress:row:$row").assertExists()
    }

    @Test
    fun `M2 the URLs section is never silent`() {
        val sections = AppLongPressMenu.sectionsFor(ctx, ctx.packageName, "App") {}
        assertEquals(listOf("Configs", "URLs"), sections.filter { it.title != "Actions" }.map { it.title })
        assertTrue(sections.last().rows.isNotEmpty())
    }

    @Test
    fun `M3 a row closes the menu`() {
        var closed = 0
        showKit { AppLongPressMenu.Menu(ctx, ctx.packageName) { closed++ } }
        compose.onNodeWithText("App info").performClick()
        assertEquals(1, closed)
    }
}
