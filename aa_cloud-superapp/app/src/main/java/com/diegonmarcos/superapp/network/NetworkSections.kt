package com.diegonmarcos.superapp.network

/**
 * The ONE order of the network tools: the status strip's left-cluster icons and the network
 * popup's sections are both laid out from [ORDER] through [inOrder], so the two cannot drift.
 * Tapping an icon still opens the popup on that icon's own section (the key is the link).
 */
object NetworkSections {
    const val CELLULAR = "cellular"; const val WIFI = "wifi"; const val BLUETOOTH = "bluetooth"; const val MESH = "mesh"
    const val KDE = "kde"; const val ADB = "adb"; const val DATA = "data"; const val HOTSPOT = "hotspot"

    /** mobile, WiFi, BT, WG, KDE, ADB, Data, HS. */
    val ORDER: List<String> = listOf(CELLULAR, WIFI, BLUETOOTH, MESH, KDE, ADB, DATA, HOTSPOT)

    /** The strip's label for each key. */
    val LABELS: Map<String, String> = mapOf(
        CELLULAR to "5G", WIFI to "WiFi", BLUETOOTH to "BT", MESH to "WG",
        KDE to "KDE", ADB to "ADB", DATA to "Data", HOTSPOT to "HS",
    )

    /** [byKey]'s values in [ORDER]; a key missing or unknown is a programming error, caught by tests. */
    fun <T> inOrder(byKey: Map<String, T>): List<T> {
        require(byKey.keys == ORDER.toSet()) { "network sections ${byKey.keys} != $ORDER" }
        return ORDER.map { byKey.getValue(it) }
    }
}
