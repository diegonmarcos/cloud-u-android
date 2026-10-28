package com.diegonmarcos.superapp.browser

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

// #1225 Web Serial, WebUSB and Web NFC in Cloud Browser: the types the native core (WebHardwareCore) and its Android backends share.
// Everything here is plain Kotlin so the rules are JVM-tested (WebHardwareCoreTest) over test-double backends; the Android classes live in
// WebHardwareAndroid.kt. The pattern is Web Bluetooth's (WebBluetoothCore): a backend interface, per-origin grants over a KeyValue,
// a chooser interface, a core that holds the Chrome rules.

data class HwUsbEndpoint(val number: Int, val dirIn: Boolean, val type: String, val packetSize: Int)
data class HwUsbAlt(val setting: Int, val cls: Int, val sub: Int, val proto: Int, val endpoints: List<HwUsbEndpoint>)
data class HwUsbInterface(val number: Int, val alternates: List<HwUsbAlt>)
data class HwUsbConfig(val value: Int, val interfaces: List<HwUsbInterface>)

/** One attached USB device as the USB host API reports it. [key] is stable across a replug (vendor:product, plus the bus path only when two identical devices are attached). */
data class HwUsbDevice(
    val key: String, val vendorId: Int, val productId: Int, val cls: Int, val sub: Int, val proto: Int,
    val manufacturer: String?, val product: String?, val serial: String?, val configs: List<HwUsbConfig>,
) {
    val label: String get() = product ?: "USB device %04x:%04x".format(vendorId, productId)
    val alternates: List<HwUsbAlt> get() = configs.flatMap { c -> c.interfaces.flatMap { it.alternates } }
}

/** Result of one USB transfer: [status] is ok, stall, babble, or error (the transfer did not happen: gone, timed out, refused). */
class UsbTransfer(val status: String, val data: ByteArray = ByteArray(0), val written: Int = 0)

interface HwUsbListener { fun attached(d: HwUsbDevice); fun detached(key: String) }

/** The Android USB host API, as far as pages need it. Blocking calls are made off the main thread by the core. */
interface UsbBackend {
    val supported: Boolean
    fun observe(l: HwUsbListener)
    fun unobserve(l: HwUsbListener)
    fun attached(): List<HwUsbDevice>
    fun hasPermission(key: String): Boolean
    fun requestPermission(key: String, done: (Boolean) -> Unit)
    fun open(key: String): Boolean
    fun close(key: String)
    fun selectConfiguration(key: String, value: Int): Boolean
    fun claim(key: String, number: Int): Boolean
    fun release(key: String, number: Int): Boolean
    fun setAlternate(key: String, number: Int, setting: Int): Boolean
    fun control(key: String, requestType: Int, request: Int, value: Int, index: Int, data: ByteArray?, length: Int): UsbTransfer
    fun bulkIn(key: String, endpoint: Int, length: Int, timeoutMs: Int = 5000): UsbTransfer
    fun bulkOut(key: String, endpoint: Int, data: ByteArray, timeoutMs: Int = 5000): UsbTransfer
    fun reset(key: String): Boolean
}

class SerialOptions(val baudRate: Int, val dataBits: Int, val stopBits: Int, val parity: String, val flowControl: String)

interface HwSerialListener { fun data(key: String, bytes: ByteArray); fun closed(key: String) }

/** Serial ports: USB CDC ACM devices (see CdcAcmSerial). The failure of an operation is a message, null when it worked. */
interface SerialBackend {
    val supported: Boolean
    fun observe(l: HwSerialListener)
    fun unobserve(l: HwSerialListener)
    fun ports(): List<HwUsbDevice>
    fun open(key: String, o: SerialOptions): String?
    fun write(key: String, bytes: ByteArray): String?
    fun setSignals(key: String, dtr: Boolean?, rts: Boolean?, brk: Boolean?): String?
    fun close(key: String)
}

/** A record as Web NFC names it; [data] is the payload in its wire form (text bytes in [encoding], the URL's UTF-8, the MIME body). */
class NdefRec(val recordType: String, val mediaType: String?, val id: String?, val data: ByteArray, val encoding: String?, val lang: String?)

/** A record as NFC Forum names it (TNF, type, id, payload): what the radio reads and writes. */
class RawNdef(val tnf: Int, val type: ByteArray, val id: ByteArray, val payload: ByteArray)

