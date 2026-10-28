package com.diegonmarcos.superapp.browser

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Web Serial's backend (#1225): USB CDC ACM serial (the class Arduino, ESP32-S2/S3/C3, RP2040, STM32 and most dev boards expose),
 * driven through the same [UsbBackend] WebUSB uses: claim the control and data interfaces, SET_LINE_CODING / SET_CONTROL_LINE_STATE /
 * SEND_BREAK as control transfers, bulk IN / OUT on the data interface. Vendor-specific bridge chips (FTDI, CP210x, CH34x) speak their own
 * protocol and are not CDC: they are not listed. Reading is a loop on its own thread ([startLoop]); [pump] is one turn of it.
 */
class CdcAcmSerial(private val usb: UsbBackend, private val startLoop: (() -> Unit) -> Unit) : SerialBackend, HwUsbListener {
    private class Port(val dev: HwUsbDevice, val ctrl: Int, val data: Int, val inEp: Int, val outEp: Int, val inSize: Int) { @Volatile var open = true; @Volatile var dtr = true; @Volatile var rts = true }

    private val observers = CopyOnWriteArrayList<HwSerialListener>()
    private val ports = HashMap<String, Port>()
    private val lock = Any()

    init { usb.observe(this) }

    override val supported: Boolean get() = usb.supported
    override fun observe(l: HwSerialListener) { observers.addIfAbsent(l) }
    override fun unobserve(l: HwSerialListener) { observers.remove(l) }

    /** The attached devices that expose a CDC data interface with a bulk IN and a bulk OUT endpoint. */
    override fun ports(): List<HwUsbDevice> = usb.attached().filter { dataInterface(it) != null }

    private class Layout(val ctrl: Int, val data: Int, val inEp: Int, val outEp: Int, val inSize: Int)

    private fun dataInterface(d: HwUsbDevice): Layout? {
        val cfg = d.configs.firstOrNull() ?: return null
        val ifaces = cfg.interfaces
        val dataAlt = ifaces.firstNotNullOfOrNull { i ->
            i.alternates.firstOrNull { a -> a.cls == CDC_DATA && a.endpoints.any { it.type == "bulk" && it.dirIn } && a.endpoints.any { it.type == "bulk" && !it.dirIn } }?.let { i to it }
        } ?: return null
        val ctrl = ifaces.firstOrNull { i -> i.alternates.any { it.cls == CDC_COMM && it.sub == ACM } }?.number
            ?: ifaces.firstOrNull { i -> i.alternates.any { it.cls == CDC_COMM } }?.number ?: 0
        val ep = dataAlt.second.endpoints
        val inEp = ep.first { it.type == "bulk" && it.dirIn }
        val outEp = ep.first { it.type == "bulk" && !it.dirIn }
        return Layout(ctrl, dataAlt.first.number, inEp.number, outEp.number, inEp.packetSize.coerceAtLeast(8))
    }

    override fun open(key: String, o: SerialOptions): String? {
        val dev = usb.attached().firstOrNull { it.key == key } ?: return "The device was disconnected."
        val l = dataInterface(dev) ?: return "This device is not a USB CDC serial port."
        if (o.flowControl == "hardware") return "Hardware flow control is not available on USB CDC serial."
        synchronized(lock) { if (ports[key]?.open == true) return "The port is already open." }
        if (!usb.open(key)) return "Access to the device was denied."
        fun fail(m: String): String { usb.close(key); return m }
        if (!usb.claim(key, l.data)) return fail("The data interface could not be claimed.")
        if (l.ctrl != l.data && !usb.claim(key, l.ctrl)) { usb.release(key, l.data); return fail("The control interface could not be claimed.") }
        val port = Port(dev, l.ctrl, l.data, l.inEp, l.outEp, l.inSize)
        val err = lineCoding(key, port, o)
        if (err != null) { release(key, port); return err }
        // Raise DTR and RTS like every serial stack on open: most boards do not talk until DTR is up.
        if (usb.control(key, REQ_OUT_CLASS_IFACE, SET_CONTROL_LINE_STATE, 3, port.ctrl, null, 0).status != "ok") { release(key, port); return "The control lines could not be set." }
        synchronized(lock) { ports[key] = port }
        startLoop { while (port.open && pump(key)) Thread.sleep(1) }
        return null
    }

