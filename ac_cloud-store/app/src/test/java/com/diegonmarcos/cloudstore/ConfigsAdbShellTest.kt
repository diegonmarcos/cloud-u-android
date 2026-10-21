package com.diegonmarcos.cloudstore

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.fragment.app.FragmentActivity
import com.diegonmarcos.superapp.adbdebug.AdbShellActivity
import com.diegonmarcos.superapp.adbdebug.AdbShellHost
import com.diegonmarcos.superapp.adbdebug.AdbShellLink
import com.diegonmarcos.superapp.adbdebug.HOSTED_TAG
import com.diegonmarcos.superapp.uikit.KitDenseMaterial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * #1080 Cloud Store's Wireless debugging button (Cloud tab, Access > Android Perms) opens Configs > ADB Shell INSIDE the
 * Configs frame, and that page is the lib's ONE page (the one SuperApp hosts too).
 *
 *   A1  Configs > ADB Shell draws the shared page, hosted bare under the Store's own dense theme, with this app's identity
 *   A2  a chip's open() goes to the host that has the page in its frame, and starts no full-screen activity
 *   A3  an app with no such page still gets the full-screen page (Termux, Nix-on-Droid)
 *   A4  MainActivity IS that host
 * Evidence: app/build/configs-screens/store-configs-adb-shell.png (CI uploads the folder).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w384dp-h2600dp-450dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConfigsAdbShellTest {
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
    fun `A1 Configs ADB Shell draws the shared page with this app's identity`() {
        compose.setContent { KitDenseMaterial(darkColorScheme()) { Surface(Modifier.fillMaxSize()) { ConfigsPage(ConfigsPages.ADB) } } }
        compose.waitForIdle()
        compose.onNodeWithTag(ConfigsPages.body(ConfigsPages.ADB)).assertExists()
        compose.onNodeWithTag(HOSTED_TAG, useUnmergedTree = true).assertExists()
        assertTrue("the page names this app's connection", compose.onAllNodesWithText("Connection name:", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        for (section in listOf("1  STATUS", "2  CONNECT", "3  ACTIVE CONNECTION", "7  SIDE BY SIDE"))
            assertTrue("section $section", compose.onAllNodesWithText(section, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        val out = File(System.getProperty("user.dir"), "build/configs-screens").apply { mkdirs() }
        val bmp = compose.runOnUiThread {
            val root = compose.activity.findViewById<View>(android.R.id.content)
            Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                .also { b -> Canvas(b).also { it.drawColor(android.graphics.Color.BLACK); root.draw(it) } }
        }
        File(out, "store-configs-adb-shell.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private class FrameHost(base: Context) : ContextWrapper(base), AdbShellHost {
        var opened = 0
        override fun openAdbShellInFrame(): Boolean { opened++; return true }
    }

    @Test
    fun `A2 a chip opens the page in the host's frame and starts no activity`() {
        val app = RuntimeEnvironment.getApplication()
        val host = FrameHost(app)
        AdbShellLink.open(host)
        assertEquals(1, host.opened)
        assertNull("no full-screen page was started", shadowOf(app).nextStartedActivity)
        // a themed wrapper between the chip and the host (a fragment's context) still reaches it
        AdbShellLink.open(ContextWrapper(host))
        assertEquals(2, host.opened)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun `A3 an app with no page in its frame gets the full-screen page`() {
        val app = RuntimeEnvironment.getApplication()
        AdbShellLink.open(app)
        val started = shadowOf(app).nextStartedActivity
        assertEquals(AdbShellActivity::class.java.name, started?.component?.className)
    }

    @Test
    fun `A4 MainActivity is the host`() {
        assertTrue(AdbShellHost::class.java.isAssignableFrom(MainActivity::class.java))
    }
}
