package com.diegonmarcos.superapp.bottomnav

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * AppTopBarScaffold is the host's only layout owner under the status bar: the page it hosts starts exactly one bar
 * height (56dp) below the scaffold's top and fills the rest edge to edge. No second inset or content padding of
 * its own (the "huge padding" after the conversions came from stacked paddings, so any added one fails here).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InsetOwnershipTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `the page starts exactly one bar below the top and fills the rest`() {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AppTopBarScaffold(
                    decl = TopBarDecl(drawer = true, ai = false, settings = emptyList()),
                    title = "App", onAccount = {}, onConfigs = {},
                ) { Box(Modifier.fillMaxSize().testTag("page")) }
            }
        }
        val bar = compose.onNodeWithTag(TopBarTags.BAR).getUnclippedBoundsInRoot()
        val page = compose.onNodeWithTag("page").getUnclippedBoundsInRoot()
        assertEquals("bar height", 56f, bar.height.value, 0.5f)
        assertEquals("page top == bar bottom, no extra padding", bar.bottom.value, page.top.value, 0.5f)
        assertEquals("page left", 0f, page.left.value, 0.5f)
        assertEquals("page width", 360f, page.width.value, 0.5f)
    }
}
