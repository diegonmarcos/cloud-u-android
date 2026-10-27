package com.diegonmarcos.superapp.browser

import com.diegonmarcos.superapp.bluetooth.BleDevice
import com.diegonmarcos.superapp.bluetooth.BleFilters
import com.diegonmarcos.superapp.bluetooth.ConnState
import com.diegonmarcos.superapp.bluetooth.GattCatalog
import com.diegonmarcos.superapp.bluetooth.GattCharacteristic
import com.diegonmarcos.superapp.bluetooth.GattListener
import com.diegonmarcos.superapp.bluetooth.GattProps
import com.diegonmarcos.superapp.bluetooth.GattSession
import com.diegonmarcos.superapp.bluetooth.Hex
import com.diegonmarcos.superapp.bluetooth.LogKind
import com.diegonmarcos.superapp.bluetooth.NotifyMode
import com.diegonmarcos.superapp.bluetooth.Uuids
import com.diegonmarcos.superapp.bluetooth.WebFilter
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/** What a site was allowed (#1222): one device, the services the page named for it. Origin-scoped by [WebBleGrants]. */
data class WebBleGrant(val address: String, val name: String?, val services: Set<String>)

/**
 * Per-origin device permission memory, Chrome's model: a grant is made by the user in the native chooser, belongs to ONE origin
 * (scheme + host + port), survives restarts, lists in `navigator.bluetooth.getDevices()` and ends on `device.forget()` or a clear.
 * [KeyValue] is the persistence (SharedPreferences on the phone, a map in a test).
 */
class WebBleGrants(private val kv: KeyValue) {
    interface KeyValue { fun get(key: String): String?; fun put(key: String, value: String?); fun keys(): Set<String> }

    fun forOrigin(origin: String): List<WebBleGrant> {
        val arr = runCatching { JSONArray(kv.get(origin) ?: return emptyList()) }.getOrNull() ?: return emptyList()
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val s = o.optJSONArray("services")
            WebBleGrant(o.getString("address"), o.optString("name").ifEmpty { null }, if (s == null) emptySet() else List(s.length()) { s.getString(it) }.toSet())
        }
    }

    /** Adds [g]; a device the origin already holds keeps its services and gains the new ones. */
    fun grant(origin: String, g: WebBleGrant) {
        val merged = forOrigin(origin).filter { it.address != g.address } +
            (forOrigin(origin).firstOrNull { it.address == g.address }?.let { g.copy(services = it.services + g.services) } ?: g)
        write(origin, merged)
    }

    fun find(origin: String, deviceId: String): WebBleGrant? = forOrigin(origin).firstOrNull { WebBluetoothCore.deviceId(origin, it.address) == deviceId }
    fun revoke(origin: String, address: String) = write(origin, forOrigin(origin).filter { it.address != address })
    fun clear(origin: String) = kv.put(origin, null)
    fun origins(): List<String> = kv.keys().filter { forOrigin(it).isNotEmpty() }.sorted()

    private fun write(origin: String, list: List<WebBleGrant>) {
        if (list.isEmpty()) { kv.put(origin, null); return }
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("address", it.address).put("name", it.name ?: "").put("services", JSONArray(it.services.toList()))) }
        kv.put(origin, arr.toString())
    }
}

/** The device list the native chooser shows while the scan runs. [devices] is the filtered, strongest-first snapshot. */
class ChooserRequest(val origin: String) {
    private val list = CopyOnWriteArrayList<BleDevice>()
    @Volatile var onUpdate: (() -> Unit)? = null
    val devices: List<BleDevice> get() = list.sortedByDescending { it.rssi }
    internal fun see(d: BleDevice) {
        val i = list.indexOfFirst { it.address == d.address }
        if (i >= 0) list[i] = list[i].merge(d) else list.add(d)
        onUpdate?.invoke()
    }
}

/** The native device chooser: shows [request]'s devices live and calls exactly one of [onPick] / [onCancel]. */
interface WebBleChooser { fun show(request: ChooserRequest, onPick: (BleDevice) -> Unit, onCancel: () -> Unit) }

