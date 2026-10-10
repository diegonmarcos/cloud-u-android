package com.diegonmarcos.superapp.network

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * The status strip's BT icon + dots and the popup's Bluetooth section, from one reading:
 *   label  — white when the adapter is ON, grey when OFF, whatever is connected;
 *   dots   — how many devices are connected right now (0..4, 4 = four or more), all four grey
 *            (unfilled) at 0.
 * The rules are pure ([dots], [tint], [lines], [merge]) so they are tested without a radio; [read]
 * gathers the facts.
 */
object BtLinks {

    /** One connected device, every profile it is connected on ("A2DP", "Headset", ...) and its battery. */
    data class Link(val address: String, val name: String, val profiles: List<String>, val battery: Battery = Battery())

    /** Battery percentages a device reports, -1 = not readable. Earbuds may report left / right / case. */
    data class Battery(val main: Int = -1, val left: Int = -1, val right: Int = -1, val case: Int = -1)

    /** "80%", "L 80% · R 75% · Case 40%" (only the parts known), or "—": never a made-up number. */
    fun batteryText(b: Battery): String {
        fun ok(v: Int) = v in 0..100
        val split = listOfNotNull(
            b.left.takeIf(::ok)?.let { "L $it%" }, b.right.takeIf(::ok)?.let { "R $it%" },
            b.case.takeIf(::ok)?.let { "Case $it%" })
        return when {
            split.isNotEmpty() -> split.joinToString(" · ")
            ok(b.main) -> "${b.main}%"
            else -> "—"
        }
    }

    /** A METADATA_* battery value: ASCII digits, e.g. "80"; null / garbage / out of range = -1. */
    fun metadataLevel(bytes: ByteArray?): Int =
        bytes?.let { runCatching { String(it, Charsets.US_ASCII).trim().toInt() }.getOrNull() }?.takeIf { it in 0..100 } ?: -1

    data class Reading(
        val adapterOn: Boolean,
        /** False = BLUETOOTH_CONNECT is not granted, so connections cannot be listed (count 0). */
        val permitted: Boolean,
        val links: List<Link>,
    ) {
        val count: Int get() = links.size
    }

    /** Filled dots for [count] connected devices: 0..4, four or more = 4. */
    fun dots(count: Int): Int = count.coerceIn(0, 4)

    fun tint(adapterOn: Boolean): Int = if (adapterOn) WgLink.TINT_ON else WgLink.TINT_OFF

    /** Join per-profile device lists (address -> name) into one row per device, profiles in the given order. */
    fun merge(byProfile: List<Pair<String, Map<String, String>>>): List<Link> {
        val names = LinkedHashMap<String, String>()
        val profiles = LinkedHashMap<String, MutableList<String>>()
        for ((profile, devices) in byProfile) for ((addr, name) in devices) {
            if (addr.isBlank()) continue
            if (names[addr].isNullOrBlank()) names[addr] = name
            val ps = profiles.getOrPut(addr) { mutableListOf() }
            if (profile !in ps) ps += profile
        }
        return names.map { (addr, name) -> Link(addr, name.ifBlank { addr }, profiles[addr].orEmpty()) }
    }

    /** The popup's Bluetooth section, under its on/off light. */
    fun lines(r: Reading): List<String> {
        if (!r.adapterOn) return listOf("OFF")
        if (!r.permitted) return listOf("ON · connections unknown",
            "Grant Nearby devices (Bluetooth) to the SuperApp to count connections")
        val out = mutableListOf("ON · ${r.count} connected")
        if (r.links.isEmpty()) out += "No active links"
        for (l in r.links) out += "  • ${l.name}" + (if (l.profiles.isEmpty()) "" else " (${l.profiles.joinToString(", ")})") +
            " · battery ${batteryText(l.battery)}"
        return out
    }

    // ── gathering (Android) ─────────────────────────────────────────────

    /** Profiles asked through proxies, in display order. Hidden ids are their stable AOSP values. */
    private val PROFILES: List<Pair<Int, String>> by lazy { buildList {
        add(BluetoothProfile.A2DP to "A2DP")
        add(BluetoothProfile.HEADSET to "Headset")
        if (Build.VERSION.SDK_INT >= 33) add(BluetoothProfile.LE_AUDIO to "LE Audio")
        if (Build.VERSION.SDK_INT >= 29) add(BluetoothProfile.HEARING_AID to "Hearing aid")
        add(HID_HOST to "HID")
        add(PAN to "PAN")
    } }
    private const val HID_HOST = 4   // BluetoothProfile.HID_HOST (@hide)
    private const val PAN = 5        // BluetoothProfile.PAN (@hide)

    private val proxies = java.util.concurrent.ConcurrentHashMap<Int, BluetoothProfile>()
    private val asked = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    /** Called (main thread) when a profile proxy arrives, so a reader can redraw with it. */
    @Volatile var onProxy: (() -> Unit)? = null

