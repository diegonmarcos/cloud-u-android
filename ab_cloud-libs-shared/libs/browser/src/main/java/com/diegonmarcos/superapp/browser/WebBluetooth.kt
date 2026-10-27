package com.diegonmarcos.superapp.browser

import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.ArrayAdapter
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.diegonmarcos.superapp.bluetooth.BlePermissions
import com.diegonmarcos.superapp.bluetooth.BleDevice
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
 * Feasibility of the sibling APIs (#1222 ticket): Web Serial and WebUSB would need a UsbManager permission flow, a native chooser and
 * a bridge of the same shape (usb-serial / bulk transfers): feasible, a separate ticket each. Web NFC needs the foreground NFC
 * dispatch of the one visible WebView and an NDEF bridge: feasible for NDEFReader scan / write, not for raw ISO-DEP. None is built here.
 *
 * [requestPermissions] asks Android for the Bluetooth permissions in the host's UI (the fragment's launcher).
 */
class WebBluetooth(private val ctx: Context, private val requestPermissions: (Array<String>, (Boolean) -> Unit) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val cores = WeakHashMap<WebView, WebBluetoothCore>()
    private val prefsGrants by lazy { WebBleGrants(PrefsKv(ctx.getSharedPreferences("web_bluetooth_grants", Context.MODE_PRIVATE))) }
    private val catalog: GattCatalog by lazy { BluetoothHub.catalog(ctx) }

    private val permissions = object : WebBlePermissions {
        override fun missing(): List<String> = BlePermissions.missing(ctx)
        override fun request(perms: List<String>, done: (Boolean) -> Unit) { main.post { requestPermissions(perms.toTypedArray(), done) } }
    }

    /** Call once per WebView, before it loads anything. A private tab's grants live in memory and end with the tab. */
    fun attach(w: WebView, privateTab: Boolean) {
        val grants = if (privateTab) WebBleGrants(MemoryKv()) else prefsGrants
        val core = WebBluetoothCore(BluetoothHub.session(ctx), catalog, grants, DialogChooser(w), permissions) { json -> deliver(w, json) }
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

    /** The native device chooser: a list that fills while the scan runs. Exactly one of pick / cancel. */
    private class DialogChooser(private val w: WebView) : WebBleChooser {
        private val main = Handler(Looper.getMainLooper())

        override fun show(request: ChooserRequest, onPick: (BleDevice) -> Unit, onCancel: () -> Unit) {
            main.post {
                val items = ArrayList<String>()
                val adapter = ArrayAdapter(w.context, android.R.layout.simple_list_item_1, items)
                var shown: List<BleDevice> = emptyList()
                var done = false
                val host = request.origin.substringAfter("://")
                val dialog = AlertDialog.Builder(w.context)
                    .setTitle("$host wants to connect to a Bluetooth device")
                    .setAdapter(adapter) { _, which -> shown.getOrNull(which)?.let { done = true; onPick(it) } }
                    .setNegativeButton("Cancel") { _, _ -> }
                    .setOnDismissListener { if (!done) { done = true; onCancel() } }
                    .create()
                request.onUpdate = {
                    main.post {
                        shown = request.devices
                        items.clear()
                        shown.forEach { items.add("${it.label}  (${it.rssi} dBm)\n${it.address}") }
                        adapter.notifyDataSetChanged()
                        dialog.setTitle(if (shown.isEmpty()) "$host: scanning..." else "$host wants to connect to a Bluetooth device")
                    }
                }
                runCatching { dialog.show() }.onFailure { if (!done) { done = true; onCancel() } }
            }
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
