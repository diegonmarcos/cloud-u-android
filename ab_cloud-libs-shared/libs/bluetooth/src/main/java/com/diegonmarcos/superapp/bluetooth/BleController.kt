package com.diegonmarcos.superapp.bluetooth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** A characteristic's last value, with the catalog's reading of it. */
data class CharValue(val bytes: ByteArray, val atMs: Long, val decoded: Decoded?, val notified: Boolean = false)

/** Everything the page draws. Immutable: the controller replaces it on every change. */
data class BleState(
    val supported: Boolean = true,
    val enabled: Boolean = true,
    val scanning: Boolean = false,
    val scanError: String = "",
    val query: ScanQuery = ScanQuery(),
    val seen: Map<String, BleDevice> = emptyMap(),
    val bonded: List<BleDevice> = emptyList(),
    val selected: String? = null,
    val conn: ConnState = ConnState.Disconnected,
    val services: List<GattService> = emptyList(),
    val values: Map<String, CharValue> = emptyMap(),
    val notifying: Map<String, NotifyMode> = emptyMap(),
    val mtu: Int = 23,
    val rssi: Int? = null,
    val bondState: Int = GattSession.BOND_NONE,
    val telemetry: List<Double> = emptyList(),
    val telemetryLabel: String = "",
    val busy: String = "",
    val message: String = "",
) {
    val selectedDevice: BleDevice? get() = selected?.let { seen[it] ?: bonded.firstOrNull { b -> b.address == it } }
}

/**
 * The Bluetooth page's brain (#1221), over a [GattSession]: scan with a [ScanQuery], pick a device, connect, bond, discover, read /
 * write / subscribe, descriptors, MTU, RSSI, raw hex writes, the telemetry graph feed. Plain Kotlin: a unit test drives it with a
 * fake [BleAdapter]; the page only renders [state] and calls these.
 */
