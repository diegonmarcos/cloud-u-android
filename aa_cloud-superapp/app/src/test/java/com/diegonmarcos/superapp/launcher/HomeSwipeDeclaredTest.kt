package com.diegonmarcos.superapp.launcher

import android.app.Application
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.settings.HomeSwipePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Home-screen vertical swipes are DATA (build.json onehand.home_swipes -> BuildConfig.HOME_SWIPE_*):
 *   S1 swipe UP is Cloud > Apps, swipe DOWN on the left half is Notify, on the right half Quick Actions, and a fresh
 *      install's prefs read exactly that; left is the last Cloud app, right the last SuperApp page
 *   S2 each declared `page:` target is a page the app really has (section + page declared)
 *   S3 the declared shapes are a circle (toggle the light) and a C (open Cloud Calc)
 *   S4 the Configs > Launcher entry is called Quick Actions
 * The gestures themselves are driven on an emulator by androidTest HomeSwipeGestureTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HomeSwipeDeclaredTest {

    private fun resolves(target: String) {
        assertTrue("$target is not a page: target", target.startsWith("page:"))
        val (sec, page) = target.removePrefix("page:").split('/', limit = 2)
        val s = assertNotNull_(Sections.byId(sec), "section $sec")
        assertTrue("page $sec/$page is not declared", s.allPages.any { it.id == page })
    }

    private fun <T : Any> assertNotNull_(v: T?, what: String): T { assertNotNull("$what is not declared", v); return v!! }

    @Test fun `S1 up is Cloud Apps and down is Notify`() {
        assertEquals("page:cloud/apps", BuildConfig.HOME_SWIPE_UP)
        assertEquals("page:communication/my-rss", BuildConfig.HOME_SWIPE_DOWN_LEFT)
        assertEquals("page:config/controls", BuildConfig.HOME_SWIPE_DOWN_RIGHT)
        assertEquals("open_last_cloud_app", BuildConfig.HOME_SWIPE_LEFT)
        assertEquals("open_last_superapp_page", BuildConfig.HOME_SWIPE_RIGHT)
        assertEquals("Notify", Sections.byId("communication")!!.label)
        val prefs = HomeSwipePrefs(RuntimeEnvironment.getApplication())
        assertEquals(BuildConfig.HOME_SWIPE_UP, prefs.up)
        assertEquals(BuildConfig.HOME_SWIPE_DOWN_LEFT, prefs.downLeft)
        assertEquals(BuildConfig.HOME_SWIPE_DOWN_RIGHT, prefs.downRight)
        assertEquals(BuildConfig.HOME_SWIPE_LEFT, prefs.left)
        assertEquals(BuildConfig.HOME_SWIPE_RIGHT, prefs.right)
    }

    @Test fun `S2 the declared swipe targets are real pages`() {
        resolves(BuildConfig.HOME_SWIPE_UP)
        resolves(BuildConfig.HOME_SWIPE_DOWN_LEFT)
        resolves(BuildConfig.HOME_SWIPE_DOWN_RIGHT)
    }

    @Test fun `S3 the declared shapes are a circle and a C`() {
        val t = ShapeRecogniser.declared.templates.associate { it.id to it.action }
        assertEquals("toggle_light", t["circle"])
        assertEquals("open_cloud_calc", t["c"])
        for (a in t.values) assertTrue("$a is not in the Configs picker", HomeSwipePrefs.ACTIONS.any { it.first == a })
        for (a in listOf(BuildConfig.HOME_SWIPE_LEFT, BuildConfig.HOME_SWIPE_RIGHT))
            assertTrue("$a is not in the Configs picker", HomeSwipePrefs.ACTIONS.any { it.first == a })
    }

    @Test fun `S4 the Launcher entry is Quick Actions`() {
        assertEquals("Quick Actions", Sections.byId("config")!!.allPages.first { it.id == "controls" }.label)
    }

    @Test fun `S5 overrides of the retired vocabulary are dropped once and later edits stay`() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("home_swipe_prefs", android.content.Context.MODE_PRIVATE).edit().clear()
            .putString("swipe_up", "action:open_home_apps_phone").putString("swipe_down", "page:cloud/apps")
            .putString("swipe_left", "walk_step_next").putString("swipe_right", "walk_step_prev").commit()
        val prefs = HomeSwipePrefs(app)
        assertEquals(BuildConfig.HOME_SWIPE_UP, prefs.up)
        assertEquals(BuildConfig.HOME_SWIPE_DOWN_LEFT, prefs.downLeft)
        assertEquals(BuildConfig.HOME_SWIPE_LEFT, prefs.left)
        assertEquals(BuildConfig.HOME_SWIPE_RIGHT, prefs.right)
        prefs.up = "page:phone/apps"
        assertEquals("a deliberate edit survives the next start", "page:phone/apps", HomeSwipePrefs(app).up)
    }
}