interface NfcListener { fun tag(serial: String, records: List<NdefRec>); fun failed(message: String) }

/** Null [error] is success; otherwise a Web error name and the message for the page. */
class NfcWriteResult(val error: String?, val message: String?)

interface NfcBackend {
    val supported: Boolean
    val enabled: Boolean
    fun start(l: NfcListener): Boolean
    fun stop(l: NfcListener)
    fun writeNext(records: List<NdefRec>, overwrite: Boolean, done: (NfcWriteResult) -> Unit)
    fun cancelWrite()
}

/** NFC Forum NDEF <-> Web NFC records. Pure: the Android side only moves bytes and TNF ints. */
object NdefCodec {
    const val TNF_EMPTY = 0; const val TNF_WELL_KNOWN = 1; const val TNF_MIME = 2; const val TNF_ABSOLUTE_URI = 3; const val TNF_EXTERNAL = 4; const val TNF_UNKNOWN = 5
    private val URI_PREFIX = listOf("", "http://www.", "https://www.", "http://", "https://", "tel:", "mailto:", "ftp://anonymous:anonymous@", "ftp://ftp.",
        "ftps://", "sftp://", "smb://", "nfs://", "ftp://", "dav://", "news:", "telnet://", "imap:", "rtsp://", "urn:", "pop:", "sip:", "sips:", "tftp:",
        "btspp://", "btl2cap://", "btgoep://", "tcpobex://", "irdaobex://", "file://", "urn:epc:id:", "urn:epc:tag:", "urn:epc:pat:", "urn:epc:raw:", "urn:epc:", "urn:nfc:")

    fun toWeb(r: RawNdef): NdefRec {
        val id = if (r.id.isEmpty()) null else String(r.id, Charsets.UTF_8)
        val t = String(r.type, Charsets.US_ASCII)
        return when {
            r.tnf == TNF_EMPTY -> NdefRec("empty", null, id, ByteArray(0), null, null)
            r.tnf == TNF_WELL_KNOWN && t == "T" && r.payload.isNotEmpty() -> {
                val status = r.payload[0].toInt() and 0xFF
                val langLen = status and 0x3F
                if (1 + langLen > r.payload.size) NdefRec("unknown", null, id, r.payload, null, null)
                else NdefRec("text", null, id, r.payload.copyOfRange(1 + langLen, r.payload.size), if (status and 0x80 != 0) "utf-16" else "utf-8",
                    String(r.payload, 1, langLen, Charsets.US_ASCII))
            }
            r.tnf == TNF_WELL_KNOWN && t == "U" && r.payload.isNotEmpty() -> {
                val p = r.payload[0].toInt() and 0xFF
                val prefix = URI_PREFIX.getOrElse(p) { "" }
                NdefRec("url", null, id, (prefix + String(r.payload, 1, r.payload.size - 1, Charsets.UTF_8)).toByteArray(Charsets.UTF_8), null, null)
            }
            r.tnf == TNF_WELL_KNOWN -> NdefRec("smart-poster".takeIf { t == "Sp" } ?: "unknown", null, id, r.payload, null, null)
            r.tnf == TNF_MIME -> NdefRec("mime", t, id, r.payload, null, null)
            r.tnf == TNF_ABSOLUTE_URI -> NdefRec("absolute-url", null, id, r.type, null, null)
            r.tnf == TNF_EXTERNAL -> NdefRec(t, null, id, r.payload, null, null)
            else -> NdefRec("unknown", null, id, r.payload, null, null)
        }
    }