class BleController(
    val session: GattSession,
    val catalog: GattCatalog,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : GattListener {
    private val adapter: BleAdapter get() = session.adapter
    private val _state = MutableStateFlow(BleState(supported = adapter.supported, enabled = adapter.enabled))
    val state: StateFlow<BleState> = _state
    val log: BleLog get() = session.log

    init { session.observe(this); refreshBonded() }

    private fun edit(f: (BleState) -> BleState) { _state.update(f) }

    /** Re-reads the adapter's presence (the page calls it when it resumes, after the user turned Bluetooth on). */
    fun refreshAdapter() { edit { it.copy(supported = adapter.supported, enabled = adapter.enabled) }; refreshBonded() }

    fun refreshBonded() {
        val list = runCatching { adapter.bonded() }.getOrDefault(emptyList())
        edit { it.copy(bonded = list) }
    }

    // ---- Find ----

    fun setQuery(q: ScanQuery) = edit { it.copy(query = q) }

    fun startScan() {
        if (!adapter.supported) { edit { it.copy(scanError = "This device has no Bluetooth LE") }; return }
        if (!adapter.enabled) { edit { it.copy(scanError = "Bluetooth is off") }; return }
        edit { it.copy(scanning = true, scanError = "", seen = emptyMap()) }
        log.add(LogKind.Sys, "Scan started (${state.value.query.filterId})")
        val ok = adapter.startScan({ d -> edit { s -> s.copy(seen = s.seen + (d.address to (s.seen[d.address]?.merge(d) ?: d))) } }) { err ->
            log.add(LogKind.Error, "Scan failed: $err"); edit { it.copy(scanning = false, scanError = err) }
        }
        if (!ok) edit { it.copy(scanning = false, scanError = "Scan refused (permission missing?)") }
    }

    fun stopScan() {
        adapter.stopScan()
        if (state.value.scanning) log.add(LogKind.Sys, "Scan stopped")
        edit { it.copy(scanning = false) }
    }

    /** The scan result, filtered by the current query. */
    fun visible(s: BleState = state.value): List<BleDevice> = BleFilters.apply(s.seen.values, s.query, catalog)

    // ---- Connect ----

    fun select(address: String) = edit {
        if (it.selected == address) it else it.copy(selected = address, conn = session.state(address), services = session.services(address),
            values = emptyMap(), notifying = emptyMap(), telemetry = emptyList(), telemetryLabel = "", rssi = null, mtu = 23, message = "")
    }

    fun connect() {
        val a = state.value.selected ?: return
        stopScan()
        edit { it.copy(conn = ConnState.Connecting, busy = "Connecting", message = "") }
        session.connect(a) { r ->
            edit { it.copy(busy = "", message = if (r.ok) "" else "Connect failed: ${r.error} ${r.status}") }
            if (r.ok) { discover(); session.readRssi(a) { } }
        }
    }

    fun disconnect() { state.value.selected?.let { session.disconnect(it) } }

    fun bond() {
        val a = state.value.selected ?: return
        log.add(LogKind.Sys, "Bonding $a")
        if (!adapter.bond(a)) edit { it.copy(message = "Bonding refused") }
    }

    fun discover() {
        val a = state.value.selected ?: return
        edit { it.copy(busy = "Discovering services") }
        session.discover(a) { r -> edit { it.copy(busy = "", message = if (r.ok) it.message else "Discovery failed: ${r.error}") } }
    }

    fun requestMtu(mtu: Int) {
        val a = state.value.selected ?: return
        session.requestMtu(a, mtu.coerceIn(23, 517)) { r -> if (r.ok) edit { it.copy(mtu = r.int) } else edit { it.copy(message = "MTU: ${r.error}") } }
    }

    fun readRssi() {
        val a = state.value.selected ?: return
        session.readRssi(a) { r -> if (r.ok) edit { it.copy(rssi = r.int) } }
    }

    // ---- Interact ----

    fun read(c: GattCharacteristic) {
        val a = state.value.selected ?: return
        session.read(a, c.serviceUuid, c.uuid) { r -> if (!r.ok) edit { it.copy(message = "Read ${Uuids.short(c.uuid)}: ${r.error}") } }
    }

    fun write(c: GattCharacteristic, bytes: ByteArray, withResponse: Boolean = !c.canWriteNoResponse || c.properties and GattProps.WRITE != 0) {
        val a = state.value.selected ?: return
        session.write(a, c.serviceUuid, c.uuid, bytes, withResponse) { r ->
            edit { it.copy(message = if (r.ok) "Wrote ${bytes.size} bytes to ${Uuids.short(c.uuid)}" else "Write ${Uuids.short(c.uuid)}: ${r.error} ${r.status}") }
        }
    }

    /** Dev tab: parse [hex] and write it to [c]; the message says why it did not. */
    fun writeHex(c: GattCharacteristic, hex: String, withResponse: Boolean) {
        val bytes = Hex.parse(hex)
        if (bytes == null) { edit { it.copy(message = "Not a hex string: $hex") }; return }
        write(c, bytes, withResponse)
    }

    fun setNotify(c: GattCharacteristic, mode: NotifyMode) {
        val a = state.value.selected ?: return
        session.setNotify(a, c.serviceUuid, c.uuid, mode) { r ->
            if (r.ok) edit { it.copy(notifying = if (mode == NotifyMode.Off) it.notifying - c.key else it.notifying + (c.key to mode)) }
            else edit { it.copy(message = "Notify ${Uuids.short(c.uuid)}: ${r.error}") }
        }
    }

    fun readDescriptor(c: GattCharacteristic, desc: String) {
        val a = state.value.selected ?: return
        session.readDescriptor(a, c.serviceUuid, c.uuid, desc) { r ->
            edit { it.copy(message = if (r.ok) "${catalog.descriptorName(desc)}: ${describeDescriptor(desc, r.value)}" else "Descriptor ${Uuids.short(desc)}: ${r.error}") }
        }
    }

    fun readAll() {
        val a = state.value.selected ?: return
        state.value.services.flatMap { it.characteristics }.filter { it.canRead }.forEach { read(it) }
        session.readRssi(a) { r -> if (r.ok) edit { it.copy(rssi = r.int) } }
    }

    fun describeDescriptor(desc: String, bytes: ByteArray): String =
        GattDecoders.decode(if (Uuids.assigned(desc) == 0x2902) "cccd" else if (Uuids.assigned(desc) == 0x2901) "utf8" else "", bytes)?.text ?: Hex.format(bytes)

    fun clearTelemetry() = edit { it.copy(telemetry = emptyList(), telemetryLabel = "") }

    // ---- events ----

    private fun record(service: String, char: String, bytes: ByteArray, notified: Boolean) {
        val decoded = GattDecoders.describe(catalog, char, bytes)
        val v = CharValue(bytes, clock(), decoded, notified)
        edit { s ->
            val numeric = decoded.value
            s.copy(values = s.values + ("$service/$char" to v),
                telemetry = if (numeric != null && (notified || s.telemetryLabel.isEmpty() || s.telemetryLabel == catalog.characteristicName(char))) (s.telemetry + numeric).takeLast(MAX_SAMPLES) else s.telemetry,
                telemetryLabel = if (numeric != null) catalog.characteristicName(char) else s.telemetryLabel)
        }
    }

    override fun onConnectionState(address: String, state: ConnState, status: Int) {
        if (address != _state.value.selected) return
        edit { it.copy(conn = state, services = if (state == ConnState.Disconnected) emptyList() else it.services, notifying = if (state == ConnState.Disconnected) emptyMap() else it.notifying) }
    }

    override fun onServices(address: String, services: List<GattService>, status: Int) {
        if (address == _state.value.selected && status == 0) edit { it.copy(services = services) }
    }

    override fun onRead(address: String, service: String, char: String, value: ByteArray, status: Int) {
        if (address == _state.value.selected && status == 0) record(service, char, value, notified = false)
    }

    override fun onChanged(address: String, service: String, char: String, value: ByteArray) {
        if (address == _state.value.selected) record(service, char, value, notified = true)
    }

    override fun onBond(address: String, state: Int) {
        if (address == _state.value.selected) edit { it.copy(bondState = state) }
        refreshBonded()
    }

    fun close() { stopScan(); session.unobserve(this) }

    companion object { const val MAX_SAMPLES = 60 }
}
