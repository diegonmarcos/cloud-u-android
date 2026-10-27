package com.diegonmarcos.superapp.bluetooth

/**
 * The serial GATT client over a [BleAdapter]. Android allows ONE GATT operation in flight per connection, and answers each in a
 * callback; this queues the operations per device, starts the next when the last answers (or times out), logs every ATT-level step
 * to [log], and tells its [observers] what happened. The page's [BleController] and Cloud Browser's Web Bluetooth bridge both drive it.
 *
 * [schedule] runs a block after a delay (the Android host posts to the main looper; a test calls it by hand); [now] is the clock.
 */
class GattSession(
    val adapter: BleAdapter,
    val log: BleLog = BleLog(),
    private val schedule: (Long, () -> Unit) -> Unit = { _, _ -> },
    private val now: () -> Long = { System.currentTimeMillis() },
    private val opTimeoutMs: Long = 10_000L,
) : GattListener {
    private class Op(val label: String, val start: () -> Boolean, val done: (OpResult) -> Unit)

    private val lock = Any()
    private val queues = HashMap<String, ArrayDeque<Op>>()
    private val inflight = HashMap<String, Op>()
    private val states = HashMap<String, ConnState>()
    private val serviceTable = HashMap<String, List<GattService>>()
    private val connectWaiters = HashMap<String, MutableList<(OpResult) -> Unit>>()
    private val observers = java.util.concurrent.CopyOnWriteArrayList<GattListener>()
    private var opSerial = 0L

    init { adapter.listener = this }

    fun observe(l: GattListener) { observers.add(l) }
    fun unobserve(l: GattListener) { observers.remove(l) }

    fun state(address: String): ConnState = synchronized(lock) { states[address] ?: ConnState.Disconnected }
    fun services(address: String): List<GattService> = synchronized(lock) { serviceTable[address] ?: emptyList() }
    fun characteristic(address: String, service: String, char: String): GattCharacteristic? =
        services(address).firstOrNull { it.uuid == service }?.characteristics?.firstOrNull { it.uuid == char }

    // ---- connection ----

    /** Connects [address]; [done] answers once, when it is connected or has failed. */
    fun connect(address: String, done: (OpResult) -> Unit = {}) {
        synchronized(lock) {
            if (states[address] == ConnState.Connected) { done(OpResult(true)); return }
            connectWaiters.getOrPut(address) { ArrayList() }.add(done)
            if (states[address] == ConnState.Connecting) return
            states[address] = ConnState.Connecting
        }
        log.add(LogKind.Sys, "Connecting $address")
        if (!adapter.connect(address)) failConnect(address, "connect refused (permission or adapter off)")
    }

    fun disconnect(address: String) {
        log.add(LogKind.Sys, "Disconnecting $address")
        synchronized(lock) { if (states[address] == ConnState.Connected) states[address] = ConnState.Disconnecting }
        adapter.disconnect(address)
    }

    private fun failConnect(address: String, error: String) {
        val waiters = synchronized(lock) { states[address] = ConnState.Disconnected; connectWaiters.remove(address).orEmpty() }
        log.add(LogKind.Error, "Connect $address: $error")
        waiters.forEach { it(OpResult.failed(error)) }
    }

    fun discover(address: String, done: (OpResult) -> Unit = {}) = enqueue(address, "discover services", { adapter.discover(address) }, done)

    // ---- queued operations ----

    fun read(address: String, service: String, char: String, done: (OpResult) -> Unit) =
        enqueue(address, "read ${Uuids.short(char)}", { adapter.read(address, service, char) }, done)

    fun write(address: String, service: String, char: String, value: ByteArray, withResponse: Boolean, done: (OpResult) -> Unit) {
        log.add(LogKind.Tx, "${Uuids.short(char)} <= ${Hex.format(value)}" + if (withResponse) "" else " (no response)")
        enqueue(address, "write ${Uuids.short(char)}", { adapter.write(address, service, char, value, withResponse) }, done)
    }

    fun setNotify(address: String, service: String, char: String, mode: NotifyMode, done: (OpResult) -> Unit) =
        enqueue(address, "notify ${mode.name.lowercase()} ${Uuids.short(char)}", { adapter.setNotify(address, service, char, mode) }, done)

    fun readDescriptor(address: String, service: String, char: String, desc: String, done: (OpResult) -> Unit) =
        enqueue(address, "read descriptor ${Uuids.short(desc)}", { adapter.readDescriptor(address, service, char, desc) }, done)

    fun writeDescriptor(address: String, service: String, char: String, desc: String, value: ByteArray, done: (OpResult) -> Unit) {
        log.add(LogKind.Tx, "descriptor ${Uuids.short(desc)} <= ${Hex.format(value)}")
        enqueue(address, "write descriptor ${Uuids.short(desc)}", { adapter.writeDescriptor(address, service, char, desc, value) }, done)
    }

    fun requestMtu(address: String, mtu: Int, done: (OpResult) -> Unit) =
        enqueue(address, "request MTU $mtu", { adapter.requestMtu(address, mtu) }, done)

    fun readRssi(address: String, done: (OpResult) -> Unit) =
        enqueue(address, "read RSSI", { adapter.readRssi(address) }, done)

    private fun enqueue(address: String, label: String, start: () -> Boolean, done: (OpResult) -> Unit) {
        if (state(address) != ConnState.Connected) {
            log.add(LogKind.Error, "$label: not connected")
            done(OpResult.failed("not connected")); return
        }
        synchronized(lock) { queues.getOrPut(address) { ArrayDeque() }.addLast(Op(label, start, done)) }
        pump(address)
    }

    private fun pump(address: String) {
        val op: Op
        val serial: Long
        synchronized(lock) {
            if (inflight.containsKey(address)) return
            op = queues[address]?.removeFirstOrNull() ?: return
            inflight[address] = op
            serial = ++opSerial
            opSerials[address] = serial
        }
        val started = runCatching { op.start() }.getOrElse { log.add(LogKind.Error, "${op.label}: ${it.message}"); false }
        if (!started) { finish(address, OpResult.failed("${op.label}: refused")); return }
        schedule(opTimeoutMs) { if (opSerials[address] == serial) finish(address, OpResult.failed("${op.label}: timed out")) }
    }

    private val opSerials = HashMap<String, Long>()

    /** Completes the in-flight operation of [address] with [r] (a no-op when none is), then starts the next. */
    private fun finish(address: String, r: OpResult) {
        val op = synchronized(lock) { opSerials.remove(address); inflight.remove(address) } ?: return
        if (!r.ok) log.add(LogKind.Error, "${op.label}: ${r.error.ifEmpty { "status ${r.status}" }}")
        op.done(r)
        pump(address)
    }

    // ---- adapter events ----

    override fun onConnectionState(address: String, state: ConnState, status: Int) {
        val waiters: List<(OpResult) -> Unit>
        val dropped: List<Op>
        synchronized(lock) {
            states[address] = state
            waiters = if (state == ConnState.Connected || state == ConnState.Disconnected) connectWaiters.remove(address).orEmpty() else emptyList()
            if (state == ConnState.Disconnected) {
                dropped = queues.remove(address).orEmpty().toList()
                serviceTable.remove(address)
            } else dropped = emptyList()
        }
        log.add(LogKind.Sys, "$address ${state.name.lowercase()}" + if (status != 0) " (status $status)" else "")
        if (state == ConnState.Disconnected) {
            finish(address, OpResult.failed("disconnected", status))
            dropped.forEach { it.done(OpResult.failed("disconnected", status)) }
        }
        waiters.forEach { it(if (state == ConnState.Connected) OpResult(true) else OpResult.failed("connection failed", status)) }
        observers.forEach { it.onConnectionState(address, state, status) }
    }

    override fun onServices(address: String, services: List<GattService>, status: Int) {
        synchronized(lock) { if (status == 0) serviceTable[address] = services }
        log.add(LogKind.Rx, "services: ${services.size}, characteristics: ${services.sumOf { it.characteristics.size }}")
        finish(address, if (status == 0) OpResult(true) else OpResult.failed("discovery", status))
        observers.forEach { it.onServices(address, services, status) }
    }

    override fun onRead(address: String, service: String, char: String, value: ByteArray, status: Int) {
        if (status == 0) log.add(LogKind.Rx, "${Uuids.short(char)} => ${Hex.format(value)}")
        finish(address, if (status == 0) OpResult(true, 0, value) else OpResult.failed("read", status))
        observers.forEach { it.onRead(address, service, char, value, status) }
    }

    override fun onWrite(address: String, service: String, char: String, status: Int) {
        if (status == 0) log.add(LogKind.Sys, "${Uuids.short(char)} written")
        finish(address, if (status == 0) OpResult(true) else OpResult.failed("write", status))
        observers.forEach { it.onWrite(address, service, char, status) }
    }

    override fun onChanged(address: String, service: String, char: String, value: ByteArray) {
        log.add(LogKind.Notify, "${Uuids.short(char)} => ${Hex.format(value)}")
        observers.forEach { it.onChanged(address, service, char, value) }
    }

    override fun onNotifySet(address: String, service: String, char: String, mode: NotifyMode, status: Int) {
        log.add(if (status == 0) LogKind.Sys else LogKind.Error, "${Uuids.short(char)} ${mode.name.lowercase()}" + if (status == 0) "" else " failed ($status)")
        finish(address, if (status == 0) OpResult(true) else OpResult.failed("notify", status))
        observers.forEach { it.onNotifySet(address, service, char, mode, status) }
    }

    override fun onDescriptorRead(address: String, service: String, char: String, desc: String, value: ByteArray, status: Int) {
        if (status == 0) log.add(LogKind.Rx, "descriptor ${Uuids.short(desc)} => ${Hex.format(value)}")
        finish(address, if (status == 0) OpResult(true, 0, value) else OpResult.failed("descriptor read", status))
        observers.forEach { it.onDescriptorRead(address, service, char, desc, value, status) }
    }

    override fun onDescriptorWrite(address: String, service: String, char: String, desc: String, status: Int) {
        finish(address, if (status == 0) OpResult(true) else OpResult.failed("descriptor write", status))
        observers.forEach { it.onDescriptorWrite(address, service, char, desc, status) }
    }

    override fun onMtu(address: String, mtu: Int, status: Int) {
        log.add(LogKind.Sys, "MTU $mtu" + if (status == 0) "" else " (status $status)")
        finish(address, if (status == 0) OpResult(true, 0, int = mtu) else OpResult.failed("mtu", status))
        observers.forEach { it.onMtu(address, mtu, status) }
    }

    override fun onRssi(address: String, rssi: Int, status: Int) {
        finish(address, if (status == 0) OpResult(true, 0, int = rssi) else OpResult.failed("rssi", status))
        observers.forEach { it.onRssi(address, rssi, status) }
    }

    override fun onBond(address: String, state: Int) {
        log.add(LogKind.Sys, "$address bond state ${bondName(state)}")
        observers.forEach { it.onBond(address, state) }
    }

    /** Wall clock for callers that stamp their own samples. */
    fun nowMs(): Long = now()

    companion object {
        const val BOND_NONE = 10
        const val BOND_BONDING = 11
        const val BOND_BONDED = 12
        fun bondName(s: Int) = when (s) { BOND_BONDED -> "bonded"; BOND_BONDING -> "bonding"; else -> "none" }
    }
}