    /** The raw record for a record the page wants written, or a message when the page's record cannot be written. */
    fun toRaw(r: NdefRec): Pair<RawNdef?, String?> {
        val id = (r.id ?: "").toByteArray(Charsets.UTF_8)
        return when (r.recordType) {
            "empty" -> RawNdef(TNF_EMPTY, ByteArray(0), id, ByteArray(0)) to null
            "text" -> {
                if ((r.encoding ?: "utf-8") != "utf-8") return null to "Only utf-8 text records can be written."
                val lang = (r.lang ?: "en").toByteArray(Charsets.US_ASCII)
                if (lang.size > 63) return null to "The language tag is too long."
                RawNdef(TNF_WELL_KNOWN, "T".toByteArray(), id, byteArrayOf(lang.size.toByte()) + lang + r.data) to null
            }
            "url" -> RawNdef(TNF_WELL_KNOWN, "U".toByteArray(), id, byteArrayOf(0) + r.data) to null
            "absolute-url" -> RawNdef(TNF_ABSOLUTE_URI, r.data, id, ByteArray(0)) to null
            "mime" -> {
                val mt = r.mediaType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
                RawNdef(TNF_MIME, mt.lowercase().toByteArray(Charsets.US_ASCII), id, r.data) to null
            }
            "unknown" -> RawNdef(TNF_UNKNOWN, ByteArray(0), id, r.data) to null
            else -> if (Regex("^[a-z0-9.:_-]+$").matches(r.recordType) && ':' in r.recordType)
                RawNdef(TNF_EXTERNAL, r.recordType.toByteArray(Charsets.US_ASCII), id, r.data) to null
            else null to "Unsupported record type ${r.recordType}."
        }
    }
}

/** What a site was allowed: one device (or the NFC reader), by [key]. */
data class HwGrant(val key: String, val name: String?)

/**
 * Per-origin permission memory for one API (serial, usb, nfc), Web Bluetooth's model: made by the user in the native chooser or prompt,
 * belongs to ONE origin, survives restarts, lists in getPorts() / getDevices(), ends on forget(). One instance per API, each over its own
 * store (web_serial_grants, web_usb_grants, web_nfc_grants, declared in fleet-config.json).
 */
class WebHwGrants(private val kv: WebBleGrants.KeyValue, private val api: String) {
    fun forOrigin(origin: String): List<HwGrant> {
        val arr = runCatching { JSONArray(kv.get(origin) ?: return emptyList()) }.getOrNull() ?: return emptyList()
        return List(arr.length()) { i -> arr.getJSONObject(i).let { HwGrant(it.getString("key"), it.optString("name").ifEmpty { null }) } }
    }

    fun grant(origin: String, g: HwGrant) = write(origin, forOrigin(origin).filter { it.key != g.key } + g)
    fun find(origin: String, id: String): HwGrant? = forOrigin(origin).firstOrNull { idOf(origin, it.key) == id }
    fun has(origin: String, key: String): Boolean = forOrigin(origin).any { it.key == key }
    fun revoke(origin: String, key: String) = write(origin, forOrigin(origin).filter { it.key != key })
    fun clear(origin: String) = kv.put(origin, null)
    fun origins(): List<String> = kv.keys().filter { forOrigin(it).isNotEmpty() }.sorted()

    /** The opaque id the page sees for [key]: stable for (origin, api, device), unrelated across origins and APIs. */
    fun idOf(origin: String, key: String): String = WebBluetoothCore.deviceId(origin, "$api:$key")

    private fun write(origin: String, list: List<HwGrant>) {
        if (list.isEmpty()) { kv.put(origin, null); return }
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("key", it.key).put("name", it.name ?: "")) }
        kv.put(origin, arr.toString())
    }
}

class HwChoice(val key: String, val title: String, val subtitle: String)

/** The list a native chooser shows; [set] refreshes it live when a device is plugged or unplugged. */
class HwChoiceRequest(val origin: String, val kind: String) {
    private val list = CopyOnWriteArrayList<HwChoice>()
    @Volatile var onUpdate: (() -> Unit)? = null
    val items: List<HwChoice> get() = list.toList()
    fun set(items: List<HwChoice>) { list.clear(); list.addAll(items); onUpdate?.invoke() }
}

/** The native UI of the three APIs: a device chooser (USB, serial), a one-time NFC permission prompt, and the "hold a tag to the phone" panel. */
interface WebHwChooser {
    /** Exactly one of [onPick] (with the chosen key) / [onCancel]. */
    fun pick(request: HwChoiceRequest, onPick: (String) -> Unit, onCancel: () -> Unit)
    fun allowNfc(origin: String, writing: Boolean, onAllow: () -> Unit, onDeny: () -> Unit)
    /** Shows the tap panel; [onCancel] runs when the user dismisses it. Returns what dismisses the panel. */
    fun tapTag(origin: String, onCancel: () -> Unit): () -> Unit
}