    fun permitted(ctx: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
        ctx.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** The facts, now. Cheap (in-process profile proxies), safe on the main thread. */
    @SuppressLint("MissingPermission")
    fun read(ctx: Context): Reading {
        val app = ctx.applicationContext
        val mgr = app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = mgr?.adapter
        val on = runCatching { adapter?.isEnabled == true }.getOrDefault(false)
        val ok = permitted(app)
        if (!on || !ok || adapter == null) return Reading(on, ok, emptyList())
        ensureProxies(app, adapter)
        val lists = mutableListOf<Pair<String, Map<String, String>>>()
        for ((id, label) in PROFILES) {
            val p = proxies[id] ?: continue
            lists += label to runCatching { p.connectedDevices.associate { it.address to nameOf(it) } }.getOrDefault(emptyMap())
        }
        lists += "BLE" to runCatching {
            mgr.getConnectedDevices(BluetoothProfile.GATT).associate { it.address to nameOf(it) }
        }.getOrDefault(emptyMap())
        // A bonded device with a live link but no profile above (a watch, a car kit mid-setup).
        lists += "link" to runCatching {
            adapter.bondedDevices.orEmpty().filter { aclConnected(it) }.associate { it.address to nameOf(it) }
        }.getOrDefault(emptyMap())
        val byAddr = runCatching { adapter.bondedDevices.orEmpty().associateBy { it.address } }.getOrDefault(emptyMap())
        return Reading(true, true, merge(lists).map { l ->
            l.copy(profiles = l.profiles - "link", battery = batteryOf(l.address, byAddr[l.address] ?: runCatching { adapter.getRemoteDevice(l.address) }.getOrNull()))
        })
    }

    /** Last level a BATTERY_LEVEL_CHANGED broadcast carried, per address: the fallback when the getter is unreadable. */
    private val lastBattery = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Feed a BATTERY_LEVEL_CHANGED broadcast (any receiver that hears one). */
    fun noteBattery(i: android.content.Intent) {
        if (i.action != ACTION_BATTERY_LEVEL_CHANGED) return
        val dev = runCatching {
            @Suppress("DEPRECATION") i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        }.getOrNull() ?: return
        val level = i.getIntExtra(EXTRA_BATTERY_LEVEL, -1)
        if (level in 0..100) lastBattery[dev.address] = level else lastBattery.remove(dev.address)
    }

    /** getBatteryLevel() (@hide, reflection), else the last broadcast; on API 33+ the METADATA_* earbud keys
     *  (readable only on builds that let an app ask; any refusal just leaves -1). */
    private fun batteryOf(address: String, d: BluetoothDevice?): Battery {
        if (d == null) return Battery(lastBattery[address] ?: -1)
        val main = runCatching { d.javaClass.getMethod("getBatteryLevel").invoke(d) as? Int }.getOrNull()
            ?.takeIf { it in 0..100 } ?: lastBattery[address] ?: -1
        if (Build.VERSION.SDK_INT < 33) return Battery(main)
        val get = runCatching { d.javaClass.getMethod("getMetadata", Int::class.javaPrimitiveType) }.getOrNull()
            ?: return Battery(main)
        fun meta(key: Int) = try { metadataLevel(get.invoke(d, key) as? ByteArray) } catch (_: Throwable) { -1 }
        val m = meta(META_MAIN_BATTERY)
        return Battery(if (main >= 0) main else m, meta(META_LEFT_BATTERY), meta(META_RIGHT_BATTERY), meta(META_CASE_BATTERY))
    }

    // BluetoothDevice METADATA_* keys (@SystemApi, stable AOSP values).
    private const val META_LEFT_BATTERY = 10
    private const val META_RIGHT_BATTERY = 11
    private const val META_CASE_BATTERY = 12
    private const val META_MAIN_BATTERY = 18
    const val ACTION_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
    const val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"

    @SuppressLint("MissingPermission")
    private fun nameOf(d: BluetoothDevice): String = runCatching { (if (Build.VERSION.SDK_INT >= 30) d.alias else null) ?: d.name }.getOrNull() ?: d.address

    /** BluetoothDevice.isConnected() is @hide but on the SDK's allowed list (ACL link up). */
    private fun aclConnected(d: BluetoothDevice): Boolean = runCatching {
        d.javaClass.getMethod("isConnected").invoke(d) as? Boolean ?: false
    }.getOrDefault(false)

    private fun ensureProxies(app: Context, adapter: BluetoothAdapter) {
        for ((id, _) in PROFILES) {
            if (!asked.add(id)) continue
            runCatching {
                adapter.getProfileProxy(app, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        proxies[profile] = proxy; onProxy?.invoke()
                    }
                    override fun onServiceDisconnected(profile: Int) { proxies.remove(profile); asked.remove(profile) }
                }, id)
            }.onFailure { asked.remove(id) }
        }
    }

    /** Broadcasts that change the BT icon or its count. */
    val ACTIONS = listOf(
        BluetoothAdapter.ACTION_STATE_CHANGED,
        BluetoothDevice.ACTION_ACL_CONNECTED,
        BluetoothDevice.ACTION_ACL_DISCONNECTED,
        "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED",
        "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED",
        "android.bluetooth.action.LE_AUDIO_CONNECTION_STATE_CHANGED",
        "android.bluetooth.hearingaid.profile.action.CONNECTION_STATE_CHANGED",
        "android.bluetooth.input.profile.action.CONNECTION_STATE_CHANGED",
        ACTION_BATTERY_LEVEL_CHANGED,
    )
}
