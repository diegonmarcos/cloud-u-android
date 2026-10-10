package com.diegonmarcos.superapp.network

/**
 * The ONE order of the network tools: the status strip's left-cluster icons and the network
 * popup's sections are both laid out from [ORDER] through [inOrder], so the two cannot drift.
 * Tapping an icon still opens the popup on that icon's own section (the key is the link).
 * Each section also has ONE settings target ([SETTINGS]), the "Settings ›" link at its header.
 */
object NetworkSections {
    const val CELLULAR = "cellular"; const val WIFI = "wifi"; const val BLUETOOTH = "bluetooth"; const val MESH = "mesh"
    const val KDE = "kde"; const val ADB = "adb"; const val DATA = "data"; const val HOTSPOT = "hotspot"; const val GPS = "gps"

    /** mobile, WiFi, BT, WG, KDE, ADB, Data, HS, GPS. */
    val ORDER: List<String> = listOf(CELLULAR, WIFI, BLUETOOTH, MESH, KDE, ADB, DATA, HOTSPOT, GPS)

    /** The strip's label for each key (the mobile one is replaced live by MobileLink: "5G", "Call", "SOS"...). */
    val LABELS: Map<String, String> = mapOf(
        CELLULAR to MobileLink.OFF_LABEL, WIFI to "WiFi", BLUETOOTH to "BT", MESH to "WG",
        KDE to "KDE", ADB to "ADB", DATA to "Data", HOTSPOT to "HS", GPS to "GPS",
    )

    /** [byKey]'s values in [ORDER]; a key missing or unknown is a programming error, caught by tests. */
    fun <T> inOrder(byKey: Map<String, T>): List<T> {
        require(byKey.keys == ORDER.toSet()) { "network sections ${byKey.keys} != $ORDER" }
        return ORDER.map { byKey.getValue(it) }
    }

    /** One step of the popup's layout: a section, or the divider drawn between two of them. */
    sealed class Item {
        data class Section(val key: String) : Item()
        object Divider : Item()
    }

    /** The popup's layout: every section in [ORDER], a divider between each two, and one before the trailing Network block. */
    fun layout(trailing: Boolean = true): List<Item> {
        val out = mutableListOf<Item>()
        for (k in ORDER) { if (out.isNotEmpty()) out += Item.Divider; out += Item.Section(k) }
        if (trailing) out += Item.Divider
        return out
    }

    /**
     * Where a section's "Settings ›" link goes. [Target.System] = Android screens tried in order
     * (the first that resolves on this build wins: AOSP and Samsung name them differently), each
     * "action:<Intent action>" or "component:<package>/<class>"; perSim adds the data SIM's
     * Settings.EXTRA_SUB_ID. [Target.InApp] = a SuperApp page, opened through its section/page route.
     */
    sealed class Target {
        abstract val label: String
        data class System(val tries: List<String>, val perSim: Boolean = false, override val label: String = "Settings ›") : Target()
        data class InApp(val section: String, val page: String, override val label: String) : Target()
    }

    private const val SETTINGS_PKG = "com.android.settings"
    private const val ANY_SETTINGS = "action:android.settings.SETTINGS"

    val SETTINGS: Map<String, Target> = mapOf(
        // The Mobile network page (data, roaming, VoLTE, preferred type) first; operator choice next.
        CELLULAR to Target.System(listOf(
            "action:android.settings.DATA_ROAMING_SETTINGS",
            "action:android.settings.NETWORK_OPERATOR_SETTINGS",
            "action:android.settings.WIRELESS_SETTINGS",
            ANY_SETTINGS), perSim = true),
        WIFI to Target.System(listOf("action:android.settings.WIFI_SETTINGS", "action:android.settings.WIRELESS_SETTINGS", ANY_SETTINGS)),
        BLUETOOTH to Target.System(listOf("action:android.settings.BLUETOOTH_SETTINGS", ANY_SETTINGS)),
        MESH to Target.InApp("config", "wg", "Cloud Mesh ›"),
        KDE to Target.InApp("config", "kde", "Peer Control ›"),
        ADB to Target.InApp("config", "adb-shell", "ADB Shell ›"),
        DATA to Target.System(listOf(
            "component:$SETTINGS_PKG/$SETTINGS_PKG.Settings\$UsbDetailsActivity",
            "action:android.settings.CONNECTED_DEVICE_SETTINGS",
            ANY_SETTINGS)),
        HOTSPOT to Target.System(listOf(
            "component:$SETTINGS_PKG/$SETTINGS_PKG.TetherSettings",
            "component:$SETTINGS_PKG/$SETTINGS_PKG.Settings\$TetherSettingsActivity",
            "action:android.settings.WIRELESS_SETTINGS",
            ANY_SETTINGS)),
        GPS to Target.System(listOf("action:android.settings.LOCATION_SOURCE_SETTINGS", ANY_SETTINGS)),
    )

    /** The page every "grant" action opens: Configs › Permissions (the app's one permission flow). */
    val PERMISSIONS = Target.InApp("config", "perms", "Grant ›")
}