/** The app's Bluetooth runtime permissions: which are missing, and asking for them. */
interface WebBlePermissions { fun missing(): List<String>; fun request(perms: List<String>, done: (Boolean) -> Unit) }

/**
 * Web Bluetooth for one browser tab (#1222): the native half of web_bluetooth.js, over libs:bluetooth's [GattSession]. [handle] takes
 * one request from the page (wire: assets/browser/web-bluetooth-contract.json) with the ORIGIN the host read from the WebView, and
 * answers or emits through [send]. Chrome's rules are kept here, not in the page: a secure origin, a user gesture for requestDevice,
 * a native chooser the user must pick from, grants scoped to the origin, a page reaches only the services it named, and leaving the
 * page disconnects what it connected ([reset]).
 */
class WebBluetoothCore(
    private val session: GattSession,
    private val catalog: GattCatalog,
    private val grants: WebBleGrants,
    private val chooser: WebBleChooser,
    private val permissions: WebBlePermissions,
    private val send: (String) -> Unit,
) : GattListener {

    private val connected = HashSet<String>()                 // addresses this page connected
    private val ids = HashMap<String, String>()               // address -> the device id the page knows
    private val subs = HashMap<String, String>()              // "address|service|char" -> device id
    private val lock = Any()

    init { session.observe(this) }

    fun close() { session.unobserve(this) }

    // ---- entry ----

    fun handle(origin: String, text: String) {
        val m = runCatching { JSONObject(text) }.getOrNull() ?: return
        val id = m.optInt("id", -1)
        if (id < 0 || m.optInt("v") != VERSION) return
        val op = m.optString("op")
        val args = m.optJSONObject("args") ?: JSONObject()
        session.log.add(LogKind.Sys, "web $op from $origin")
        if (!isSecure(origin)) { fail(id, "SecurityError", "Web Bluetooth requires a secure context (https or localhost)."); return }
        runCatching {
            when (op) {
                "getAvailability" -> reply(id, JSONObject().put("available", session.adapter.supported && session.adapter.enabled))
                "getDevices" -> reply(id, JSONObject().put("devices", JSONArray().also { a -> grants.forOrigin(origin).forEach { a.put(info(origin, it.address, it.name)) } }))
                "requestDevice" -> requestDevice(origin, id, args, m.optBoolean("gesture", false))
                "forget" -> withGrant(origin, id, args) { g -> forget(origin, g); reply(id, JSONObject()) }
                "gattConnect" -> withGrant(origin, id, args) { g -> gattConnect(origin, id, g) }
                "gattDisconnect" -> withGrant(origin, id, args) { g -> dropConnection(g.address, disconnect = true); reply(id, JSONObject()) }
                "getPrimaryServices" -> withLink(origin, id, args) { g -> primaryServices(id, g, args.optString("service").ifEmpty { null }) }
                "getCharacteristics" -> withLink(origin, id, args) { g -> characteristics(id, g, args) }
                "readValue" -> withChar(origin, id, args) { g, c -> read(id, g, c) }
                "writeValue" -> withChar(origin, id, args) { g, c -> write(id, g, c, args) }
                "startNotifications" -> withChar(origin, id, args) { g, c -> notifications(id, g, c, true) }
                "stopNotifications" -> withChar(origin, id, args) { g, c -> notifications(id, g, c, false) }
                else -> fail(id, "NotSupportedError", "Unknown operation $op.")
            }
        }.onFailure { fail(id, "NetworkError", it.message ?: it.javaClass.simpleName) }
    }

    /** The page went away or navigated: unsubscribe and disconnect what it connected, like a closed tab. */
    fun reset() {
        val mine = synchronized(lock) { connected.toList().also { connected.clear(); subs.clear(); ids.clear() } }
        mine.forEach { session.disconnect(it) }
    }

    // ---- requestDevice ----

    private fun requestDevice(origin: String, id: Int, args: JSONObject, gesture: Boolean) {
        if (!gesture) { fail(id, "SecurityError", "Must be handling a user gesture to show a permission request."); return }
        val all = args.optBoolean("acceptAllDevices", false)
        val fa = args.optJSONArray("filters")
        if (all == (fa != null) || (fa != null && fa.length() == 0)) {
            fail(id, "TypeError", "Either 'filters' should be present or 'acceptAllDevices' should be true, but not both."); return
        }
        val filters = ArrayList<WebFilter>()
        for (i in 0 until (fa?.length() ?: 0)) {
            val f = fa!!.getJSONObject(i)
            val s = f.optJSONArray("services")
            val services = List(s?.length() ?: 0) { Uuids.normalize(s!!.getString(it)) ?: run { fail(id, "TypeError", "Invalid service UUID."); return } }
            filters += WebFilter(services, if (f.has("name")) f.getString("name") else null, if (f.has("namePrefix")) f.getString("namePrefix") else null)
        }
        val oa = args.optJSONArray("optionalServices")
        val optional = List(oa?.length() ?: 0) { Uuids.normalize(oa!!.getString(it)) ?: run { fail(id, "TypeError", "Invalid optional service UUID."); return } }
        val allowed = (filters.flatMap { it.services } + optional).toSet()
        ensurePermissions(id) {
            val adapter = session.adapter
            if (!adapter.supported || !adapter.enabled) { fail(id, "NotFoundError", "Bluetooth adapter not available."); return@ensurePermissions }
            val req = ChooserRequest(origin)
            val started = adapter.startScan({ d -> if (BleFilters.matchesWeb(d, filters)) req.see(d) }) { err -> session.log.add(LogKind.Error, "web scan: $err") }
            if (!started) { fail(id, "NotFoundError", "Bluetooth scan could not start."); return@ensurePermissions }
            chooser.show(req, { d ->
                adapter.stopScan()
                grants.grant(origin, WebBleGrant(d.address, d.name, allowed))
                reply(id, JSONObject().put("device", info(origin, d.address, d.name)))
            }, {
                adapter.stopScan()
                fail(id, "NotFoundError", "User cancelled the requestDevice() chooser.")
            })
        }
    }

    private fun ensurePermissions(id: Int, then: () -> Unit) {
        val missing = permissions.missing()
        if (missing.isEmpty()) { then(); return }
        permissions.request(missing) { ok -> if (ok) then() else fail(id, "NotAllowedError", "Bluetooth permission was denied.") }
    }

    // ---- connection ----

    private fun gattConnect(origin: String, id: Int, g: WebBleGrant) {
        ensurePermissions(id) {
            session.connect(g.address) { r ->
                if (!r.ok) { fail(id, "NetworkError", "Connection attempt failed."); return@connect }
                synchronized(lock) { connected += g.address; ids[g.address] = WebBluetoothCore.deviceId(origin, g.address) }
                session.discover(g.address) { d ->
                    if (d.ok) reply(id, JSONObject()) else { dropConnection(g.address, disconnect = true); fail(id, "NetworkError", "Service discovery failed.") }
                }
            }
        }
    }

    private fun dropConnection(address: String, disconnect: Boolean) {
        synchronized(lock) { connected -= address; ids.remove(address); subs.keys.removeAll { it.startsWith("$address|") } }
        if (disconnect) session.disconnect(address)
    }

    private fun forget(origin: String, g: WebBleGrant) {
        if (g.address in synchronized(lock) { connected.toSet() }) dropConnection(g.address, disconnect = true)
        grants.revoke(origin, g.address)
    }

    // ---- gatt ----

    private fun allowedService(g: WebBleGrant, service: String) = service in g.services

    private fun primaryServices(id: Int, g: WebBleGrant, service: String?) {
        val want = service?.let { Uuids.normalize(it) }
        if (service != null && (want == null || !allowedService(g, want))) { notAllowed(id); return }
        val found = session.services(g.address).map { it.uuid }.filter { allowedService(g, it) && (want == null || it == want) }
        reply(id, JSONObject().put("services", JSONArray(found)))
    }

    private fun characteristics(id: Int, g: WebBleGrant, args: JSONObject) {
        val service = Uuids.normalize(args.optString("service")) ?: run { fail(id, "TypeError", "Invalid service UUID."); return }
        if (!allowedService(g, service)) { notAllowed(id); return }
        val want = args.optString("characteristic").ifEmpty { null }?.let { Uuids.normalize(it) }
        val svc = session.services(g.address).firstOrNull { it.uuid == service } ?: run { fail(id, "NotFoundError", "No Services matching UUID $service found in Device."); return }
        val list = svc.characteristics.filter { want == null || it.uuid == want }
        reply(id, JSONObject().put("characteristics", JSONArray().also { a -> list.forEach { a.put(JSONObject().put("uuid", it.uuid).put("properties", props(it))) } }))
    }

    private fun read(id: Int, g: WebBleGrant, c: GattCharacteristic) {
        if (!c.canRead) { fail(id, "NotSupportedError", "GATT operation not permitted."); return }
        session.read(g.address, c.serviceUuid, c.uuid) { r ->
            if (r.ok) reply(id, JSONObject().put("value", hex(r.value))) else fail(id, "NetworkError", "GATT Error: read failed (${r.status}).")
        }
    }

    private fun write(id: Int, g: WebBleGrant, c: GattCharacteristic, args: JSONObject) {
        val bytes = Hex.parse(args.optString("value")) ?: run { fail(id, "TypeError", "The value is not hex."); return }
        val mode = args.optString("response", "auto")
        val hasResp = c.properties and GattProps.WRITE != 0
        val withResponse = when (mode) {
            "with" -> if (hasResp) true else { fail(id, "NotSupportedError", "GATT operation not permitted."); return }
            "without" -> if (c.canWriteNoResponse) false else { fail(id, "NotSupportedError", "GATT operation not permitted."); return }
            else -> if (hasResp) true else if (c.canWriteNoResponse) false else { fail(id, "NotSupportedError", "GATT operation not permitted."); return }
        }
        session.write(g.address, c.serviceUuid, c.uuid, bytes, withResponse) { r ->
            if (r.ok) reply(id, JSONObject()) else fail(id, "NetworkError", "GATT Error: write failed (${r.status}).")
        }
    }

    private fun notifications(id: Int, g: WebBleGrant, c: GattCharacteristic, on: Boolean) {
        val mode = when { !on -> NotifyMode.Off; c.canNotify -> NotifyMode.Notify; c.canIndicate -> NotifyMode.Indicate
            else -> { fail(id, "NotSupportedError", "GATT operation not permitted."); return } }
        val key = "${g.address}|${c.serviceUuid}|${c.uuid}"
        session.setNotify(g.address, c.serviceUuid, c.uuid, mode) { r ->
            if (!r.ok) { fail(id, "NetworkError", "GATT Error: notification change failed (${r.status})."); return@setNotify }
            synchronized(lock) { if (on) subs[key] = ids[g.address].orEmpty() else subs.remove(key) }
            reply(id, JSONObject())
        }
    }

    // ---- guards ----

    private fun withGrant(origin: String, id: Int, args: JSONObject, block: (WebBleGrant) -> Unit) {
        val g = grants.find(origin, args.optString("device"))
        if (g == null) fail(id, "NotAllowedError", "This origin has no permission for that device.") else block(g)
    }

    /** A granted device the page has connected (the services of an unconnected device are not readable). */
    private fun withLink(origin: String, id: Int, args: JSONObject, block: (WebBleGrant) -> Unit) = withGrant(origin, id, args) { g ->
        if (g.address !in synchronized(lock) { connected.toSet() } || session.state(g.address) != ConnState.Connected)
            fail(id, "NetworkError", "GATT Server is disconnected. Cannot perform GATT operations. (Re)connect first with device.gatt.connect.")
        else block(g)
    }

    private fun withChar(origin: String, id: Int, args: JSONObject, block: (WebBleGrant, GattCharacteristic) -> Unit) = withLink(origin, id, args) { g ->
        val s = Uuids.normalize(args.optString("service")); val ch = Uuids.normalize(args.optString("characteristic"))
        if (s == null || ch == null) fail(id, "TypeError", "Invalid UUID.")
        else if (!allowedService(g, s)) notAllowed(id)
        else {
            val c = session.characteristic(g.address, s, ch)
            if (c == null) fail(id, "NotFoundError", "No Characteristics matching UUID $ch found in Service with UUID $s.") else block(g, c)
        }
    }

    private fun notAllowed(id: Int) = fail(id, "SecurityError",
        "Origin is not allowed to access the service. Tip: Add the service UUID to 'optionalServices' in requestDevice() options.")

    // ---- events from the radio ----

    override fun onChanged(address: String, service: String, char: String, value: ByteArray) {
        val dev = synchronized(lock) { subs["$address|$service|$char"] } ?: return
        send(JSONObject().put("v", VERSION).put("event", "characteristicvaluechanged").put("device", dev)
            .put("service", service).put("characteristic", char).put("value", hex(value)).toString())
    }

    override fun onConnectionState(address: String, state: ConnState, status: Int) {
        if (state != ConnState.Disconnected) return
        val dev = synchronized(lock) { if (address in connected) ids[address] else null } ?: return
        dropConnection(address, disconnect = false)
        send(JSONObject().put("v", VERSION).put("event", "gattserverdisconnected").put("device", dev).toString())
    }

    // ---- wire ----

    private fun reply(id: Int, result: JSONObject) = send(JSONObject().put("v", VERSION).put("id", id).put("ok", true).put("result", result).toString())

    private fun fail(id: Int, name: String, message: String) {
        session.log.add(LogKind.Error, "web $name: $message")
        send(JSONObject().put("v", VERSION).put("id", id).put("ok", false).put("error", JSONObject().put("name", name).put("message", message)).toString())
    }

    private fun info(origin: String, address: String, name: String?) =
        JSONObject().put("id", deviceId(origin, address)).apply { if (!name.isNullOrBlank()) put("name", name) }

    private fun props(c: GattCharacteristic) = JSONObject()
        .put("broadcast", c.properties and GattProps.BROADCAST != 0).put("read", c.canRead)
        .put("writeWithoutResponse", c.canWriteNoResponse).put("write", c.properties and GattProps.WRITE != 0)
        .put("notify", c.canNotify).put("indicate", c.canIndicate)
        .put("authenticatedSignedWrites", c.properties and GattProps.SIGNED_WRITE != 0)
        .put("reliableWrite", false).put("writableAuxiliaries", false)

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    companion object {
        const val VERSION = 1

        /** Chrome: https, or http on loopback. Anything else (file:, http://example.com, about:blank) gets no Web Bluetooth. */
        fun isSecure(origin: String): Boolean {
            if (origin.startsWith("https://")) return true
            if (!origin.startsWith("http://")) return false
            val rest = origin.removePrefix("http://").substringBefore('/')
            val host = if (rest.startsWith("[")) rest.substringBefore(']') + "]" else rest.substringBefore(':')
            return host == "localhost" || host == "127.0.0.1" || host == "[::1]" || host.endsWith(".localhost")
        }

        /** The scheme + host + port of [url], the unit a grant belongs to; "" for anything that has none (about:, data:, file:). */
        fun originOf(url: String?): String {
            val u = url ?: return ""
            val m = Regex("^(https?)://([^/?#]+)").find(u) ?: return ""
            return m.groupValues[1] + "://" + m.groupValues[2].substringAfterLast('@').lowercase()
        }

        /** The opaque id the page sees: stable for (origin, device), unrelated across origins. */
        fun deviceId(origin: String, address: String): String =
            MessageDigest.getInstance("SHA-256").digest("$origin|$address".toByteArray()).take(16).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
