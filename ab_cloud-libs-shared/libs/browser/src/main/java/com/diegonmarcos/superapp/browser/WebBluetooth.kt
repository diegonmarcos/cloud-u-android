package com.diegonmarcos.superapp.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.diegonmarcos.superapp.bluetooth.BlePermissions
import com.diegonmarcos.superapp.bluetooth.BluetoothHub
import com.diegonmarcos.superapp.bluetooth.GattCatalog
import org.json.JSONObject
import java.util.WeakHashMap

/**
 * Web Bluetooth for Cloud Browser (#1222). Android WebView has no navigator.bluetooth, so every page gets web_bluetooth.js (document
 * start where the WebView supports it, else at page start) and [Bridge], the one JS interface it talks to. Each WebView has its own
 * [WebBluetoothCore]; they share libs:bluetooth's session, so a connection is the same one the SuperApp's Bluetooth page shows.
 * The origin of a request is read from the WebView on the main thread, never from the page's own words.
 *
 * Web Serial, WebUSB and Web NFC (#1225) are the siblings of this file: WebHardware.kt, with the same shape (bridge, contract, core, Compose chooser, grants).
 *
 * [needsPermissions] is told which Bluetooth permissions the app lacks: the host shows the "Permissions needed" banner that opens
 * the Permissions page (#1274); this class never asks Android itself.
 */
class WebBluetooth(private val ctx: Context, private val needsPermissions: (Array<String>, (Boolean) -> Unit) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val cores = WeakHashMap<WebView, WebBluetoothCore>()
    private val prefsGrants by lazy { WebBleGrants(PrefsKv(ctx.getSharedPreferences("web_bluetooth_grants", Context.MODE_PRIVATE))) }
    private val catalog: GattCatalog by lazy { BluetoothHub.catalog(ctx) }

    private val permissions = object : WebBlePermissions {
        override fun missing(): List<String> = BlePermissions.missing(ctx)
        override fun request(perms: List<String>, done: (Boolean) -> Unit) { main.post { needsPermissions(perms.toTypedArray(), done) } }
    }

    /** Call once per WebView, before it loads anything. A private tab's grants live in memory and end with the tab. */
    fun attach(w: WebView, privateTab: Boolean) {
        val grants = if (privateTab) WebBleGrants(MemoryKv()) else prefsGrants
        val core = WebBluetoothCore(BluetoothHub.session(ctx), catalog, grants, ComposeChooser(w), permissions) { json -> deliver(w, json) }
        cores[w] = core
        w.addJavascriptInterface(Bridge(w, core), JS_BRIDGE)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching { WebViewCompat.addDocumentStartJavaScript(w, script(), setOf("*")) }
        }
    }

    /** A navigation began: what the old page connected is dropped, and the polyfill is (re)installed for the new one. */
    fun onPageStarted(w: WebView) {
        cores[w]?.reset()
        w.evaluateJavascript(script(), null)
    }

    fun detach(w: WebView) { cores.remove(w)?.let { it.reset(); it.close() } }

    fun script(): String = scriptWith(ctx.assets.open("browser/web_bluetooth.js").bufferedReader().use { it.readText() }, catalog)

    private fun deliver(w: WebView, json: String) {
        main.post { w.evaluateJavascript("window.__cloudBle&&window.__cloudBle.recv(" + JSONObject.quote(json) + ")", null) }
    }

    private class Bridge(private val w: WebView, private val core: WebBluetoothCore) {
        private val main = Handler(Looper.getMainLooper())

        /** The page's one call into the app. Runs on a WebView thread: the origin is read on the main thread, then the core takes it. */
        @JavascriptInterface
        fun post(json: String?) {
            val text = json ?: return
            main.post { core.handle(WebBluetoothCore.originOf(w.url), text) }
        }
    }

    private class PrefsKv(private val p: android.content.SharedPreferences) : WebBleGrants.KeyValue {
        override fun get(key: String): String? = p.getString(key, null)
        override fun put(key: String, value: String?) { p.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply() }
        override fun keys(): Set<String> = p.all.keys
    }

    private class MemoryKv : WebBleGrants.KeyValue {
        private val m = HashMap<String, String>()
        override fun get(key: String): String? = m[key]
        override fun put(key: String, value: String?) { if (value == null) m.remove(key) else m[key] = value }
        override fun keys(): Set<String> = m.keys.toSet()
    }

    companion object {
        const val JS_BRIDGE = "AndroidWebBluetooth"

        /** The polyfill with the catalog's names filled in: `heart_rate` and the other Web Bluetooth aliases come from the data, not from this file. */
        fun scriptWith(template: String, catalog: GattCatalog): String {
            val services = JSONObject().also { o -> catalog.services.filter { it.alias.isNotEmpty() }.forEach { o.put(it.alias, it.uuid) } }
            val chars = JSONObject().also { o -> catalog.characteristics.filter { it.alias.isNotEmpty() }.forEach { o.put(it.alias, it.uuid) } }
            return template.replace("__ALIASES__", JSONObject().put("services", services).put("characteristics", chars).toString())
        }
    }
}