    private fun lineCoding(key: String, p: Port, o: SerialOptions): String? {
        val stop = when (o.stopBits) { 1 -> 0; 2 -> 2; else -> return "Unsupported stop bits." }
        val parity = when (o.parity) { "none" -> 0; "odd" -> 1; "even" -> 2; else -> return "Unsupported parity." }
        val b = o.baudRate
        val coding = byteArrayOf((b and 0xFF).toByte(), ((b shr 8) and 0xFF).toByte(), ((b shr 16) and 0xFF).toByte(), ((b shr 24) and 0xFF).toByte(),
            stop.toByte(), parity.toByte(), o.dataBits.toByte())
        return if (usb.control(key, REQ_OUT_CLASS_IFACE, SET_LINE_CODING, 0, p.ctrl, coding, coding.size).status == "ok") null else "The device refused the line settings."
    }

    private fun release(key: String, p: Port) {
        p.open = false
        usb.release(key, p.data)
        if (p.ctrl != p.data) usb.release(key, p.ctrl)
        usb.close(key)
    }

    /** One read turn: true while the port stays open and readable. A failed read on a device that is still attached is a quiet bus (keep going). */
    fun pump(key: String): Boolean {
        val p = synchronized(lock) { ports[key] } ?: return false
        if (!p.open) return false
        val r = usb.bulkIn(key, p.inEp, p.inSize, READ_TIMEOUT_MS)
        if (!p.open) return false
        if (r.status == "ok" && r.data.isNotEmpty()) { observers.forEach { it.data(key, r.data) }; return true }
        if (r.status != "ok" && usb.attached().none { it.key == key }) { drop(key); return false }
        return true
    }

    override fun write(key: String, bytes: ByteArray): String? {
        val p = synchronized(lock) { ports[key] }?.takeIf { it.open } ?: return "The port is not open."
        var off = 0
        while (off < bytes.size) {
            val chunk = bytes.copyOfRange(off, minOf(bytes.size, off + MAX_CHUNK))
            val r = usb.bulkOut(key, p.outEp, chunk)
            if (r.status != "ok" || r.written <= 0) return "The write failed."
            off += r.written
        }
        return null
    }

    override fun setSignals(key: String, dtr: Boolean?, rts: Boolean?, brk: Boolean?): String? {
        val p = synchronized(lock) { ports[key] }?.takeIf { it.open } ?: return "The port is not open."
        if (dtr != null || rts != null) {
            val v = (if (dtr ?: p.dtr) 1 else 0) or (if (rts ?: p.rts) 2 else 0)
            if (usb.control(key, REQ_OUT_CLASS_IFACE, SET_CONTROL_LINE_STATE, v, p.ctrl, null, 0).status != "ok") return "The control lines could not be set."
            p.dtr = dtr ?: p.dtr; p.rts = rts ?: p.rts
        }
        if (brk != null && usb.control(key, REQ_OUT_CLASS_IFACE, SEND_BREAK, if (brk) 0xFFFF else 0, p.ctrl, null, 0).status != "ok") return "The break could not be sent."
        return null
    }

    override fun close(key: String) {
        val p = synchronized(lock) { ports.remove(key) } ?: return
        if (p.open) release(key, p)
    }

    private fun drop(key: String) {
        val p = synchronized(lock) { ports.remove(key) } ?: return
        p.open = false
        observers.forEach { it.closed(key) }
    }

    override fun attached(d: HwUsbDevice) {}
    override fun detached(key: String) { drop(key) }

    companion object {
        const val CDC_COMM = 0x02; const val CDC_DATA = 0x0A; const val ACM = 0x02
        const val REQ_OUT_CLASS_IFACE = 0x21
        const val SET_LINE_CODING = 0x20; const val SET_CONTROL_LINE_STATE = 0x22; const val SEND_BREAK = 0x23
        const val READ_TIMEOUT_MS = 100
        const val MAX_CHUNK = 16384
    }
}
