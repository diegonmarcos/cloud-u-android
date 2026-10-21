package com.diegonmarcos.cloudstore

import android.app.Application
import android.content.ComponentName
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
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
}
