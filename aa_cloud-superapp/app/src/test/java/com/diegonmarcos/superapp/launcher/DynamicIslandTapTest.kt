package com.diegonmarcos.superapp.launcher

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.ui.DynamicIsland
import com.diegonmarcos.superapp.ui.KitPageHarness
import com.diegonmarcos.superapp.ui.ShellFxState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The second toolbar pill (the Dynamic Island), which opens the terminal. It broke after the Compose port in two ways, and
 * both are pinned here:
 *   D1 a tap on the pill reaches its handler (the pill owns its click; it no longer depends on layers letting it through)
 *   D2 the declared action (`ui.dynamic_island_action`, today extapp:cloud-myterminal) starts the terminal's launch intent
 *   D3 the toolbar island is seated BELOW the status strip at the strip's real height, and follows it when it grows
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DynamicIslandTapTest : KitPageHarness() {

    @Test fun `D1 a tap on the pill fires its handler`() {
        ShellFxState.frozen = true
        var taps = 0
        showKit { Box(Modifier.size(120.dp, 22.dp)) { DynamicIsland(onTap = { taps++ }) } }
        compose.onNodeWithTag("dynamicisland").assertIsDisplayed().performClick()
        assertEquals(1, taps)
        ShellFxState.frozen = false
    }

    @Test fun `D2 the declared action starts the terminal`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val action = BuildConfig.UI_DYNAMIC_ISLAND_ACTION
        assertEquals("extapp", action.substringBefore(":"))
        val ext = Sections.externalApp(action.substringAfter(":"))
        assertNotNull("the declared island target is not an external app", ext)
        val pkg = ext!!.hubPackage
        // Install the terminal's launcher activity into Robolectric's package manager.
        val info = ActivityInfo().apply {
            packageName = pkg; name = "$pkg.Main"
            applicationInfo = ApplicationInfo().also { it.packageName = pkg }
        }
        val pm = shadowOf(app.packageManager)
        for (cat in listOf(Intent.CATEGORY_INFO, Intent.CATEGORY_LAUNCHER)) {
            pm.addResolveInfoForIntent(
                Intent(Intent.ACTION_MAIN).addCategory(cat).setPackage(pkg),
                ResolveInfo().apply { activityInfo = info },
            )
        }
        ShellFxState.frozen = true
        // The same wiring ShellActivity gives the pill.
        showKit {
            Box(Modifier.size(120.dp, 22.dp)) {
                DynamicIsland(onTap = {
                    DynamicIslandAction.run(
                        app,
                        launchExternalApp = { id -> ExternalAppLaunch.launchInstalled(app, Sections.externalApp(id)!!, null, null) },
                        shortcut = {},
                    )
                })
            }
        }
        compose.onNodeWithTag("dynamicisland").performClick()
        val started = shadowOf(app).nextStartedActivity
        assertNotNull("tapping the island started nothing", started)
        assertEquals(pkg, started.component?.packageName ?: started.`package`)
        ShellFxState.frozen = false
    }

    @Test fun `D3 the toolbar island follows the strip's height`() {
        val parent = FrameLayout(compose.activity)
        val strip = View(compose.activity)
        val island = View(compose.activity)
        parent.addView(strip, FrameLayout.LayoutParams(100, ViewGroup.LayoutParams.WRAP_CONTENT))
        parent.addView(island, FrameLayout.LayoutParams(100, 20))
        StripIslandGap.follow(strip, island, 12)
        // First layout: an empty composition's height.
        strip.layout(0, 0, 100, 30)
        assertEquals(42, (island.layoutParams as ViewGroup.MarginLayoutParams).topMargin)
        // The strip's readings arrive and it grows: the island has to move down with it, or the strip covers it.
        strip.layout(0, 0, 100, 90)
        assertEquals("island stayed under the grown strip", 102, (island.layoutParams as ViewGroup.MarginLayoutParams).topMargin)
        // Not laid out yet asks for nothing.
        assertEquals(null, StripIslandGap.marginFor(0, 12))
    }
}
