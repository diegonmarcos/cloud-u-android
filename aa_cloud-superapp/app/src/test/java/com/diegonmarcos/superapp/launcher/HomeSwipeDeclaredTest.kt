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
 *   S1 swipe UP is Cloud > Apps, swipe DOWN is the Notify page, and a fresh install's prefs read exactly that
 *   S2 each declared `page:` target is a page the app really has (section + page declared)
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
        assertEquals("page:communication/my-rss", BuildConfig.HOME_SWIPE_DOWN)
        assertEquals("Notify", Sections.byId("communication")!!.label)
        val prefs = HomeSwipePrefs(RuntimeEnvironment.getApplication())
        assertEquals(BuildConfig.HOME_SWIPE_UP, prefs.up)
        assertEquals(BuildConfig.HOME_SWIPE_DOWN, prefs.down)
    }

    @Test fun `S2 the declared swipe targets are real pages`() {
        resolves(BuildConfig.HOME_SWIPE_UP)
        resolves(BuildConfig.HOME_SWIPE_DOWN)
    }
}
