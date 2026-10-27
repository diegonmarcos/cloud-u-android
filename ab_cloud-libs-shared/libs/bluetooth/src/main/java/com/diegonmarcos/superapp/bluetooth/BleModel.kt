package com.diegonmarcos.superapp.bluetooth

/** One device seen by the scanner or the bonded list. [manufacturer] is company id to hex payload. */
data class BleDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    val serviceUuids: List<String> = emptyList(),
    val manufacturer: Map<Int, String> = emptyMap(),
    val txPower: Int? = null,
    val connectable: Boolean = true,
    val bonded: Boolean = false,
    val classic: Boolean = false,
    val lastSeenMs: Long = 0L,
) {
    val label: String get() = name?.takeIf { it.isNotBlank() } ?: "Unnamed"

    /** A newer sighting of the same device: the fresh signal, the union of what either advertisement carried. */
    fun merge(newer: BleDevice): BleDevice = newer.copy(
        name = newer.name?.takeIf { it.isNotBlank() } ?: name,
        serviceUuids = (serviceUuids + newer.serviceUuids).distinct(),
        manufacturer = manufacturer + newer.manufacturer,
        txPower = newer.txPower ?: txPower,
        bonded = newer.bonded || bonded,
    )
}

enum class ConnState { Disconnected, Connecting, Connected, Disconnecting }

enum class NotifyMode { Off, Notify, Indicate }

/** The GATT characteristic property bits, the same values Android and the ATT spec use. */
object GattProps {
    const val BROADCAST = 0x01
    const val READ = 0x02
    const val WRITE_NO_RESPONSE = 0x04
    const val WRITE = 0x08
    const val NOTIFY = 0x10
    const val INDICATE = 0x20
    const val SIGNED_WRITE = 0x40
    const val EXTENDED = 0x80

    fun names(p: Int): List<String> = buildList {
        if (p and BROADCAST != 0) add("Broadcast")
        if (p and READ != 0) add("Read")
        if (p and WRITE_NO_RESPONSE != 0) add("Write no response")
        if (p and WRITE != 0) add("Write")
        if (p and NOTIFY != 0) add("Notify")
        if (p and INDICATE != 0) add("Indicate")
        if (p and SIGNED_WRITE != 0) add("Signed write")
        if (p and EXTENDED != 0) add("Extended")
    }
}

data class GattDescriptor(val uuid: String)

data class GattCharacteristic(
    val serviceUuid: String,
    val uuid: String,
    val properties: Int,
    val descriptors: List<GattDescriptor> = emptyList(),
) {
    val key: String get() = "$serviceUuid/$uuid"
    val canRead: Boolean get() = properties and GattProps.READ != 0
    val canWrite: Boolean get() = properties and (GattProps.WRITE or GattProps.WRITE_NO_RESPONSE or GattProps.SIGNED_WRITE) != 0
    val canWriteNoResponse: Boolean get() = properties and GattProps.WRITE_NO_RESPONSE != 0
    val canNotify: Boolean get() = properties and GattProps.NOTIFY != 0
    val canIndicate: Boolean get() = properties and GattProps.INDICATE != 0
}

data class GattService(val uuid: String, val characteristics: List<GattCharacteristic>)

/** The outcome of one queued GATT operation. [value] carries a read; [int] an MTU or an RSSI. */
data class OpResult(val ok: Boolean, val status: Int = 0, val value: ByteArray = ByteArray(0), val int: Int = 0, val error: String = "") {
    companion object {
        fun failed(error: String, status: Int = -1) = OpResult(false, status, error = error)
    }
}

/** What a GATT event reports back. All methods are optional: an observer overrides the ones it follows. Called from any thread. */
interface GattListener {
    fun onConnectionState(address: String, state: ConnState, status: Int) {}
    fun onServices(address: String, services: List<GattService>, status: Int) {}
    fun onRead(address: String, service: String, char: String, value: ByteArray, status: Int) {}
    fun onWrite(address: String, service: String, char: String, status: Int) {}
    fun onChanged(address: String, service: String, char: String, value: ByteArray) {}
    fun onNotifySet(address: String, service: String, char: String, mode: NotifyMode, status: Int) {}
    fun onDescriptorRead(address: String, service: String, char: String, desc: String, value: ByteArray, status: Int) {}
    fun onDescriptorWrite(address: String, service: String, char: String, desc: String, status: Int) {}
    fun onMtu(address: String, mtu: Int, status: Int) {}
    fun onRssi(address: String, rssi: Int, status: Int) {}
    fun onBond(address: String, state: Int) {}
}

/** The port to the radio. [AndroidBleAdapter] is the phone's; a test supplies a fake. Every call returns false when it could not even start. */
interface BleAdapter {
    val supported: Boolean
    val enabled: Boolean
    var listener: GattListener?
    fun startScan(onResult: (BleDevice) -> Unit, onFailed: (String) -> Unit): Boolean
    fun stopScan()
    fun bonded(): List<BleDevice>
    fun connect(address: String): Boolean
    fun disconnect(address: String)
    fun discover(address: String): Boolean
    fun read(address: String, service: String, char: String): Boolean
    fun write(address: String, service: String, char: String, value: ByteArray, withResponse: Boolean): Boolean
    fun setNotify(address: String, service: String, char: String, mode: NotifyMode): Boolean
    fun readDescriptor(address: String, service: String, char: String, desc: String): Boolean
    fun writeDescriptor(address: String, service: String, char: String, desc: String, value: ByteArray): Boolean
    fun requestMtu(address: String, mtu: Int): Boolean
    fun readRssi(address: String): Boolean
    fun bond(address: String): Boolean
}
