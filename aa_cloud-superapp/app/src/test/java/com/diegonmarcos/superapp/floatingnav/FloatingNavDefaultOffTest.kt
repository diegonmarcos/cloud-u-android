package com.diegonmarcos.superapp.floatingnav

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The floating button is OFF until the user turns it on.
 *
 * Reads the value baked from build.json::ui.floating_nav (default_on false,
 * enabled true) through the same [FloatingNavPrefs.enabled] the service and the
 * Configs switch read. The three cases are the three kinds of device: never
 * touched the switch, switched it on, switched it off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FloatingNavDefaultOffTest {

    private lateinit var ctx: Context

    @Before fun clean() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("floatingnav_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun `untouched device gets the declared default, off`() {
        assertFalse(FloatingNavPrefs.enabled(ctx))
    }

    @Test fun `the build still allows it, so the switch can turn it on`() {
        assertTrue(FloatingNavConfig.get().enabled)
    }

    @Test fun `a user who switched it on keeps it on`() {
        FloatingNavPrefs.setEnabled(ctx, true)
        assertTrue(FloatingNavPrefs.enabled(ctx))
    }

    @Test fun `a user who switched it off keeps it off`() {
        FloatingNavPrefs.setEnabled(ctx, false)
        assertFalse(FloatingNavPrefs.enabled(ctx))
    }
}
