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
 * Cloud ▸ WebApps (the embedded cloud-mobile page: section headers kept, fits one screen, sits below the SuperApp top row) and Cloud ▸ Linktree on a REAL emulator WebView (Robolectric cannot render one). Opens each page through
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

    @Test fun a_webAppsEmbedRenders() = check("webapps", "page:cloud/webapps", night = false, embed = true)
    @Test fun b_webAppsEmbedRendersAtNight() = check("webapps-night", "page:cloud/webapps", night = true, embed = true)
    @Test fun c_linktreeRenders() = check("linktree", "page:cloud/lnktree", night = false)

    /**
     * Back follows the embedded page's history: from the WebApps main page, open an app page (a same-origin link, which must stay
     * inside the WebView and land in its history), press the SYSTEM Back and land on the main page again; open the app page again
     * and press the shell's top-right Back arrow, same result. Only at the main page does Back leave the page.
     */
    @Test fun d_webAppsBackFollowsHistory() {
        sh("settings put global adb_wifi_enabled 0")
        val pkg = inst.targetContext.packageName
        sh("appops set $pkg REQUEST_INSTALL_PACKAGES allow")
        sh("cmd role add-role-holder android.app.role.HOME $pkg 0")
        sh("cmd uimode night no"); SystemClock.sleep(1500)
        sh("input keyevent KEYCODE_WAKEUP"); sh("wm dismiss-keyguard")
        val ctx = inst.targetContext
        ctx.startActivity(Intent().setClassName(ctx.packageName, "${ctx.packageName}.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra("shortcut_action", "page:cloud/webapps"))

        fun web(): WebView? { var w: WebView? = null; resumed()?.let { a -> inst.runOnMainSync { w = findWeb(a.window.decorView) } }; return w }
        fun until(what: String, ms: Long = 20_000, cond: () -> Boolean) {
            val t0 = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - t0 < ms) { clearPrompt(); if (cond()) return; SystemClock.sleep(500) }
            throw AssertionError("timed out waiting for: $what")
        }
        fun urlOf(w: WebView): String { var u = ""; inst.runOnMainSync { u = w.url.orEmpty() }; return u }
        fun canBack(w: WebView): Boolean { var b = false; inst.runOnMainSync { b = w.canGoBack() }; return b }
        fun tiles(w: WebView) = js(w, "document.querySelectorAll('a[href]').length").trim('"').toIntOrNull() ?: 0

        var w: WebView? = null
        until("the WebApps page loads") { w = web(); val v = w; v != null && js(v, "document.readyState").contains("complete") && tiles(v) > 0 }
        val main = urlOf(w!!)
        fun openApp() {
            val v = w!!
            // The first same-origin link that leaves the page: it must stay in this WebView (shouldOverrideUrlLoading) and add a history entry.
            val clicked = js(v, "(()=>{const a=[...document.querySelectorAll('a[href]')].find(a=>a.origin===location.origin&&a.href.split('#')[0]!==location.href.split('#')[0]&&!a.hasAttribute('download'));if(!a)return 'none';a.click();return a.href})()")
            assertTrue("no same-origin app link on the WebApps page", clicked != "\"none\"")
            until("the app page opens inside the WebView") { urlOf(v).split('#')[0] != main.split('#')[0] && canBack(v) }
        }
        fun tapShellBack() {
            val a = resumed()!!; val loc = IntArray(2); var wd = 0; var ht = 0
            inst.runOnMainSync { a.findViewById<View>(R.id.toolbar).let { it.getLocationOnScreen(loc); wd = it.width; ht = it.height } }
            sh("input tap ${loc[0] + wd - (28 * a.resources.displayMetrics.density).toInt()} ${loc[1] + ht / 2}")
        }

        openApp()
        val app = urlOf(w!!)
        sh("input keyevent KEYCODE_BACK")
        until("system Back returns to the WebApps main page") { urlOf(w!!).split('#')[0] == main.split('#')[0] }
        assertTrue("Back left the WebApps page instead of stepping back (url $app -> ${urlOf(w!!)})", web() != null)

        openApp()
        tapShellBack()
        until("the top-right Back arrow returns to the WebApps main page") { urlOf(w!!).split('#')[0] == main.split('#')[0] }
        assertTrue("the Back arrow left the WebApps page instead of stepping back", web() != null)
    }

    /** The app is uninstalled after the run (taking its files dir with it), so the shell copies the evidence out. */
    private fun export(name: String) {
        sh("mkdir -p /data/local/tmp/webpage")
        out.listFiles()?.filter { it.name.contains("-$name.") || it.name.contains("-$name-screen.") || it.name.contains("-$name-red-screen.") }
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
            // Only when the dialog HOLDS focus: a BACK that lands on the shell instead pops the page under test.
            if (!sh("dumpsys window").lineSequence().filter { "mCurrentFocus" in it }.any { "WifiDebugging" in it }) return
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

    private fun check(name: String, target: String, night: Boolean, embed: Boolean = false) {
        sh("settings put global adb_wifi_enabled 0")
        // FirstRunPerms asks for these two on launch with a dialog over the page; grant them so the pane is unobstructed.
        val pkg = inst.targetContext.packageName
        sh("appops set $pkg REQUEST_INSTALL_PACKAGES allow")
        sh("cmd role add-role-holder android.app.role.HOME $pkg 0")
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

        var htmlH = -1
        var embedHeaders = ""; var embedBottom = -1; var embedInner = -1; var embedOverflow = -1; var embedOwnTabs = -1; var embedApkRow = -1
        var embedTiles = -1; var islandBottom = -1; var islandShown = false
        var pageTitle = ""; var groups = -1
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
            htmlH = js(w, "Math.round(document.documentElement.getBoundingClientRect().height)").trim('"').toIntOrNull() ?: -1
            report.appendLine("readyState=$ready bodyTextLen=$textLen htmlHeight=$htmlH")
            pageTitle = js(w, "document.title").trim('"')
            groups = js(w, "document.querySelectorAll('.tile-group__title').length").trim('"').toIntOrNull() ?: -1
            report.appendLine("title=$pageTitle tileGroups=$groups")
            if (embed) {
                fun num(expr: String) = js(w, expr).trim('"').toIntOrNull() ?: -1
                embedHeaders = js(w, "[...document.querySelectorAll('.tile-group__title')].map(e=>e.textContent.trim()).join(' | ')")
                embedTiles = num("document.querySelectorAll('[data-app-group] .tile').length")
                embedBottom = num("(()=>{const r=document.querySelector('#embed-fit');return r?Math.round(r.getBoundingClientRect().bottom):-1})()")
                embedInner = num("innerHeight")
                embedOverflow = num("(()=>{const c=document.querySelector('.content');return (c.scrollHeight>c.clientHeight+1||document.documentElement.scrollHeight>innerHeight)?1:0})()")
                embedOwnTabs = num("(()=>{const e=document.querySelector('.page-tabs-bar');return e&&getComputedStyle(e).display!=='none'?1:0})()")
                embedApkRow = num("(()=>{const e=document.querySelector('.site-footer');return e&&getComputedStyle(e).display!=='none'?1:0})()")
                // The SuperApp's top row (status / app top bar island) stays visible and the page sits BELOW it, inside the shell.
                // resumed() itself runs on the main thread: calling it inside runOnMainSync throws "can not be called from the main application thread".
                val shell = resumed()
                inst.runOnMainSync {
                    val island = shell?.findViewById<View>(R.id.toolbar_island)
                    if (island != null) {
                        val l = IntArray(2); island.getLocationOnScreen(l)
                        islandBottom = l[1] + island.height; islandShown = island.isShown && island.height > 0
                    }
                }
                report.appendLine("embed: headers=[$embedHeaders] fitBottom=$embedBottom/$embedInner overflow=$embedOverflow ownTabs=$embedOwnTabs apkRow=$embedApkRow tiles=$embedTiles " +
                    "webTop=${loc[1]} topRowBottom=$islandBottom topRowShown=$islandShown")
            }
            report.appendLine("ua=${js(w, "navigator.userAgent")}")
            report.appendLine("bodyText=${js(w, "(document.body ? document.body.innerText.slice(0,400) : '')")}")
            report.appendLine("bodyStyle=${js(w, "(()=>{const s=getComputedStyle(document.body);return [s.color,s.backgroundColor,s.display,s.visibility,s.opacity,document.documentElement.scrollHeight,innerWidth+'x'+innerHeight].join(' | ')})()")}")
            report.appendLine("prefersDark=${js(w, "String(matchMedia('(prefers-color-scheme: dark)').matches)")} htmlClass=${js(w, "document.documentElement.className")}")
            var dark = ""
            inst.runOnMainSync { dark = if (android.os.Build.VERSION.SDK_INT >= 33) w.settings.isAlgorithmicDarkeningAllowed.toString() else "pre-33" }
            report.appendLine("darkening=$dark sdk=${android.os.Build.VERSION.SDK_INT}")
            report.appendLine("dom=${js(w, "(()=>{const r=e=>{if(!e)return 'none';const b=e.getBoundingClientRect(),c=getComputedStyle(e);return e.tagName+'.'+e.className+' '+[b.x,b.y,b.width,b.height].map(Math.round).join(',')+' op='+c.opacity+' vis='+c.visibility+' bg='+c.backgroundImage.slice(0,40)+'/'+c.backgroundColor+' fg='+c.color+' tf='+c.transform+' filt='+c.filter};return ['shell:'+r(document.querySelector('.shell')),'content:'+r(document.querySelector('.content')),'at(160,40):'+r(document.elementFromPoint(160,40)),'at(160,200):'+r(document.elementFromPoint(160,200)),'dpr='+devicePixelRatio,'vv='+(visualViewport?visualViewport.width+'x'+visualViewport.height+'@'+visualViewport.scale:'')].join(' || ')})()")}")
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
        // Discriminator: paint the page red. A pane that turns red is a page drawing black; one that stays black is a WebView
        // that is not compositing at all.
        if (w != null) {
            js(w, "document.documentElement.style.background='red';document.body.style.background='red';'ok'")
            SystemClock.sleep(1500)
            ua.takeScreenshot()?.let { File(out, "webpage-$name-red-screen.png").outputStream().use { o -> it.compress(Bitmap.CompressFormat.PNG, 100, o) } }
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
        assertTrue("[$name] page laid out with zero height (<html> $htmlH px)\n$report", htmlH > 0)
        assertTrue("[$name] WebView renders blank (one colour)\n$report", colours > 1)
        if (embed) {
            val webTop = IntArray(2).also { a -> inst.runOnMainSync { w!!.getLocationOnScreen(a) } }[1]
            assertTrue("[$name] the section headers are missing: [$embedHeaders]\n$report", groups >= 5)
            val sourceTiles = com.diegonmarcos.superapp.launcher.Sections.byId("cloud")!!.tileGroups.flatMap { it.destinations }.size
            assertTrue("[$name] the embedded page shows $embedTiles app tiles, the SuperApp's Cloud list has $sourceTiles (an app went missing)\n$report",
                embedTiles == sourceTiles && embedTiles >= 40)
            assertTrue("[$name] the page still draws its own tab bar\n$report", embedOwnTabs == 0)
            assertTrue("[$name] the page still draws the APK download row\n$report", embedApkRow == 0)
            assertTrue("[$name] the page does not fit one screen: bottom $embedBottom of $embedInner, overflow=$embedOverflow\n$report",
                embedBottom in 1..embedInner && embedOverflow == 0)
            assertTrue("[$name] the SuperApp's top row is not visible\n$report", islandShown)
            assertTrue("[$name] the page (top $webTop px) is not below the SuperApp's top row (bottom $islandBottom px)\n$report", webTop >= islandBottom - 2)
        }
    }
}
