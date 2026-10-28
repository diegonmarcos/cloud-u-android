package com.diegonmarcos.superapp.browser

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The real USB host behind WebUSB and Web Serial (#1225): Android's UsbManager. Nothing here invents a device; with no USB host, or
 * nothing plugged in, the lists are empty and the page is told so. Per device it holds one UsbDeviceConnection while a page has it open.
 * A device key is vendor:product, plus "@bus path" only while two identical devices are attached, so a page's grant survives a replug.
 */
class AndroidUsbBackend(private val ctx: Context) : UsbBackend {
    private val mgr = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager
    private val observers = CopyOnWriteArrayList<HwUsbListener>()
    private val conns = ConcurrentHashMap<String, UsbDeviceConnection>()
    private val waiting = ConcurrentHashMap<String, MutableList<(Boolean) -> Unit>>()
    @Volatile private var known: Map<String, UsbDevice> = emptyMap()
    @Volatile private var registered = false

    override val supported: Boolean get() = mgr != null && ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)

    private val receiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(c: Context, i: Intent) {
            val dev = i.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
            when (i.action) {
                ACTION_PERMISSION -> {
                    val key = known.entries.firstOrNull { it.value.deviceName == dev.deviceName }?.key ?: return
                    val granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    waiting.remove(key)?.forEach { it(granted) }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    known = snapshot()
                    val key = known.entries.firstOrNull { it.value.deviceName == dev.deviceName }?.key ?: return
                    observers.forEach { it.attached(model(key, dev)) }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val key = known.entries.firstOrNull { it.value.deviceName == dev.deviceName }?.key
                    known = snapshot()
                    if (key != null) { conns.remove(key)?.close(); observers.forEach { it.detached(key) } }
                }
            }
        }
    }

    private fun ensureRegistered() {
        if (registered) return
        synchronized(this) {
            if (registered) return
            known = snapshot()
            ContextCompat.registerReceiver(ctx.applicationContext, receiver, IntentFilter(ACTION_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
            ContextCompat.registerReceiver(ctx.applicationContext, receiver,
                IntentFilter().apply { addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }, ContextCompat.RECEIVER_EXPORTED)
            registered = true
        }
    }

    override fun observe(l: HwUsbListener) { ensureRegistered(); observers.addIfAbsent(l) }
    override fun unobserve(l: HwUsbListener) { observers.remove(l) }

    private fun snapshot(): Map<String, UsbDevice> {
        val all = mgr?.deviceList?.values?.toList() ?: return emptyMap()
        val twins = all.groupBy { it.vendorId to it.productId }
        return all.associateBy { d ->
            val base = "%04x:%04x".format(d.vendorId, d.productId)
            if ((twins[d.vendorId to d.productId]?.size ?: 1) > 1) "$base@${d.deviceName}" else base
        }
    }

    private fun device(key: String): UsbDevice? { known = snapshot(); return known[key] }

    override fun attached(): List<HwUsbDevice> { ensureRegistered(); known = snapshot(); return known.map { (k, d) -> model(k, d) } }

    private fun model(key: String, d: UsbDevice): HwUsbDevice {
        val permitted = mgr?.hasPermission(d) == true
        val configs = (0 until d.configurationCount).map { ci ->
            val cfg = d.getConfiguration(ci)
            val ifaces = (0 until cfg.interfaceCount).map { cfg.getInterface(it) }
            HwUsbConfig(cfg.id, ifaces.groupBy { it.id }.map { (num, alts) ->
                HwUsbInterface(num, alts.sortedBy { it.alternateSetting }.map { a ->
                    HwUsbAlt(a.alternateSetting, a.interfaceClass, a.interfaceSubclass, a.interfaceProtocol, (0 until a.endpointCount).map { e ->
                        val ep = a.getEndpoint(e)
                        HwUsbEndpoint(ep.endpointNumber, ep.direction == UsbConstants.USB_DIR_IN, when (ep.type) {
                            UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"; UsbConstants.USB_ENDPOINT_XFER_INT -> "interrupt"
                            UsbConstants.USB_ENDPOINT_XFER_ISOC -> "isochronous"; else -> "control" }, ep.maxPacketSize)
                    })
                })
            })
        }
        return HwUsbDevice(key, d.vendorId, d.productId, d.deviceClass, d.deviceSubclass, d.deviceProtocol,
            runCatching { d.manufacturerName }.getOrNull(), runCatching { d.productName }.getOrNull(),
            if (permitted) runCatching { d.serialNumber }.getOrNull() else null, configs)
    }

    override fun hasPermission(key: String): Boolean = device(key)?.let { mgr?.hasPermission(it) } == true

    override fun requestPermission(key: String, done: (Boolean) -> Unit) {
        ensureRegistered()
        val d = device(key) ?: run { done(false); return }
        if (mgr?.hasPermission(d) == true) { done(true); return }
        waiting.getOrPut(key) { CopyOnWriteArrayList() }.add(done)
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0   // the system fills the permission extras in
        val pi = PendingIntent.getBroadcast(ctx, 0, Intent(ACTION_PERMISSION).setPackage(ctx.packageName), flags)
        mgr?.requestPermission(d, pi)
    }

    override fun open(key: String): Boolean {
        if (conns.containsKey(key)) return true
        val d = device(key) ?: return false
        val c = mgr?.openDevice(d) ?: return false
        conns[key] = c
        return true
    }

    override fun close(key: String) { conns.remove(key)?.close() }

    private fun iface(d: UsbDevice, number: Int, alt: Int): UsbInterface? =
        (0 until d.interfaceCount).map { d.getInterface(it) }.firstOrNull { it.id == number && it.alternateSetting == alt }

    override fun selectConfiguration(key: String, value: Int): Boolean {
        val d = device(key) ?: return false; val c = conns[key] ?: return false
        val cfg = (0 until d.configurationCount).map { d.getConfiguration(it) }.firstOrNull { it.id == value } ?: return false
        return c.setConfiguration(cfg)
    }

    override fun claim(key: String, number: Int): Boolean {
        val d = device(key) ?: return false; val c = conns[key] ?: return false
        return c.claimInterface(iface(d, number, 0) ?: return false, true)
    }

    override fun release(key: String, number: Int): Boolean {
        val d = device(key) ?: return false; val c = conns[key] ?: return false
        return c.releaseInterface(iface(d, number, 0) ?: return false)
    }

    override fun setAlternate(key: String, number: Int, setting: Int): Boolean {
        val d = device(key) ?: return false; val c = conns[key] ?: return false
        return c.setInterface(iface(d, number, setting) ?: return false)
    }

    override fun control(key: String, requestType: Int, request: Int, value: Int, index: Int, data: ByteArray?, length: Int): UsbTransfer {
        val c = conns[key] ?: return UsbTransfer("error")
        val buf = data ?: ByteArray(length)
        val n = c.controlTransfer(requestType, request, value, index, buf, length, TRANSFER_TIMEOUT_MS)
        if (n < 0) return UsbTransfer("error")
        return if (requestType and 0x80 != 0) UsbTransfer("ok", buf.copyOf(n)) else UsbTransfer("ok", written = n)
    }

    private fun endpoint(d: UsbDevice, number: Int, dirIn: Boolean): UsbEndpoint? =
        (0 until d.interfaceCount).map { d.getInterface(it) }.flatMap { i -> (0 until i.endpointCount).map { i.getEndpoint(it) } }
            .firstOrNull { it.endpointNumber == number && (it.direction == UsbConstants.USB_DIR_IN) == dirIn }

    override fun bulkIn(key: String, endpoint: Int, length: Int, timeoutMs: Int): UsbTransfer {
        val d = device(key) ?: return UsbTransfer("error"); val c = conns[key] ?: return UsbTransfer("error")
        val ep = endpoint(d, endpoint, true) ?: return UsbTransfer("error")
        val buf = ByteArray(length)
        val n = c.bulkTransfer(ep, buf, length, timeoutMs)
        return if (n < 0) UsbTransfer("error") else UsbTransfer("ok", buf.copyOf(n))
    }

    override fun bulkOut(key: String, endpoint: Int, data: ByteArray, timeoutMs: Int): UsbTransfer {
        val d = device(key) ?: return UsbTransfer("error"); val c = conns[key] ?: return UsbTransfer("error")
        val ep = endpoint(d, endpoint, false) ?: return UsbTransfer("error")
        val n = c.bulkTransfer(ep, data, data.size, timeoutMs)
        return if (n < 0) UsbTransfer("error") else UsbTransfer("ok", written = n)
    }

    /** UsbDeviceConnection has no public reset on the API levels this app supports: reported as unavailable. */
    override fun reset(key: String): Boolean = false

    companion object {
        const val ACTION_PERMISSION = "com.diegonmarcos.superapp.browser.USB_PERMISSION"
        const val TRANSFER_TIMEOUT_MS = 5000
    }
}

/**
 * The real NFC radio behind Web NFC (#1225): reader mode on the visible activity (no tag-dispatch intent, no foreground-dispatch
 * manifest filters), NDEF read of any tag the platform can read, NDEF write / format of a tag. One page scans at a time.
 */
class AndroidNfcBackend(private val ctx: Context, private val activity: () -> Activity?) : NfcBackend {
    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(ctx)
    private class Write(val records: List<NdefRec>, val overwrite: Boolean, val done: (NfcWriteResult) -> Unit)
    @Volatile private var listener: NfcListener? = null
    @Volatile private var write: Write? = null
    @Volatile private var readerOn: Activity? = null

    override val supported: Boolean get() = adapter != null
    override val enabled: Boolean get() = adapter?.isEnabled == true

    override fun start(l: NfcListener): Boolean {
        if (listener != null && listener !== l) return false
        listener = l
        return ensureReader()
    }

    override fun stop(l: NfcListener) { if (listener === l) listener = null; maybeDisable() }

    override fun writeNext(records: List<NdefRec>, overwrite: Boolean, done: (NfcWriteResult) -> Unit) {
        write = Write(records, overwrite, done)
        if (!ensureReader()) { write = null; done(NfcWriteResult("NotReadableError", "NFC could not be started.")) }
    }

    override fun cancelWrite() { write = null; maybeDisable() }

    private fun ensureReader(): Boolean {
        val a = activity() ?: return false
        val nfc = adapter ?: return false
        if (readerOn === a) return true
        nfc.enableReaderMode(a, { tag -> onTag(tag) }, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V, null)
        readerOn = a
        return true
    }

    private fun maybeDisable() {
        if (listener != null || write != null) return
        readerOn?.let { runCatching { adapter?.disableReaderMode(it) } }
        readerOn = null
    }

    private fun onTag(tag: Tag) {
        val w = write
        if (w != null) { write = null; val r = writeTag(tag, w); w.done(r); maybeDisable(); return }
        val l = listener ?: return
        val serial = tag.id.joinToString(":") { "%02x".format(it.toInt() and 0xFF) }
        runCatching {
            val ndef = Ndef.get(tag)
            val records = if (ndef == null) emptyList() else {
                ndef.connect()
                try { (ndef.ndefMessage ?: ndef.cachedNdefMessage)?.records?.map { NdefCodec.toWeb(RawNdef(it.tnf.toInt(), it.type, it.id, it.payload)) } ?: emptyList() } finally { runCatching { ndef.close() } }
            }
            l.tag(serial, records)
        }.onFailure { l.failed(it.message ?: "NFC read failed") }
    }

    private fun writeTag(tag: Tag, w: Write): NfcWriteResult {
        val raws = w.records.map { NdefCodec.toRaw(it).first ?: return NfcWriteResult("NotSupportedError", "A record cannot be written.") }
        val msg = NdefMessage(raws.map { NdefRecord(it.tnf.toShort(), it.type, it.id, it.payload) }.toTypedArray())
        return runCatching {
            val ndef = Ndef.get(tag)
            if (ndef != null) {
                ndef.connect()
                try {
                    if (!ndef.isWritable) return NfcWriteResult("NotAllowedError", "The tag is read-only.")
                    val existing = ndef.ndefMessage?.records
                    val hasContent = existing != null && (existing.size > 1 || (existing.size == 1 && existing[0].tnf != NdefRecord.TNF_EMPTY))
                    if (!w.overwrite && hasContent) return NfcWriteResult("NotAllowedError", "The tag already holds records and overwrite is false.")
                    if (msg.byteArrayLength > ndef.maxSize) return NfcWriteResult("NetworkError", "The message does not fit on the tag.")
                    ndef.writeNdefMessage(msg)
                } finally { runCatching { ndef.close() } }
                NfcWriteResult(null, null)
            } else {
                val f = NdefFormatable.get(tag) ?: return NfcWriteResult("NotSupportedError", "The tag does not support NDEF.")
                f.connect()
                try { f.format(msg) } finally { runCatching { f.close() } }
                NfcWriteResult(null, null)
            }
        }.getOrElse { NfcWriteResult("NetworkError", it.message ?: "The write failed.") }
    }
}
