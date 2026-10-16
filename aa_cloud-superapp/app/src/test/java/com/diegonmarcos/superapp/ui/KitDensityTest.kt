package com.diegonmarcos.superapp.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.uikit.KitDensity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The density contract of CloudKitTheme, MEASURED (no screenshots). After the Compose conversions every screen
 * swelled because MaterialTheme hands each Text a fixed 24.sp line (bodyLarge) whatever fontSize the screen sets,
 * and Material's clickable parts claim 48dp. mdpi, so one px is one dp and one sp.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h640dp-mdpi")
class KitDensityTest : KitPageHarness() {

    @Test fun `a dense Text is as tall as its font, not Material's fixed 24sp line`() {
        showKit {
            Column {
                Text("dense9", fontSize = 9.sp, modifier = Modifier.testTag("t9"))
                Text("dense13", fontSize = 13.sp, modifier = Modifier.testTag("t13"))
            }
        }
        val h9 = compose.onNodeWithTag("t9").getUnclippedBoundsInRoot().height.value
        val h13 = compose.onNodeWithTag("t13").getUnclippedBoundsInRoot().height.value
        assertTrue("9sp text is $h9 tall: it inherited a fixed line height", h9 <= 16f)
        assertTrue("13sp text is $h13 tall: it inherited a fixed line height", h13 <= 20f)
    }

    @Test fun `Material touch targets honour the kit floor, not 48dp`() {
        showKit {
            IconButton(onClick = {}, modifier = Modifier.testTag("ib")) { Icon(Icons.Filled.Settings, null) }
        }
        val b = compose.onNodeWithTag("ib").getUnclippedBoundsInRoot()
        assertTrue("IconButton is ${b.height.value}dp tall", b.height.value <= 40f)
        assertTrue("the kit touch floor must stay at or under 40dp", KitDensity.touch.value <= 40f)
    }
}
