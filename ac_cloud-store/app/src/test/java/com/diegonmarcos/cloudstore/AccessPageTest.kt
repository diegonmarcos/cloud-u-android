package com.diegonmarcos.cloudstore

import android.app.Application
import android.content.ComponentName
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.fragment.app.FragmentActivity
import com.diegonmarcos.superapp.appstore.FleetIdentity
import com.diegonmarcos.superapp.uikit.KitDenseMaterial
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * #1067 Access > Identity crashed the Store: FleetIdentityPage drew kit parts outside CloudKitTheme
 * (LocalKitPalette has no default). The page is opened here exactly as MainActivity hosts it - a bare
 * KitDenseMaterial + Surface, no CloudKitTheme - through AccessPage(PAGE_IDENTITY). Nothing is
 * installed in Robolectric, so every fleet row also proves "not installed" is a row state, not a throw.
 *
 * #1096 the page could not be scrolled: it was a plain Column and only the SuperApp's fragment host wrapped it in a
 * scroll, so in the Store every row below the fold was out of reach. The page now owns its scroll.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AccessPageTest {
    private val compose = createAndroidComposeRule<FragmentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, FragmentActivity::class.java))
                base.evaluate()
            }
        }
    }).around(compose)

    @Test
    fun `Access Identity opens in the Store shell and draws a row per fleet app`() {
        compose.setContent { KitDenseMaterial(darkColorScheme()) { Surface(Modifier.fillMaxSize()) { AccessPage(PAGE_IDENTITY) } } }
        compose.waitForIdle()
        Thread.sleep(300) // the live probe thread: it must end without killing the process
        compose.waitForIdle()
        compose.onNodeWithTag(FleetIdentity.TAG_SUMMARY, useUnmergedTree = true).assertExists()
        val reg = FleetIdentity.baked()
        for (r in FleetIdentity.rows(reg, emptyMap())) compose.onNodeWithTag("kit:listrow:" + r.tag, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `Access Identity scrolls to its last row inside a bounded pane`() {
        compose.setContent { KitDenseMaterial(darkColorScheme()) { Surface(Modifier.height(300.dp)) { AccessPage(PAGE_IDENTITY) } } }
        compose.waitForIdle()
        val last = FleetIdentity.sorted(FleetIdentity.rows(FleetIdentity.baked(), emptyMap()), FleetIdentity.Sort.UID).last()
        val row = compose.onNodeWithTag("kit:listrow:" + last.tag, useUnmergedTree = true)
        row.assertIsNotDisplayed()   // the page is taller than its pane, else this proves nothing
        row.performScrollTo()        // fails when no enclosing scroll container exists (the #1096 bug)
        row.assertIsDisplayed()
    }
}
