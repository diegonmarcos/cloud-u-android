package com.diegonmarcos.superapp.browser

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.util.WeakHashMap
import java.util.concurrent.Executors

/**
 * Web Serial, WebUSB and Web NFC for Cloud Browser (#1225), the siblings of [WebBluetooth]. Android WebView has no navigator.serial,
 * navigator.usb or NDEFReader, so every page gets web_hardware.js (document start where the WebView supports it, else at page start)
 * and [Bridge], the one JS interface it talks to. Each WebView has its own [WebHardwareCore]; the USB host and the NFC reader are
 * process-wide (a device has one owner), the grants are per API: web_serial_grants, web_usb_grants, web_nfc_grants. The origin of a
 * request is read from the WebView on the main thread, never from the page's own words.
 *
 * Backends: Android's UsbManager for WebUSB (bulk, interrupt and control transfers; isochronous and reset are not offered by that API),
 * USB CDC ACM for Web Serial (CdcAcmSerial over the same USB host), NfcAdapter reader mode for Web NFC (NDEF read / write; not raw
 * ISO-DEP, not makeReadOnly). A phone without USB host or NFC reports so to the page.
 */
class WebHardware(private val ctx: Context, private val activity: () -> Activity?) {
    private val main = Handler(Looper.getMainLooper())
    private val cores = WeakHashMap<WebView, WebHardwareCore>()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "web-hardware").apply { isDaemon = true } }
    private val usb by lazy { AndroidUsbBackend(ctx) }
    private val serial by lazy { CdcAcmSerial(usb) { loop -> pool.execute { loop() } } }
    private val nfc by lazy { AndroidNfcBackend(ctx) { activity() } }
    private val serialStore by lazy { WebHwGrants(Kv(ctx.getSharedPreferences("web_serial_grants", Context.MODE_PRIVATE)), "serial") }
    private val usbStore by lazy { WebHwGrants(Kv(ctx.getSharedPreferences("web_usb_grants", Context.MODE_PRIVATE)), "usb") }
    private val nfcStore by lazy { WebHwGrants(Kv(ctx.getSharedPreferences("web_nfc_grants", Context.MODE_PRIVATE)), "nfc") }

    private val permission = object : WebHardwareCore.UsbPermission {
        override fun ensure(key: String, done: (Boolean) -> Unit) { main.post { usb.requestPermission(key, done) } }
    }

    /** Call once per WebView, before it loads anything. A private tab's grants live in memory and end with the tab. */
    fun attach(w: WebView, privateTab: Boolean) {
        fun grants(api: String, shared: WebHwGrants) = if (privateTab) WebHwGrants(Mem(), api) else shared
        val core = WebHardwareCore(usb, serial, nfc, grants("serial", serialStore), grants("usb", usbStore), grants("nfc", nfcStore),
            ComposeHwChooser(w), permission, { f -> pool.execute { f() } }) { json -> deliver(w, json) }
        cores[w] = core
        w.addJavascriptInterface(Bridge(w, core), JS_BRIDGE)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching { WebViewCompat.addDocumentStartJavaScript(w, script(), setOf("*")) }
        }
    }

    /** A navigation began: what the old page opened is closed, and the polyfill is (re)installed for the new one. */
    fun onPageStarted(w: WebView) {
        cores[w]?.reset()
        w.evaluateJavascript(script(), null)
    }

    fun detach(w: WebView) { cores.remove(w)?.close() }

    fun script(): String = ctx.assets.open("browser/web_hardware.js").bufferedReader().use { it.readText() }

    private fun deliver(w: WebView, json: String) {
        main.post { w.evaluateJavascript("window.__cloudHw&&window.__cloudHw.recv(" + JSONObject.quote(json) + ")", null) }
    }

    private class Bridge(private val w: WebView, private val core: WebHardwareCore) {
        private val main = Handler(Looper.getMainLooper())

        /** The page's one call into the app. Runs on a WebView thread: the origin is read on the main thread, then the core takes it. */
        @JavascriptInterface
        fun post(json: String?) {
            val text = json ?: return
            main.post { core.handle(WebBluetoothCore.originOf(w.url), text) }
        }
    }

    private class Kv(private val p: android.content.SharedPreferences) : WebBleGrants.KeyValue {
        override fun get(key: String): String? = p.getString(key, null)
        override fun put(key: String, value: String?) { p.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply() }
        override fun keys(): Set<String> = p.all.keys
    }

    private class Mem : WebBleGrants.KeyValue {
        private val m = HashMap<String, String>()
        override fun get(key: String): String? = m[key]
        override fun put(key: String, value: String?) { if (value == null) m.remove(key) else m[key] = value }
        override fun keys(): Set<String> = m.keys.toSet()
    }

    companion object { const val JS_BRIDGE = "AndroidWebHardware" }
}
