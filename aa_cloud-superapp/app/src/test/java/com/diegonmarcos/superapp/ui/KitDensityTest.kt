package com.diegonmarcos.superapp.ui

import android.app.Application
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.isUnspecified
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.uikit.KitDensity
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.testTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The density contract of CloudKitTheme, read from what a screen really INHERITS (font metrics under Robolectric
 * are not the device's, so heights would prove nothing). After the Compose conversions every screen swelled
 * because MaterialTheme hands each Text a fixed 24.sp line (bodyLarge) whatever fontSize the screen sets, and
 * Material's clickable parts claim 48dp.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h640dp-mdpi")
class KitDensityTest : KitPageHarness() {

    @Test fun `a dense Text inherits no fixed line height and no tracking`() {
        var layout: TextLayoutResult? = null
        showKit { Text("dense9", fontSize = 9.sp, onTextLayout = { layout = it }) }
        val style = layout!!.layoutInput.style
        assertEquals(9f, style.fontSize.value, 0.01f)
        assertTrue("the Text inherited a fixed line height of ${style.lineHeight}", style.lineHeight.isUnspecified)
        assertTrue("the Text inherited letter spacing ${style.letterSpacing}", style.letterSpacing.isUnspecified)
    }

    @Test fun `Material's minimum interactive size is OFF under the kit, not 48dp`() {
        var floor: Dp = Dp.Unspecified
        showKit { floor = LocalMinimumInteractiveComponentSize.current }
        assertEquals(KitDensity.interactiveFloor.value, floor.value, 0.01f)
        assertEquals("the dense theme must switch the 48dp padding off", 0f, floor.value, 0.01f)
    }

    @Test fun `a kit button is 28 to 32dp tall, not Material's 40`() {
        showKit { com.diegonmarcos.superapp.uikit.KitButton(onClick = {}, modifier = androidx.compose.ui.Modifier.testTag("b")) { Text("Save") } }
        val h = compose.onNodeWithTag("b").fetchSemanticsNode().boundsInRoot.height / compose.density.density
        assertTrue("KitButton is $h dp tall", h in 27.5f..32.5f)
    }
}
