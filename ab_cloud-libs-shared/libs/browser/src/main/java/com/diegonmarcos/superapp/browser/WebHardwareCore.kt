package com.diegonmarcos.superapp.browser

import com.diegonmarcos.superapp.bluetooth.Hex
import org.json.JSONArray
import org.json.JSONObject

/**
 * Web Serial, WebUSB and Web NFC for one browser tab (#1225): the native half of web_hardware.js. [handle] takes one request from the
 * page (wire: assets/browser/web-hardware-contract.json) with the ORIGIN the host read from the WebView, and answers or emits through
 * [send]. Web Bluetooth's rules (WebBluetoothCore) carry over: a secure origin, a user gesture for requestDevice / requestPort and for the
 * first NFC permission, a native chooser or prompt the user must act on, grants scoped to the origin (one store per API), a page reaches
 * only what it was granted, and leaving the page closes what it opened ([reset]). Blocking USB transfers run on [io].
 * WebUSB also keeps Chrome's protected-class rule (HID, audio, video, mass storage, smart card and wireless-controller interfaces
 * cannot be claimed and such devices are not offered), and a port that Web Serial holds is not claimable by WebUSB.
 */
class WebHardwareCore(
    private val usb: UsbBackend,
    private val serial: SerialBackend,
    private val nfc: NfcBackend,
    private val serialGrants: WebHwGrants,
    private val usbGrants: WebHwGrants,
    private val nfcGrants: WebHwGrants,
    private val chooser: WebHwChooser,
    private val permissions: UsbPermission,
    private val io: (() -> Unit) -> Unit,
    private val send: (String) -> Unit,
) : HwUsbListener, HwSerialListener, NfcListener {

    /** Android's own USB permission (the system dialog), per device. */
    interface UsbPermission { fun ensure(key: String, done: (Boolean) -> Unit) }

    private class UsbState(val id: String) { var config: Int? = null; val claimed = HashSet<Int>(); val alt = HashMap<Int, Int>() }

    @Volatile private var origin = ""
    private val serialOpen = HashMap<String, String>()          // key -> the port id the page knows
    private val usbOpen = HashMap<String, UsbState>()           // key -> state of the page's open device
    private var scanning = false
    private var dismissTap: (() -> Unit)? = null
    private var openRequest: HwChoiceRequest? = null
    private var openCandidates: () -> List<HwUsbDevice> = { emptyList() }
    private val lock = Any()

    init { usb.observe(this); serial.observe(this) }

    fun close() { reset(); usb.unobserve(this); serial.unobserve(this) }

    // ---- entry ----

    fun handle(origin: String, text: String) {
        val m = runCatching { JSONObject(text) }.getOrNull() ?: return
        val id = m.optInt("id", -1)
        if (id < 0 || m.optInt("v") != VERSION) return
        val op = m.optString("op")
        val args = m.optJSONObject("args") ?: JSONObject()
        this.origin = origin
        if (!WebBluetoothCore.isSecure(origin)) { fail(id, "SecurityError", "Web Serial, WebUSB and Web NFC require a secure context (https or localhost)."); return }
        val gesture = m.optBoolean("gesture", false)
        runCatching {
            when (op) {
                "serial.getPorts" -> reply(id, JSONObject().put("ports", JSONArray().also { a ->
                    serialGrants.forOrigin(origin).forEach { g -> serial.ports().firstOrNull { it.key == g.key }?.let { a.put(serialInfo(it)) } } }))
                "serial.requestPort" -> requestSerial(id, args, gesture)
                "serial.open" -> withSerial(id, args) { d -> openSerial(id, d, args) }
                "serial.write" -> withOpenSerial(id, args) { d -> writeSerial(id, d, args) }
                "serial.close" -> withSerialGrant(id, args) { d -> closeSerial(d.key); reply(id, JSONObject()) }
                "serial.setSignals" -> withOpenSerial(id, args) { d -> setSignals(id, d, args) }
                "serial.forget" -> withSerialGrant(id, args) { g -> closeSerial(g.key); serialGrants.revoke(origin, g.key); reply(id, JSONObject()) }

                "usb.getDevices" -> reply(id, JSONObject().put("devices", JSONArray().also { a ->
                    usbGrants.forOrigin(origin).forEach { g -> usb.attached().firstOrNull { it.key == g.key }?.let { a.put(usbInfo(it)) } } }))
                "usb.requestDevice" -> requestUsb(id, args, gesture)
                "usb.forget" -> withUsbGrant(id, args) { g -> closeUsb(g.key); usbGrants.revoke(origin, g.key); reply(id, JSONObject()) }
                "usb.open" -> withUsb(id, args) { d -> openUsb(id, d) }
                "usb.close" -> withUsbGrant(id, args) { d -> closeUsb(d.key); reply(id, JSONObject()) }
                "usb.selectConfiguration" -> withOpenUsb(id, args) { d, s -> selectConfiguration(id, d, s, args) }
                "usb.claimInterface" -> withOpenUsb(id, args) { d, s -> claim(id, d, s, args) }
                "usb.releaseInterface" -> withOpenUsb(id, args) { d, s -> release(id, d, s, args) }
                "usb.selectAlternateInterface" -> withOpenUsb(id, args) { d, s -> selectAlternate(id, d, s, args) }
                "usb.controlTransferIn" -> withOpenUsb(id, args) { d, s -> control(id, d, s, args, true) }
                "usb.controlTransferOut" -> withOpenUsb(id, args) { d, s -> control(id, d, s, args, false) }
                "usb.transferIn" -> withOpenUsb(id, args) { d, s -> bulk(id, d, s, args, true) }
                "usb.transferOut" -> withOpenUsb(id, args) { d, s -> bulk(id, d, s, args, false) }
                "usb.clearHalt" -> withOpenUsb(id, args) { d, s -> clearHalt(id, d, s, args) }
                "usb.reset" -> withOpenUsb(id, args) { _, _ -> fail(id, "NotSupportedError", "Resetting a device is not available through Android's USB host API.") }

                "nfc.scan" -> nfcScan(id, gesture)
                "nfc.stop" -> { stopScan(); reply(id, JSONObject()) }
                "nfc.write" -> nfcWrite(id, args, gesture)
                else -> fail(id, "NotSupportedError", "Unknown operation $op.")
            }
        }.onFailure { fail(id, "NetworkError", it.message ?: it.javaClass.simpleName) }
    }

    /** The page went away or navigated: close what it opened, stop what it started, like a closed tab. */
    fun reset() {
        val ports = synchronized(lock) { serialOpen.keys.toList().also { serialOpen.clear() } }
        val devs = synchronized(lock) { usbOpen.keys.toList().also { usbOpen.clear() } }
        ports.forEach { serial.close(it) }
        devs.forEach { usb.close(it) }
        stopScan()
        dismissTap?.invoke(); dismissTap = null
        nfc.cancelWrite()
        origin = ""
    }

    // ---- shared: choosing a device ----

    private fun offerDevices(id: Int, kind: String, grants: WebHwGrants, candidates: () -> List<HwUsbDevice>, gesture: Boolean) {
        if (!gesture) { fail(id, "SecurityError", "Must be handling a user gesture to show a permission request."); return }
        if (!usb.supported) { fail(id, "NotFoundError", "This device has no USB host."); return }
        val o = origin
        val req = HwChoiceRequest(o, kind)
        fun refresh() = req.set(candidates().map { HwChoice(it.key, it.label, "%04x:%04x".format(it.vendorId, it.productId)) })
        refresh()
        synchronized(lock) { openRequest = req; openCandidates = candidates }
        chooser.pick(req, { key ->
            synchronized(lock) { if (openRequest === req) openRequest = null }
            val d = candidates().firstOrNull { it.key == key }
            if (d == null) { fail(id, "NotFoundError", "The device was disconnected."); return@pick }
            permissions.ensure(d.key) { ok ->
                if (!ok) { fail(id, "NotAllowedError", "Android's USB permission was denied."); return@ensure }
                grants.grant(o, HwGrant(d.key, d.product))
                reply(id, JSONObject().put(if (kind == "serial") "port" else "device", if (kind == "serial") serialInfo(d) else usbInfo(d)))
            }
        }, {
            synchronized(lock) { if (openRequest === req) openRequest = null }
            fail(id, "NotFoundError", if (kind == "serial") "No port selected by the user." else "No device selected.")
        })
    }

    override fun attached(d: HwUsbDevice) {
        refreshChooser()
        val o = origin
        if (o.isEmpty()) return
        if (usbGrants.has(o, d.key)) send(JSONObject().put("v", VERSION).put("event", "usb.connect").put("device", usbInfo(d)).toString())
    }

    override fun detached(key: String) {
        refreshChooser()
        val o = origin
        val wasOpen = synchronized(lock) { usbOpen.remove(key) != null }
        if (o.isEmpty()) return
        if (usbGrants.has(o, key) || wasOpen) send(JSONObject().put("v", VERSION).put("event", "usb.disconnect").put("device", usbGrants.idOf(o, key)).toString())
        if (serialGrants.has(o, key)) {
            synchronized(lock) { serialOpen.remove(key) }
            send(JSONObject().put("v", VERSION).put("event", "serial.disconnect").put("port", serialGrants.idOf(o, key)).toString())
        }
    }

    private fun refreshChooser() {
        val (req, candidates) = synchronized(lock) { openRequest to openCandidates }
        req ?: return
        req.set(candidates().map { HwChoice(it.key, it.label, "%04x:%04x".format(it.vendorId, it.productId)) })
    }

    // ---- Web Serial ----

    private fun requestSerial(id: Int, args: JSONObject, gesture: Boolean) {
        val fa = args.optJSONArray("filters")
        val filters = List(fa?.length() ?: 0) { fa!!.getJSONObject(it) }
        if (filters.any { !it.has("usbVendorId") }) { fail(id, "TypeError", "A serial port filter needs usbVendorId."); return }
        offerDevices(id, "serial", serialGrants, {
            serial.ports().filter { d -> filters.isEmpty() || filters.any { f -> f.getInt("usbVendorId") == d.vendorId && (!f.has("usbProductId") || f.getInt("usbProductId") == d.productId) } }
        }, gesture)
    }

    private fun withSerialGrant(id: Int, args: JSONObject, block: (HwGrant) -> Unit) {
        val g = serialGrants.find(origin, args.optString("port"))
        if (g == null) fail(id, "NotAllowedError", "This origin has no permission for that port.") else block(g)
    }

    private fun withSerial(id: Int, args: JSONObject, block: (HwUsbDevice) -> Unit) = withSerialGrant(id, args) { g ->
        val d = serial.ports().firstOrNull { it.key == g.key }
        if (d == null) fail(id, "NetworkError", "The device has been lost.") else block(d)
    }

    private fun withOpenSerial(id: Int, args: JSONObject, block: (HwUsbDevice) -> Unit) = withSerial(id, args) { d ->
        if (synchronized(lock) { d.key !in serialOpen }) fail(id, "InvalidStateError", "The port is not open.") else block(d)
    }

    private fun openSerial(id: Int, d: HwUsbDevice, args: JSONObject) {
        if (synchronized(lock) { d.key in serialOpen }) { fail(id, "InvalidStateError", "The port is already open."); return }
        if (synchronized(lock) { d.key in usbOpen }) { fail(id, "InvalidStateError", "WebUSB holds this device."); return }
        val baud = args.optInt("baudRate", 0)
        if (baud <= 0) { fail(id, "TypeError", "baudRate must be a positive number."); return }
        val dataBits = args.optInt("dataBits", 8)
        val stopBits = args.optInt("stopBits", 1)
        if (dataBits != 7 && dataBits != 8) { fail(id, "TypeError", "dataBits must be 7 or 8."); return }
        if (stopBits != 1 && stopBits != 2) { fail(id, "TypeError", "stopBits must be 1 or 2."); return }
        val opts = SerialOptions(baud, dataBits, stopBits, args.optString("parity", "none"), args.optString("flowControl", "none"))
        permissions.ensure(d.key) { ok ->
            if (!ok) { fail(id, "NotAllowedError", "Android's USB permission was denied."); return@ensure }
            io {
                val err = serial.open(d.key, opts)
                if (err != null) fail(id, "NetworkError", "Failed to open serial port. $err")
                else { synchronized(lock) { serialOpen[d.key] = serialGrants.idOf(origin, d.key) }; reply(id, JSONObject()) }
            }
        }
    }

    private fun writeSerial(id: Int, d: HwUsbDevice, args: JSONObject) {
        val raw = args.optString("value")
        val bytes = (if (raw.isEmpty()) ByteArray(0) else Hex.parse(raw)) ?: run { fail(id, "TypeError", "The value is not hex."); return }
        io { val err = serial.write(d.key, bytes); if (err == null) reply(id, JSONObject()) else fail(id, "NetworkError", err) }
    }

    private fun setSignals(id: Int, d: HwUsbDevice, args: JSONObject) {
        fun opt(k: String) = if (args.has(k)) args.getBoolean(k) else null
        io { val err = serial.setSignals(d.key, opt("dataTerminalReady"), opt("requestToSend"), opt("break")); if (err == null) reply(id, JSONObject()) else fail(id, "NetworkError", err) }
    }

    private fun closeSerial(key: String) {
        synchronized(lock) { serialOpen.remove(key) }
        serial.close(key)
    }

    override fun data(key: String, bytes: ByteArray) {
        val port = synchronized(lock) { serialOpen[key] } ?: return
        send(JSONObject().put("v", VERSION).put("event", "serial.data").put("port", port).put("value", hex(bytes)).toString())
    }

    override fun closed(key: String) {
        val port = synchronized(lock) { serialOpen.remove(key) } ?: return
        send(JSONObject().put("v", VERSION).put("event", "serial.disconnect").put("port", port).toString())
    }

    // ---- WebUSB ----

    private fun requestUsb(id: Int, args: JSONObject, gesture: Boolean) {
        val fa = args.optJSONArray("filters") ?: run { fail(id, "TypeError", "requestDevice needs a filters array."); return }
        val filters = List(fa.length()) { fa.getJSONObject(it) }
        offerDevices(id, "usb", usbGrants, {
            usb.attached().filter { d -> !onlyProtected(d) && (filters.isEmpty() || filters.any { matches(it, d) }) }
        }, gesture)
    }

    private fun matches(f: JSONObject, d: HwUsbDevice): Boolean {
        if (f.has("vendorId") && f.getInt("vendorId") != d.vendorId) return false
        if (f.has("productId") && f.getInt("productId") != d.productId) return false
        if (f.has("serialNumber") && f.getString("serialNumber") != d.serial) return false
        if (f.has("classCode") || f.has("subclassCode") || f.has("protocolCode")) {
            val cls = f.optInt("classCode", -1); val sub = f.optInt("subclassCode", -1); val proto = f.optInt("protocolCode", -1)
            fun ok(c: Int, s: Int, p: Int) = (cls < 0 || cls == c) && (sub < 0 || sub == s) && (proto < 0 || proto == p)
            if (!(ok(d.cls, d.sub, d.proto) || d.alternates.any { ok(it.cls, it.sub, it.proto) })) return false
        }
        return true
    }

    private fun withUsbGrant(id: Int, args: JSONObject, block: (HwGrant) -> Unit) {
        val g = usbGrants.find(origin, args.optString("device"))
        if (g == null) fail(id, "NotAllowedError", "This origin has no permission for that device.") else block(g)
    }

    private fun withUsb(id: Int, args: JSONObject, block: (HwUsbDevice) -> Unit) = withUsbGrant(id, args) { g ->
        val d = usb.attached().firstOrNull { it.key == g.key }
        if (d == null) fail(id, "NetworkError", "The device was disconnected.") else block(d)
    }

    private fun withOpenUsb(id: Int, args: JSONObject, block: (HwUsbDevice, UsbState) -> Unit) = withUsb(id, args) { d ->
        val s = synchronized(lock) { usbOpen[d.key] }
        if (s == null) fail(id, "InvalidStateError", "The device must be opened first.") else block(d, s)
    }

    private fun openUsb(id: Int, d: HwUsbDevice) {
        if (synchronized(lock) { d.key in serialOpen }) { fail(id, "InvalidStateError", "Web Serial holds this device."); return }
        if (synchronized(lock) { d.key in usbOpen }) { reply(id, JSONObject()); return }
        permissions.ensure(d.key) { ok ->
            if (!ok) { fail(id, "NotAllowedError", "Android's USB permission was denied."); return@ensure }
            io {
                if (!usb.open(d.key)) fail(id, "NetworkError", "Access denied.")
                else { synchronized(lock) { usbOpen[d.key] = UsbState(usbGrants.idOf(origin, d.key)) }; reply(id, JSONObject()) }
            }
        }
    }

    private fun closeUsb(key: String) {
        if (synchronized(lock) { usbOpen.remove(key) } != null) usb.close(key)
    }

    private fun selectConfiguration(id: Int, d: HwUsbDevice, s: UsbState, args: JSONObject) {
        val v = args.optInt("configurationValue", -1)
        if (d.configs.none { it.value == v }) { fail(id, "NotFoundError", "The configuration index is out of range."); return }
        if (s.claimed.isNotEmpty()) { fail(id, "InvalidStateError", "Release the claimed interfaces first."); return }
        io { if (usb.selectConfiguration(d.key, v)) { s.config = v; reply(id, JSONObject()) } else fail(id, "NetworkError", "Unable to set device configuration.") }
    }

    private fun currentConfig(d: HwUsbDevice, s: UsbState) = d.configs.firstOrNull { it.value == s.config } ?: d.configs.firstOrNull()

    private fun claim(id: Int, d: HwUsbDevice, s: UsbState, args: JSONObject) {
        val n = args.optInt("interfaceNumber", -1)
        val iface = currentConfig(d, s)?.interfaces?.firstOrNull { it.number == n } ?: run { fail(id, "NotFoundError", "The interface number provided is not supported by the device in its current configuration."); return }
        if (iface.alternates.any { it.cls in PROTECTED_CLASSES }) { fail(id, "SecurityError", "The requested interface implements a protected class."); return }
        if (n in s.claimed) { reply(id, JSONObject()); return }
        io { if (usb.claim(d.key, n)) { s.claimed += n; s.alt[n] = 0; reply(id, JSONObject()) } else fail(id, "NetworkError", "Unable to claim interface.") }
    }

    private fun release(id: Int, d: HwUsbDevice, s: UsbState, args: JSONObject) {
        val n = args.optInt("interfaceNumber", -1)
        if (n !in s.claimed) { fail(id, "InvalidStateError", "The interface is not claimed."); return }
        io { if (usb.release(d.key, n)) { s.claimed -= n; s.alt.remove(n); reply(id, JSONObject()) } else fail(id, "NetworkError", "Unable to release interface.") }
    }

    private fun selectAlternate(id: Int, d: HwUsbDevice, s: UsbState, args: JSONObject) {
        val n = args.optInt("interfaceNumber", -1); val a = args.optInt("alternateSetting", -1)
        if (n !in s.claimed) { fail(id, "InvalidStateError", "The interface must be claimed first."); return }
        if (currentConfig(d, s)?.interfaces?.firstOrNull { it.number == n }?.alternates?.none { it.setting == a } != false) { fail(id, "NotFoundError", "The alternate setting provided is not supported by the device."); return }
        io { if (usb.setAlternate(d.key, n, a)) { s.alt[n] = a; reply(id, JSONObject()) } else fail(id, "NetworkError", "Unable to set device interface.") }
    }

    private fun endpointOf(d: HwUsbDevice, s: UsbState, number: Int, dirIn: Boolean): HwUsbEndpoint? =
        currentConfig(d, s)?.interfaces?.filter { it.number in s.claimed }?.flatMap { i -> i.alternates.filter { it.setting == (s.alt[i.number] ?: 0) } }
            ?.flatMap { it.endpoints }?.firstOrNull { it.number == number && it.dirIn == dirIn }

    private fun control(id: Int, d: HwUsbDevice, s: UsbState, args: JSONObject, dirIn: Boolean) {
        val setup = args.optJSONObject("setup") ?: run { fail(id, "TypeError", "setup is required."); return }
        val type = when (setup.optString("requestType")) { "standard" -> 0x00; "class" -> 0x20; "vendor" -> 0x40; else -> { fail(id, "TypeError", "Invalid requestType."); return } }
        val recipient = when (setup.optString("recipient")) { "device" -> 0; "interface" -> 1; "endpoint" -> 2; "other" -> 3; else -> { fail(id, "TypeError", "Invalid recipient."); return } }
        val index = setup.optInt("index", 0)
        if (recipient == 1 && (index and 0xFF) !in s.claimed) { fail(id, "NotFoundError", "The interface is not claimed."); return }
        if (recipient == 2) {
            val ep = index and 0x0F; val epIn = index and 0x80 != 0
            if (endpointOf(d, s, ep, epIn) == null) { fail(id, "NotFoundError", "The endpoint is not part of a claimed and selected alternate interface."); return }
        }
        val bm = (if (dirIn) 0x80 else 0x00) or type or recipient
        val data = if (dirIn) null else Hex.parse(args.optString("data")) ?: if (args.optString("data").isEmpty()) ByteArray(0) else run { fail(id, "TypeError", "The data is not hex."); return }
        val length = if (dirIn) args.optInt("length", -1) else data!!.size
        if (length < 0 || length > MAX_TRANSFER) { fail(id, "TypeError", "Invalid transfer length."); return }
        io {
            val r = usb.control(d.key, bm, setup.optInt("request", 0), setup.optInt("value", 0), index, data, length)
            transferReply(id, r, dirIn)
        }
    }

    private fun bulk(id: Int, d: HwUsbDevice, s: UsbState, args: JSONObject, dirIn: Boolean) {
        val n = args.optInt("endpointNumber", -1)
        val ep = endpointOf(d, s, n, dirIn) ?: run { fail(id, "NotFoundError", "The specified endpoint is not part of a claimed and selected alternate interface."); return }
        if (ep.type != "bulk" && ep.type != "interrupt") { fail(id, "NotSupportedError", "Only bulk and interrupt endpoints are available through Android's USB host API."); return }
        if (dirIn) {
            val length = args.optInt("length", -1)
            if (length < 0 || length > MAX_TRANSFER) { fail(id, "TypeError", "Invalid transfer length."); return }
            io { transferReply(id, usb.bulkIn(d.key, n, length), true) }
        } else {
            val data = (if (args.optString("data").isEmpty()) ByteArray(0) else Hex.parse(args.optString("data"))) ?: run { fail(id, "TypeError", "The data is not hex."); return }
            io { transferReply(id, usb.bulkOut(d.key, n, data), false) }
        }
    }

    private fun clearHalt(id: Int, d: HwUsbDevice, s: UsbState, args: JSONObject) {
        val n = args.optInt("endpointNumber", -1); val dirIn = args.optString("direction") == "in"
        if (endpointOf(d, s, n, dirIn) == null) { fail(id, "NotFoundError", "The endpoint is not part of a claimed and selected alternate interface."); return }
        val addr = n or (if (dirIn) 0x80 else 0)
        io {
            // CLEAR_FEATURE(ENDPOINT_HALT) to the endpoint: the standard way, Android has no call of its own.
            val r = usb.control(d.key, 0x02, 0x01, 0, addr, null, 0)
            if (r.status == "ok") reply(id, JSONObject()) else fail(id, "NetworkError", "Unable to clear the endpoint's halt.")
        }
    }

    private fun transferReply(id: Int, r: UsbTransfer, dirIn: Boolean) {
        if (r.status == "error") { fail(id, "NetworkError", "A transfer error has occurred."); return }
        reply(id, JSONObject().put("status", r.status).apply { if (dirIn) put("data", hex(r.data)) else put("bytesWritten", r.written) })
    }

    // ---- Web NFC ----

    private fun nfcReady(id: Int, gesture: Boolean, writing: Boolean, then: () -> Unit) {
        if (!nfc.supported) { fail(id, "NotSupportedError", "This device has no NFC."); return }
        if (!nfc.enabled) { fail(id, "NotReadableError", "NFC is switched off."); return }
        val o = origin
        if (nfcGrants.has(o, NFC_KEY)) { then(); return }
        if (!gesture) { fail(id, "NotAllowedError", "NFC permission has not been granted and a prompt needs a user gesture."); return }
        chooser.allowNfc(o, writing, {
            nfcGrants.grant(o, HwGrant(NFC_KEY, "NFC"))
            then()
        }, { fail(id, "NotAllowedError", "NFC permission request denied.") })
    }

    private fun nfcScan(id: Int, gesture: Boolean) = nfcReady(id, gesture, false) {
        if (scanning) { reply(id, JSONObject()); return@nfcReady }
        if (!nfc.start(this)) { fail(id, "NotReadableError", "NFC is in use by another page."); return@nfcReady }
        scanning = true
        reply(id, JSONObject())
    }

    private fun stopScan() { if (scanning) { scanning = false; nfc.stop(this) } }

    private fun nfcWrite(id: Int, args: JSONObject, gesture: Boolean) {
        val ra = args.optJSONArray("records") ?: run { fail(id, "TypeError", "records is required."); return }
        val recs = ArrayList<NdefRec>()
        for (i in 0 until ra.length()) {
            val r = ra.getJSONObject(i)
            val data = Hex.parse(r.optString("data")) ?: if (r.optString("data").isEmpty()) ByteArray(0) else run { fail(id, "TypeError", "A record's data is not hex."); return }
            recs += NdefRec(r.optString("recordType"), if (r.has("mediaType")) r.getString("mediaType") else null, if (r.has("id")) r.getString("id") else null, data,
                if (r.has("encoding")) r.getString("encoding") else null, if (r.has("lang")) r.getString("lang") else null)
            val bad = NdefCodec.toRaw(recs.last()).second
            if (bad != null) { fail(id, "NotSupportedError", bad); return }
        }
        val overwrite = args.optBoolean("overwrite", true)
        nfcReady(id, gesture, true) {
            var finished = false
            val dismiss = chooser.tapTag(origin) {
                if (!finished) { finished = true; nfc.cancelWrite(); fail(id, "AbortError", "The write was cancelled.") }
            }
            dismissTap = dismiss
            nfc.writeNext(recs, overwrite) { res ->
                if (finished) return@writeNext
                finished = true
                dismissTap = null
                dismiss()
                if (res.error == null) reply(id, JSONObject()) else fail(id, res.error, res.message ?: "The write failed.")
            }
        }
    }

    override fun tag(serial: String, records: List<NdefRec>) {
        if (!scanning) return
        send(JSONObject().put("v", VERSION).put("event", "nfc.reading").put("message", JSONObject().put("serialNumber", serial).put("records", recordsJson(records))).toString())
    }

    override fun failed(message: String) {
        if (scanning) send(JSONObject().put("v", VERSION).put("event", "nfc.readingerror").toString())
    }

    // ---- wire ----

    private fun reply(id: Int, result: JSONObject) = send(JSONObject().put("v", VERSION).put("id", id).put("ok", true).put("result", result).toString())

    private fun fail(id: Int, name: String, message: String) =
        send(JSONObject().put("v", VERSION).put("id", id).put("ok", false).put("error", JSONObject().put("name", name).put("message", message)).toString())

    private fun serialInfo(d: HwUsbDevice) = JSONObject().put("id", serialGrants.idOf(origin, d.key)).put("usbVendorId", d.vendorId).put("usbProductId", d.productId)

    private fun usbInfo(d: HwUsbDevice): JSONObject {
        val o = JSONObject().put("id", usbGrants.idOf(origin, d.key)).put("vendorId", d.vendorId).put("productId", d.productId)
            .put("deviceClass", d.cls).put("deviceSubclass", d.sub).put("deviceProtocol", d.proto)
        d.manufacturer?.let { o.put("manufacturerName", it) }; d.product?.let { o.put("productName", it) }; d.serial?.let { o.put("serialNumber", it) }
        o.put("configurations", JSONArray().also { cs -> d.configs.forEach { c ->
            cs.put(JSONObject().put("configurationValue", c.value).put("interfaces", JSONArray().also { ia -> c.interfaces.forEach { i ->
                ia.put(JSONObject().put("interfaceNumber", i.number).put("alternates", JSONArray().also { aa -> i.alternates.forEach { a ->
                    aa.put(JSONObject().put("alternateSetting", a.setting).put("interfaceClass", a.cls).put("interfaceSubclass", a.sub).put("interfaceProtocol", a.proto)
                        .put("endpoints", JSONArray().also { ea -> a.endpoints.forEach { e ->
                            ea.put(JSONObject().put("endpointNumber", e.number).put("direction", if (e.dirIn) "in" else "out").put("type", e.type).put("packetSize", e.packetSize))
                        } }))
                } }))
            } }))
        } })
        return o
    }

    private fun recordsJson(records: List<NdefRec>) = JSONArray().also { a -> records.forEach { r ->
        a.put(JSONObject().put("recordType", r.recordType).put("data", hex(r.data)).apply {
            r.mediaType?.let { put("mediaType", it) }; r.id?.let { put("id", it) }; r.encoding?.let { put("encoding", it) }; r.lang?.let { put("lang", it) }
        })
    } }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    companion object {
        const val VERSION = 1
        const val NFC_KEY = "nfc"
        const val MAX_TRANSFER = 1 shl 20
        /** Chrome's WebUSB protected classes: audio, HID, mass storage, smart card, video, audio/video, wireless controller. */
        val PROTECTED_CLASSES = setOf(0x01, 0x03, 0x08, 0x0B, 0x0E, 0x10, 0xE0)

        /** A device whose every interface is protected has nothing a page may claim, so the chooser does not offer it (Chrome does the same). */
        fun onlyProtected(d: HwUsbDevice): Boolean {
            val alts = d.configs.flatMap { c -> c.interfaces.map { i -> i.alternates } }.filter { it.isNotEmpty() }
            return alts.isNotEmpty() && alts.all { it.all { a -> a.cls in PROTECTED_CLASSES } }
        }
    }
}
