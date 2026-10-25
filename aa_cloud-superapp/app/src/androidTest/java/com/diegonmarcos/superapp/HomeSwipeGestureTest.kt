package com.diegonmarcos.superapp

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.diegonmarcos.superapp.launcher.GroupedTilesFragment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Home screen's vertical swipes on a REAL emulator, through the real input pipeline. The destinations are DATA
 * (build.json onehand.home_swipes): swipe UP opens Cloud > Apps, swipe DOWN opens Notify. The test reads the
 * declared targets from BuildConfig, so editing build.json changes what it expects, never this file.
 *
 * Run: INSTRUMENT_CLASS=com.diegonmarcos.superapp.HomeSwipeGestureTest ./build.sh instrument
 */
@RunWith(AndroidJUnit4::class)
class HomeSwipeGestureTest {
    private val inst: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext

    /** startActivitySync waits for the main thread to go idle, which the animated Home never does: start it and poll. */
    private fun launchHome(): ShellActivity {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ctx.startActivity(i)
        var shell: ShellActivity? = null
        assertTrue("MainActivity never resumed", until(60_000) {
            shell = onMain {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull { it is ShellActivity } as ShellActivity?
            }
            shell != null
        })
        val sh = shell!!
        assertTrue("never reached the Home root", until(60_000) { onMain { sh.currentSection == "home" && sh.supportFragmentManager.backStackEntryCount == 0 } })
        SystemClock.sleep(1_500)
        return sh
    }

    private fun <T> onMain(f: () -> T): T {
        var out: Any? = null
        inst.runOnMainSync { out = f() }
        @Suppress("UNCHECKED_CAST") return out as T
    }

    private fun until(ms: Long, cond: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + ms
        while (SystemClock.uptimeMillis() < end) { if (cond()) return true; SystemClock.sleep(200) }
        return cond()
    }

    /** A quick vertical fling at column [xFrac] of the screen: [fromFrac] to [toFrac] of its height in 160 ms. */
    private fun fling(xFrac: Float, fromFrac: Float, toFrac: Float) {
        val dm = ctx.resources.displayMetrics
        val x = dm.widthPixels * xFrac
        val y0 = dm.heightPixels * fromFrac
        val y1 = dm.heightPixels * toFrac
        val ua = inst.uiAutomation
        val t0 = SystemClock.uptimeMillis()
        fun send(action: Int, y: Float, t: Long) {
            val e = MotionEvent.obtain(t0, t, action, x, y, 0)
            e.source = InputDevice.SOURCE_TOUCHSCREEN
            ua.injectInputEvent(e, true); e.recycle()
        }
        send(MotionEvent.ACTION_DOWN, y0, t0)
        val steps = 8
        for (s in 1..steps) {
            SystemClock.sleep(20)
            send(MotionEvent.ACTION_MOVE, y0 + (y1 - y0) * s / steps, SystemClock.uptimeMillis())
        }
        send(MotionEvent.ACTION_UP, y1, SystemClock.uptimeMillis())
    }

    /** Swipes in the first column that is not one of the Home stars (a gesture that starts on a star is the star's). */
    private fun swipeUntil(shell: ShellActivity, up: Boolean, arrived: () -> Boolean): Boolean {
        for (x in listOf(0.5f, 0.25f, 0.75f, 0.4f, 0.6f, 0.15f)) {
            if (up) fling(x, 0.78f, 0.38f) else fling(x, 0.38f, 0.78f)
            if (until(4_000, arrived)) return true
            if (!onMain { shell.currentSection == "home" }) return false
        }
        return false
    }

    private fun declared(target: String) = target.removePrefix("action:")

    @Test fun swipeUpOpensCloudApps() {
        assertEquals("page:cloud/apps", declared(BuildConfig.HOME_SWIPE_UP))
        val shell = launchHome()
        val ok = swipeUntil(shell, up = true) {
            onMain { shell.currentSection == "cloud" && shell.supportFragmentManager.findFragmentById(R.id.fragment_container) is GroupedTilesFragment }
        }
        assertTrue("swipe up did not open Cloud > Apps (section=${onMain { shell.currentSection }})", ok)
    }

    @Test fun swipeDownOpensNotify() {
        assertEquals("page:communication/my-rss", declared(BuildConfig.HOME_SWIPE_DOWN))
        val shell = launchHome()
        val ok = swipeUntil(shell, up = false) { onMain { shell.currentSection == "communication" } }
        assertTrue("swipe down did not open Notify (section=${onMain { shell.currentSection }})", ok)
    }
}
