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
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Home screen's vertical swipes on a REAL emulator, through the real input pipeline. The destinations are DATA
 * (build.json onehand.home_swipes): swipe UP opens Cloud > Apps, swipe DOWN on the left half Notify, on the right half
 * Quick Actions, swipe LEFT the last Cloud app, swipe RIGHT the last SuperApp page; a drawn circle toggles the light and a drawn C
 * opens Cloud Calc (onehand.shape_gestures). The test reads the declared targets from BuildConfig, so editing build.json changes what it expects, never this file.
 *
 * Run: INSTRUMENT_CLASS=com.diegonmarcos.superapp.HomeSwipeGestureTest ./build.sh instrument
 */
@RunWith(AndroidJUnit4::class)
class HomeSwipeGestureTest {
    private val inst: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext

    /** The screensaver (idle 10 s) is a full-screen focusable overlay of this app: on a slow emulator it covers Home before
     *  the first swipe and takes every touch (run 40245015198: the focused window was the app's own overlay, not the
     *  activity). A swipe test needs the Home screen in front, so the idle timeout is off for the test and restored after. */
    private var savedScreensaver = 0
    private val savedSkips = mutableMapOf<String, Int>()
    @Before fun screensaverOff() {
        val prefs = com.diegonmarcos.superapp.settings.LauncherSettingsPrefs(ctx)
        savedScreensaver = prefs.screensaverTimeout; prefs.screensaverTimeout = 0
        // The first-run permission ask is a modal dialog over Home (non-dismissable): it takes every touch of the first
        // launch, so its skip ledger is set to "asked enough" for the test and restored after.
        val ask = com.diegonmarcos.superapp.system.PermAskTracker(ctx)
        com.diegonmarcos.superapp.system.FirstRunPerms.ORDER.forEach {
            savedSkips[it.name] = ask.skips(it.name); ask.setSkips(it.name, com.diegonmarcos.superapp.system.FirstRunPerms.MAX_SKIPS)
        }
    }
    /** Runs a shell command and waits for it to finish (reading its output), unlike a closed stream which may cut it short. */
    private fun shell(cmd: String): String = runCatching {
        java.io.BufferedReader(java.io.InputStreamReader(
            android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(cmd)))).readText()
    }.getOrDefault("")

    private fun closeInstaller() {
        repeat(6) {
            shell("am force-stop com.google.android.packageinstaller")
            shell("am force-stop com.android.packageinstaller")
            if (!shell("dumpsys window").contains("mCurrentFocus=Window{") || !shell("dumpsys window").lineSequence().any { it.contains("mCurrentFocus") && it.contains("packageinstaller") }) return
            SystemClock.sleep(500)
        }
    }

    @After fun screensaverRestore() {
        // Drawing a C opens the install offer for Cloud Calc (it is not on the emulator): close the system installer so it can not
        // take the touches of the next test.
        closeInstaller()
        com.diegonmarcos.superapp.settings.LauncherSettingsPrefs(ctx).screensaverTimeout = savedScreensaver
        val ask = com.diegonmarcos.superapp.system.PermAskTracker(ctx)
        savedSkips.forEach { (k, v) -> ask.setSkips(k, v) }
    }

    /** startActivitySync waits for the main thread to go idle, which the animated Home never does: start it and poll. */
    private fun launchHome(): ShellActivity {
        closeInstaller()
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

    /** Injects one touch stroke through the real input pipeline: [pts] in screen px, 12 ms apart, starting at NOW. */
    private fun stroke(pts: List<Pair<Float, Float>>, stepMs: Long = 12) {
        val ua = inst.uiAutomation
        // Event times start at NOW and run apart: the detector arms its long-press at downTime + 500 ms, and a down time in
        // the past (the earlier "synthetic" trick) let that fire before the release arrived, which cancels the fling.
        val t0 = SystemClock.uptimeMillis()
        fun send(action: Int, p: Pair<Float, Float>, t: Long) {
            val e = MotionEvent.obtain(t0, t, action, p.first, p.second, 0)
            e.source = InputDevice.SOURCE_TOUCHSCREEN
            ua.injectInputEvent(e, true); e.recycle()
        }
        send(MotionEvent.ACTION_DOWN, pts.first(), t0)
        for (s in 1 until pts.size - 1) send(MotionEvent.ACTION_MOVE, pts[s], t0 + stepMs * s)
        send(MotionEvent.ACTION_UP, pts.last(), t0 + stepMs * (pts.size - 1))
    }

    private val dm get() = ctx.resources.displayMetrics

    /** A quick vertical fling at column [xFrac] of the screen: [fromFrac] to [toFrac] of its height in 8 steps. */
    private fun fling(xFrac: Float, fromFrac: Float, toFrac: Float) {
        val x = dm.widthPixels * xFrac
        val y0 = dm.heightPixels * fromFrac; val y1 = dm.heightPixels * toFrac
        stroke((0..9).map { x to y0 + (y1 - y0) * it / 9 }, 10)
    }

    /** A quick horizontal fling at row [yFrac]: [fromFrac] to [toFrac] of the width. */
    private fun hfling(yFrac: Float, fromFrac: Float, toFrac: Float) {
        val y = dm.heightPixels * yFrac
        val x0 = dm.widthPixels * fromFrac; val x1 = dm.widthPixels * toFrac
        stroke((0..9).map { x0 + (x1 - x0) * it / 9 to y }, 10)
    }

    /** An arc of radius [rFrac] x width around (cxFrac, cyFrac): from [a0] to [a1] degrees, screen coordinates (y down). */
    private fun arcStroke(cxFrac: Float, cyFrac: Float, rFrac: Float, a0: Double, a1: Double) {
        val cx = dm.widthPixels * cxFrac; val cy = dm.heightPixels * cyFrac; val r = dm.widthPixels * rFrac
        stroke((0..28).map { i ->
            val a = Math.toRadians(a0 + (a1 - a0) * i / 28)
            (cx + r * Math.cos(a)).toFloat() to (cy - r * Math.sin(a)).toFloat()
        })
    }

    /** Tries [attempt] at the given [spots] (the first place that is not a Home star takes the gesture) until [arrived]. */
    private fun <T> tryUntil(shell: ShellActivity, spots: List<T>, attempt: (T) -> Unit, arrived: () -> Boolean): Boolean {
        for (s in spots) {
            attempt(s)
            if (until(4_000, arrived)) return true
            if (!onMain { shell.currentSection == "home" }) return false
        }
        return false
    }

    private val leftXs = listOf(0.25f, 0.4f, 0.15f, 0.35f)
    private val rightXs = listOf(0.75f, 0.6f, 0.85f, 0.65f)

    /** Evidence for a red run: the focused window, the top fragment, and a screenshot pulled by the workflow. */
    private fun evidence(shell: ShellActivity, name: String): String {
        val ua = inst.uiAutomation
        runCatching {
            java.io.FileOutputStream("/data/local/tmp/home-swipe-$name.png").use { ua.takeScreenshot()?.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) }
        }
        val pfd = ua.executeShellCommand("dumpsys window")
        val focus = java.io.BufferedReader(java.io.InputStreamReader(android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd)))
            .lineSequence().filter { it.contains("mCurrentFocus") }.joinToString(" | ")
        // The input dispatcher's own window list, in z-order: which window owns the touch at the swipe point.
        fun sh(cmd: String) = java.io.BufferedReader(java.io.InputStreamReader(
            android.os.ParcelFileDescriptor.AutoCloseInputStream(ua.executeShellCommand(cmd)))).readText()
        val input = runCatching { sh("dumpsys input") }.getOrDefault("")
        runCatching { java.io.File("/data/local/tmp/home-swipe-$name-input.txt").writeText(input) }
        val wins = input.lineSequence().filter { Regex("^\\s+\\d+: name=").containsMatchIn(it) }.take(14).joinToString(" ; ") { it.trim().take(150) }
        val top = onMain { shell.supportFragmentManager.findFragmentById(R.id.fragment_container)?.javaClass?.simpleName }
        return "section=${onMain { shell.currentSection }} top=$top focus=[$focus] windows=[$wins]"
    }

    private fun declared(target: String) = target.removePrefix("action:")
    private fun lastAction(shell: ShellActivity) = onMain { shell.lastHomeAction }

    private fun onCloudApps(shell: ShellActivity) = onMain {
        val top = shell.supportFragmentManager.findFragmentById(R.id.fragment_container)
        shell.currentSection == "cloud" &&
            (top is GroupedTilesFragment || top?.childFragmentManager?.fragments?.any { it is GroupedTilesFragment && it.isVisible } == true)
    }

    @Test fun swipeUpOpensCloudApps() {
        assertEquals("page:cloud/apps", declared(BuildConfig.HOME_SWIPE_UP))
        val shell = launchHome()
        val ok = tryUntil(shell, listOf(0.5f, 0.25f, 0.75f, 0.4f, 0.6f, 0.15f), { fling(it, 0.78f, 0.38f) }) { onCloudApps(shell) }
        assertTrue("swipe up did not open Cloud > Apps: ${if (ok) "" else evidence(shell, "up")}", ok)
    }

    private fun storeLegacyOverrides() {
        ctx.getSharedPreferences("home_swipe_prefs", android.content.Context.MODE_PRIVATE).edit().clear()
            .remove("schema").putString("swipe_up", "action:open_home_apps_phone").putString("swipe_down", "page:cloud/apps").commit()
    }

    /** A phone that once opened the old picker holds retired overrides (up = Phone > Apps, down = Cloud > Apps): the declared gestures must win. */
    @Test fun legacyStoredOverridesDoNotBeatTheDeclaredSwipeDown() {
        storeLegacyOverrides()
        val shell = launchHome()
        val ok = tryUntil(shell, listOf(0.25f, 0.4f, 0.15f, 0.35f), { fling(it, 0.38f, 0.78f) }) { onMain { shell.currentSection == "communication" } }
        assertTrue("with a legacy override stored, swipe down on the left did not open Notify: ${if (ok) "" else evidence(shell, "legacy-down")}", ok)
        assertEquals("page:communication/my-rss", lastAction(shell))
    }

    @Test fun legacyStoredOverridesDoNotBeatTheDeclaredSwipeUp() {
        storeLegacyOverrides()
        val shell = launchHome()
        val up = tryUntil(shell, listOf(0.5f, 0.25f, 0.75f, 0.4f), { fling(it, 0.78f, 0.38f) }) { onCloudApps(shell) }
        assertTrue("with a legacy override stored, swipe up did not open Cloud > Apps: ${if (up) "" else evidence(shell, "legacy-up")}", up)
        assertEquals("page:cloud/apps", lastAction(shell))
    }

    @Test fun swipeDownLeftHalfOpensNotify() {
        assertEquals("page:communication/my-rss", declared(BuildConfig.HOME_SWIPE_DOWN_LEFT))
        val shell = launchHome()
        val ok = tryUntil(shell, leftXs, { fling(it, 0.38f, 0.78f) }) { onMain { shell.currentSection == "communication" } }
        assertTrue("swipe down on the left half did not open Notify: ${if (ok) "" else evidence(shell, "down-left")}", ok)
    }

    @Test fun swipeDownRightHalfOpensQuickActions() {
        assertEquals("page:config/controls", declared(BuildConfig.HOME_SWIPE_DOWN_RIGHT))
        val shell = launchHome()
        val ok = tryUntil(shell, rightXs, { fling(it, 0.38f, 0.78f) }) { onMain { shell.currentSection == "config" } }
        assertTrue("swipe down on the right half did not open Quick Actions: ${if (ok) "" else evidence(shell, "down-right")}", ok)
    }

    @Test fun swipeLeftOpensLastCloudApp() {
        assertEquals("open_last_cloud_app", declared(BuildConfig.HOME_SWIPE_LEFT))
        val shell = launchHome()
        // A fleet app launched earlier; it is not installed on the emulator, so the action falls back to Cloud > Apps.
        val pkg = com.diegonmarcos.superapp.launcher.Sections.externalApps().first { it.hubPackage.isNotBlank() }.hubPackage
        com.diegonmarcos.superapp.apptabs.AppTabPrefs(ctx).recordExternalApp(pkg, "Fleet app")
        val ok = tryUntil(shell, listOf(0.4f, 0.55f, 0.3f), { hfling(it, 0.8f, 0.25f) }) { lastAction(shell) == "open_last_cloud_app" }
        assertTrue("swipe left did not run open_last_cloud_app: ${if (ok) "" else evidence(shell, "left")}", ok)
        assertTrue("fallback is Cloud > Apps", until(8_000) { onCloudApps(shell) })
    }

    @Test fun swipeRightOpensLastSuperAppPage() {
        assertEquals("open_last_superapp_page", declared(BuildConfig.HOME_SWIPE_RIGHT))
        val shell = launchHome()
        com.diegonmarcos.superapp.apptabs.AppTabPrefs(ctx).recordPage("communication", "my-rss", "Notify", "ic_rss")
        val ok = tryUntil(shell, listOf(0.4f, 0.55f, 0.3f), { hfling(it, 0.25f, 0.8f) }) { onMain { shell.currentSection == "communication" } }
        assertTrue("swipe right did not open the last SuperApp page: ${if (ok) "" else evidence(shell, "right")}", ok)
    }

    @Test fun drawingACircleTogglesTheLight() {
        val declaredAction = com.diegonmarcos.superapp.launcher.ShapeRecogniser.declared.templates.first { it.id == "circle" }.action
        assertEquals("toggle_light", declaredAction)
        val shell = launchHome()
        val spots = listOf(0.5f to 0.5f, 0.35f to 0.55f, 0.65f to 0.55f, 0.5f to 0.4f)
        val ok = tryUntil(shell, spots, { arcStroke(it.first, it.second, 0.2f, 0.0, 360.0) }) { lastAction(shell) == "toggle_light" }
        assertTrue("a drawn circle did not toggle the light: ${if (ok) "" else evidence(shell, "circle")}", ok)
        // The light engine was driven: the torch flipped, or a flash-less emulator reported why it could not.
        val st = com.diegonmarcos.superapp.light.Light.engine(ctx).state
        assertTrue("the light engine was not touched", st.rearOn || st.error != null || st.frontOn)
        onMain { com.diegonmarcos.superapp.light.Light.engine(ctx).offAll() }
    }

    @Test fun drawingACOpensCloudCalc() {
        val declaredAction = com.diegonmarcos.superapp.launcher.ShapeRecogniser.declared.templates.first { it.id == "c" }.action
        assertEquals("open_cloud_calc", declaredAction)
        val shell = launchHome()
        val spots = listOf(0.5f to 0.5f, 0.4f to 0.55f, 0.6f to 0.55f, 0.5f to 0.4f)
        val ok = tryUntil(shell, spots, { arcStroke(it.first, it.second, 0.15f, 45.0, 315.0) }) { lastAction(shell) == "open_cloud_calc" }
        assertTrue("a drawn C did not open Cloud Calc: ${if (ok) "" else evidence(shell, "c")}", ok)
        // Cloud Calc is not on the emulator, so the tile offers to install it and the system installer pops up a little later:
        // wait for it, then close it, so it can not take the touches of the next test.
        until(30_000) { shell("dumpsys window").lineSequence().any { it.contains("mCurrentFocus") && it.contains("packageinstaller") } }
        closeInstaller()
    }

    @Test fun aLongStraightStrokeStaysASwipeNotAShape() {
        val shell = launchHome()
        val ok = tryUntil(shell, listOf(0.5f, 0.25f, 0.75f), { fling(it, 0.78f, 0.38f) }) { onCloudApps(shell) }
        assertTrue("swipe up did not open Cloud > Apps", ok)
        assertTrue("a swipe must not run a shape action", lastAction(shell) == "page:cloud/apps")
    }

    /** The Boards strip (the top fragment) and what it shows in its pane. */
    private fun boardsStrip(shell: ShellActivity) =
        shell.supportFragmentManager.findFragmentById(R.id.fragment_container) as? com.diegonmarcos.superapp.launcher.SectionTabsFragment

    private fun paneFragments(shell: ShellActivity) = onMain { boardsStrip(shell)?.childFragmentManager?.fragments?.map { it.javaClass.simpleName }.orEmpty() }

    @Test fun boardsResourcesPageShowsTheLivePhone() {
        val shell = launchHome()
        onMain { shell.dispatchTarget("page:boards/resources") }
        assertTrue("Boards did not open", until(20_000) { onMain { shell.currentSection == "boards" } })
        assertTrue("the Resources tab was not selected: ${paneFragments(shell)} ${evidence(shell, "resources")}",
            until(20_000) { onMain { boardsStrip(shell)?.selectedPage == "resources" } })
        // The page is the aggregator stack of the declared board_resources cards, and every section it declares answers on this device.
        assertTrue("Resources did not render its stack: ${paneFragments(shell)}", until(20_000) { "AggregatorStackFragment" in paneFragments(shell) })
        for (s in com.diegonmarcos.superapp.boards.ResourcesProvider.SECTIONS.filter { it != "network" && it != "sensors" }) {
            assertTrue("$s read nothing on a device", com.diegonmarcos.superapp.boards.ResourcesProvider.read(ctx, s).isNotEmpty())
        }
    }

    @Test fun boardsQuickActionsIsTheConfigsPage() {
        val shell = launchHome()
        onMain { shell.dispatchTarget("page:boards/quick-actions") }
        assertTrue("Boards did not open", until(20_000) { onMain { shell.currentSection == "boards" } })
        assertTrue("the Quick Actions tab was not selected: ${paneFragments(shell)} ${evidence(shell, "quick-actions")}",
            until(20_000) { onMain { boardsStrip(shell)?.selectedPage == "quick-actions" } })
        // Mirrored page: the pane holds the very strip Configs > Launcher > Quick Actions opens (a SectionTabsFragment over Presets | Panels).
        assertTrue("Quick Actions did not host the Configs strip: ${paneFragments(shell)}", until(20_000) { "SectionTabsFragment" in paneFragments(shell) })
    }
}
