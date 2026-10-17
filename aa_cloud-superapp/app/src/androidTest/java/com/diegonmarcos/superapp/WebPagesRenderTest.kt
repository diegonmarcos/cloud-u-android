package com.diegonmarcos.superapp

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.diegonmarcos.superapp.launcher.WebPageFragment
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Cloud ▸ WebApps and Cloud ▸ Linktree on a REAL emulator WebView (Robolectric cannot render one). Opens each page through
 * the same route a tile uses, waits up to 20 s, and writes the evidence to /data/local/tmp/webpage-<name>.{png,txt}:
 * the WebView's on-screen pixels, its measured size, document.readyState, the body's text length, every console line,
 * every load / HTTP error and the final URL. Fails when the body has no text or the WebView's pixels are one colour.
 */
@RunWith(AndroidJUnit4::class)
@org.junit.FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)
class WebPagesRenderTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ua = inst.uiAutomation
    /** Readable by `adb pull` without root: /sdcard/Android/data/<pkg>/files/webpage. */
    private val out by lazy { File(inst.targetContext.getExternalFilesDir(null), "webpage").apply { mkdirs() } }

    private fun sh(cmd: String): String =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(ua.executeShellCommand(cmd)).bufferedReader().readText()

    @After fun tearDown() { WebPageFragment.probe = null }

    @Test fun a_webAppsRenders() = check("webapps", "page:cloud/webapps", night = false)
    @Test fun b_webAppsRendersAtNight() = check("webapps-night", "page:cloud/webapps", night = true)
    @Test fun c_linktreeRenders() = check("linktree", "page:cloud/lnktree", night = false)

    /** The app is uninstalled after the run (taking its files dir with it), so the shell copies the evidence out. */
    private fun export(name: String) {
        sh("mkdir -p /data/local/tmp/webpage")
        out.listFiles()?.filter { it.name.contains("-$name.") || it.name.contains("-$name-screen.") }
            ?.forEach { sh("cp ${it.absolutePath} /data/local/tmp/webpage/${it.name}") }
    }

    private fun resumed(): Activity? {
        var a: Activity? = null
        inst.runOnMainSync {
            val m = ActivityLifecycleMonitorRegistry.getInstance()
            a = listOf(Stage.RESUMED, Stage.PAUSED, Stage.STARTED).firstNotNullOfOrNull { m.getActivitiesInStage(it).firstOrNull() }
        }
        return a
    }

    /** The app's wireless-debugging bootstrap raises SystemUI's "Allow wireless debugging?" dialog on a fresh emulator; it
     *  pauses the page under test (same workaround as EdgeOverlayGestureTest). */
    private fun clearPrompt() {
        sh("settings put global adb_wifi_enabled 0")
        repeat(6) {
            if (!sh("dumpsys window windows").contains("WifiDebuggingActivity")) return
            sh("input keyevent KEYCODE_BACK"); SystemClock.sleep(600)
        }
    }

    private fun findWeb(v: View): WebView? = when (v) {
        is WebView -> v
        is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { findWeb(v.getChildAt(it)) }
        else -> null
    }

    private fun js(w: WebView, expr: String): String {
        val latch = CountDownLatch(1); var r = "<timeout>"
        inst.runOnMainSync { w.evaluateJavascript(expr) { r = it; latch.countDown() } }
        latch.await(3, TimeUnit.SECONDS)
        return r
    }

    private fun check(name: String, target: String, night: Boolean) {
        sh("cmd uimode night ${if (night) "yes" else "no"}"); SystemClock.sleep(1500)
        val log = Collections.synchronizedList(mutableListOf<String>())
        WebPageFragment.probe = { log += it }
        sh("input keyevent KEYCODE_WAKEUP"); sh("wm dismiss-keyguard"); sh("logcat -c")
        val ctx = inst.targetContext
        ctx.startActivity(Intent().setClassName(ctx.packageName, "${ctx.packageName}.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra("shortcut_action", target))

        val t0 = SystemClock.elapsedRealtime()
        var web: WebView? = null
        var ready = ""; var textLen = -1
        while (SystemClock.elapsedRealtime() - t0 < 20_000) {
            SystemClock.sleep(1000)
            clearPrompt()
            val a = resumed() ?: continue
            inst.runOnMainSync { web = findWeb(a.window.decorView) }
            val w = web ?: continue
            ready = js(w, "document.readyState")
            textLen = js(w, "(document.body ? document.body.innerText.length : -1)").trim('"').toIntOrNull() ?: -1
            log += "t=${SystemClock.elapsedRealtime() - t0} ready=$ready len=$textLen"
            if (ready.contains("complete") && textLen > 0 && SystemClock.elapsedRealtime() - t0 > 8_000) break
        }
        clearPrompt(); SystemClock.sleep(1500)

        val report = StringBuilder()
        val w = web
        var crop: Bitmap? = null
        if (w == null) report.appendLine("NO WebView found in the resumed activity (${resumed()?.javaClass?.name})")
        else {
            val loc = IntArray(2); var size = ""; var url = ""; var vis = ""
            inst.runOnMainSync {
                w.getLocationOnScreen(loc); size = "${w.width}x${w.height} measured=${w.measuredWidth}x${w.measuredHeight}"
                url = w.url.orEmpty(); vis = "shown=${w.isShown} alpha=${w.alpha} visibility=${w.visibility} layer=${w.layerType}"
            }
            ready = js(w, "document.readyState")
            textLen = js(w, "(document.body ? document.body.innerText.length : -1)").trim('"').toIntOrNull() ?: -1
            report.appendLine("night=$night uimode=${sh("cmd uimode night").trim()}")
            report.appendLine("size=$size at=${loc[0]},${loc[1]} $vis")
            report.appendLine("finalUrl=$url")
            report.appendLine("readyState=$ready bodyTextLen=$textLen")
            report.appendLine("ua=${js(w, "navigator.userAgent")}")
            report.appendLine("bodyText=${js(w, "(document.body ? document.body.innerText.slice(0,400) : '')")}")
            report.appendLine("bodyStyle=${js(w, "(()=>{const s=getComputedStyle(document.body);return [s.color,s.backgroundColor,s.display,s.visibility,s.opacity,document.documentElement.scrollHeight,innerWidth+'x'+innerHeight].join(' | ')})()")}")
            report.appendLine("prefersDark=${js(w, "String(matchMedia('(prefers-color-scheme: dark)').matches)")} htmlClass=${js(w, "document.documentElement.className")}")
            report.appendLine("darkening=${if (android.os.Build.VERSION.SDK_INT >= 33) w.settings.isAlgorithmicDarkeningAllowed.toString() else "pre-33"} sdk=${android.os.Build.VERSION.SDK_INT}")
            report.appendLine("sw=${js(w, "String(!!navigator.serviceWorker && !!navigator.serviceWorker.controller)")}")
            val shot = ua.takeScreenshot()
            if (shot != null) {
                shot.compress(Bitmap.CompressFormat.PNG, 100, File(out, "webpage-$name-screen.png").outputStream())
                val r = Rect(loc[0], loc[1], loc[0] + w.width, loc[1] + w.height)
                if (r.intersect(0, 0, shot.width, shot.height) && r.width() > 0 && r.height() > 0) {
                    crop = Bitmap.createBitmap(shot, r.left, r.top, r.width(), r.height())
                    crop.compress(Bitmap.CompressFormat.PNG, 100, File(out, "webpage-$name.png").outputStream())
                }
            }
        }
        val colours = crop?.let { b ->
            val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height); px.toSet().size
        } ?: 0
        report.appendLine("distinctColours=$colours")
        report.appendLine("--- events (${log.size}) ---"); synchronized(log) { log.forEach { report.appendLine(it) } }
        File(out, "webpage-$name.txt").writeText(report.toString())
        runCatching { File(out, "logcat-$name.txt").writeText(sh("logcat -d -v time -t 3000")) }
        export(name)
        Log.i("WebPagesRenderTest", "[$name]\n$report")

        assertTrue("[$name] no WebView\n$report", w != null)
        assertTrue("[$name] body text is empty (len=$textLen)\n$report", textLen > 0)
        assertTrue("[$name] WebView renders blank (one colour)\n$report", colours > 1)
    }
}
