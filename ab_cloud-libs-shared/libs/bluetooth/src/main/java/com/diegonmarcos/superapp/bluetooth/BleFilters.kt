package com.diegonmarcos.superapp.bluetooth

/**
 * What the Find tab (and the Web Bluetooth chooser) keeps from the scan: a catalog preset ([filterId]), a name fragment, a floor on
 * the signal and two switches. The same query reads a device list whatever radio produced it.
 */
data class ScanQuery(
    val filterId: String = "all",
    val nameContains: String = "",
    val minRssi: Int = -100,
    val connectableOnly: Boolean = false,
    val hideUnnamed: Boolean = false,
)

/** One Web Bluetooth `filters` entry (requestDevice): every field present must match. */
data class WebFilter(
    val services: List<String> = emptyList(),
    val name: String? = null,
    val namePrefix: String? = null,
)

object BleFilters {
    fun matches(d: BleDevice, q: ScanQuery, catalog: GattCatalog): Boolean {
        if (d.rssi < q.minRssi) return false
        if (q.connectableOnly && !d.connectable) return false
        if (q.hideUnnamed && d.name.isNullOrBlank()) return false
        if (q.nameContains.isNotBlank() && d.name?.contains(q.nameContains.trim(), ignoreCase = true) != true) return false
        val preset = catalog.filter(q.filterId) ?: return true
        if (preset.services.isEmpty() && preset.namePrefixes.isEmpty()) return true
        return preset.services.any { it in d.serviceUuids } ||
            preset.namePrefixes.any { d.name?.startsWith(it, ignoreCase = true) == true }
    }

    fun apply(list: Collection<BleDevice>, q: ScanQuery, catalog: GattCatalog): List<BleDevice> =
        list.filter { matches(it, q, catalog) }.sortedByDescending { it.rssi }

    /** Web Bluetooth: a device passes when ANY filter entry matches (within an entry all given fields must), or there are no filters. */
    fun matchesWeb(d: BleDevice, filters: List<WebFilter>): Boolean =
        filters.isEmpty() || filters.any { f ->
            (f.services.isEmpty() || f.services.all { it in d.serviceUuids }) &&
                (f.name == null || d.name == f.name) &&
                (f.namePrefix == null || d.name?.startsWith(f.namePrefix) == true)
        }

    /** A coarse signal grade for the list: 4 bars at -55 or better down to 0 below -90. */
    fun bars(rssi: Int): Int = when {
        rssi >= -55 -> 4
        rssi >= -67 -> 3
        rssi >= -78 -> 2
        rssi >= -90 -> 1
        else -> 0
    }
}
