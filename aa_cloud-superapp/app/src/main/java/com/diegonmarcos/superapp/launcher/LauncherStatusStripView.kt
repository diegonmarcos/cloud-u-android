package com.diegonmarcos.superapp.launcher
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.MainActivity
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.ui.SystemInfoPopup
import com.diegonmarcos.superapp.settings.LauncherTheme
import com.diegonmarcos.superapp.cloud.CalendarAgendaPopup
import com.diegonmarcos.superapp.battery.BatteryIconView
import com.diegonmarcos.superapp.battery.BatteryEstimatePopup
import com.diegonmarcos.superapp.network.NetworkInfoPopup
import com.diegonmarcos.superapp.network.SignalLevels
import com.diegonmarcos.superapp.network.TetherModel
import com.diegonmarcos.superapp.network.UsbDataModel
import com.diegonmarcos.superapp.zoomies.PetStrengthView

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.util.Base64
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Top status strip rendered when the SuperApp is the active default
 * Android launcher AND the active LauncherTheme is Cloud. Replaces the
 * hidden system status bar with our own 3-cluster row + bottom hairline:
 *
 *   ┌──────────────────────────────────────────────────────────────────┐
 *   │ [5G][WiFi][BT][WG][KDE][ADB][Data][HS]  dd-MM-yyyy HH:mm  [R][S][C][Bat] │
 *   │  ····  ····  ····   (signal dots, ~3dp)                          │
 *   │ ──────────────────────────────────────────────────────────────── │  hairline
 *   └──────────────────────────────────────────────────────────────────┘
 *
 *   LEFT  — network labels in NetworkSections.ORDER (the popup's section
 *           order too), each over a 4-dot row (SignalDots; 5G / WiFi / WG
 *           carry a signal level, BT the count of connected devices (BtLinks,
 *           4 = four or more), KDE / ADB / Data / HS keep the same empty
 *           footprint so every icon stays aligned). BT's label is white while
 *           the adapter is ON (ACL / profile / adapter broadcasts). Tinted
 *           by state, all event-driven: ConnectivityManager callback (5G /
 *           WiFi / WG), RSSI_CHANGED + the data SIM's signal callback (dots),
 *           a Settings.Global observer (ADB = USB or Wireless debugging),
 *           USB_STATE + OTG attach/detach (Data), TETHER_STATE_CHANGED +
 *           WIFI_AP_STATE_CHANGED (HS). WG is lit only ON the mesh (WgLink:
 *           a hub handshake under 3 min), NOT for "a VPN transport exists" —
 *           the engine's DNS-only tunnel is a VPN too. Its state + dots are
 *           re-read from the engine on every network change and on the
 *           clock's minute tick. 5G label tracks any cellular transport.
 *   CENTER — Date + time, monospace, centred. Updated every minute via
 *           ACTION_TIME_TICK + immediate refresh on TIMEZONE_CHANGED /
 *           TIME_CHANGED.
 *   RIGHT — RAM% used (ActivityManager.MemoryInfo) · Storage% used on
 *           /data (StatFs) · BatteryIconView (existing). Polled every
 *           10s on the main-thread ticker + on every time tick.
 *
 * MainActivity.applyLauncherChrome pushes the toolbar island down by
 * `topSystemInset + 6dp` in this theme so the strip's hairline has
 * breathing room above the dynamic island (the user pointed out they
 * were touching).
 */
class LauncherStatusStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : LinearLayout(context, attrs, defStyle) {

    private val signal5gView: TextView
    private val wifiView: TextView
    private val wgView: TextView
    private val btView: TextView
    private val dataView: TextView
    private val adbView: TextView
    private val hsView: TextView
    private val kdeView: TextView
    private val cellDots = SignalDots.create(context)
    private val wifiDots = SignalDots.create(context)
    private val wgDots = SignalDots.create(context)
    private val btDots = SignalDots.create(context)
    /** Icons with no level to show (KDE, ADB, Data, HS) still carry an empty dots row. */
    private fun emptyDots() = SignalDots.create(context)
    private val dateTimeView: TextView
    private val ramView: TextView
    private val storageView: TextView
    private val cpuView: TextView
    private val batteryView: BatteryIconView

    // Line 0 — one animated pet per Line-1 tool (vendored zoomies sprites).
    // Data-driven from BuildConfig.STATUS_PETS_B64 (build.json::status_pets):
    // each tool gets a FIXED animal; its strength maps to a gait level
    // (idle/walk/walk_fast/run). The pet sits in a vertical [pet, icon]
    // column so it's always directly above its icon.
    private data class ToolPet(
        val animal: String, val variant: String,
        val type: String, val onLevel: Int, val buckets: List<Int>,
    )
    private var petsEnabled = false
    private var petPx = 0
    private var gaits: List<String> = listOf("idle", "walk", "walk_fast", "run")
    private val toolCfg = HashMap<String, ToolPet>()
    private val petViews = HashMap<String, PetStrengthView>()

    /** Line 1's own top padding, and the only dp in the clock's vertical
     *  offset — [centreTopReservePx] subtracts it so the reserve below is
     *  measured from the strip's top edge rather than from Line 1's. */
    private val innerRowTopPadPx = (3 * resources.displayMetrics.density).toInt()
    /** The camera punch-hole's height in RAW PIXELS, from
     *  WindowInsetsCompat.Type.displayCutout(). 0 until the first inset
     *  dispatch, and on any phone whose screen has no cutout at all. */
    private var cutoutTopPx = 0
    /** Reserves [centreTopReservePx] above the clock inside the centre column. */
    private val cutoutSpacer: View = View(context)

    private var hasWifi = false
    private var hasCellular = false
    private var hasVpn = false
    private var hasBluetooth = false
    /** Connected Bluetooth devices (BtLinks), for the BT dots. */
    private var btCount = 0
    private var hasUsbData = false
    private var hasAdb = false
    private var hasHotspot = false
    private var hasKde = false
    // Signal levels (0..4, -1 = none) for the dots under 5G / WiFi / WG.
    private var cellLevel = SignalLevels.NONE
    private var wifiLevel = SignalLevels.NONE
    /** The mesh's truth (WgLink), read from the engine off the main thread; OFF until the first read. */
    private var wgLink = com.diegonmarcos.superapp.network.WgLink.Reading(
        com.diegonmarcos.superapp.network.WgLink.State.OFF, SignalLevels.NONE, "")
    // Tethering inputs: the last sticky TETHER_STATE_CHANGED / WIFI_AP_STATE_CHANGED / USB_STATE.
    private var tetherIfaces: List<String> = emptyList()
    private var apState: Int? = null
    private var usbTetherFn = false
    private val wgReading = java.util.concurrent.atomic.AtomicBoolean(false)
    /** Coalesces a burst of connectivity callbacks into one engine read. */
    private val wgRefresh = Runnable { refreshWgLevel() }
    /** API 31+ TelephonyCallback or the older PhoneStateListener, held as Any so neither class is touched on a release that lacks it. */
    private var signalListener: Any? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val metricsTicker = object : Runnable {
        override fun run() {
            refreshMetrics()
            // Reschedule only while live system stats are ON (Configs → Launcher →
            // "Live system stats"). OFF = frozen numbers, no 10s wakeup → leaner.
            if (com.diegonmarcos.superapp.settings.LauncherSettingsPrefs(context).toggle("status_live"))
                mainHandler.postDelayed(this, 10_000)
        }
    }

    init {
        orientation = VERTICAL
        setPadding(0, 0, 0, 0)
        // Transparent background — galaxy backdrop reads through the
        // strip so the camera-cutout area + the strip read as ONE
        // continuous galaxy band.
        setBackgroundColor(0x00000000)

        parsePetsConfig()

        val hpad = (10 * resources.displayMetrics.density).toInt()
        // FrameLayout (NOT horizontal LinearLayout) — lets the date/time
        // sit at gravity=CENTER which is TRUE screen-centre, aligned with
        // the dynamic-island pill below. A LinearLayout with weighted
        // columns would centre the date/time inside its column only —
        // and since LEFT cluster (~60dp) is narrower than RIGHT cluster
        // (~110dp), that "column centre" is biased rightward of screen
        // centre. FrameLayout positions each child independently via
        // layout_gravity, so LEFT anchors start, RIGHT anchors end, and
        // the centre child stays glued to screen midpoint.
        // Line 1 — the system-info row. WRAP_CONTENT (was 0+weight) so the
        // VERTICAL strip now stacks Line 0 (fixed) + Line 1 (content) + the
        // hairline instead of one weighted row filling a fixed barH. A small
        // vertical pad gives the icons breathing room.
        val innerRow = FrameLayout(context).apply {
            setPadding(hpad, innerRowTopPadPx, hpad, innerRowTopPadPx)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        // ── LEFT cluster: 5G · WiFi · BT · WG · KDE · ADB · Data · HS (anchored to START) ──
        val leftCluster = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.CENTER_VERTICAL,
            )
        }
        val nl = com.diegonmarcos.superapp.network.NetworkSections.LABELS
        signal5gView = makeIconLabel(nl.getValue(NetworkInfoPopup.CELLULAR))
        wifiView     = makeIconLabel(nl.getValue(NetworkInfoPopup.WIFI))
        wgView       = makeIconLabel(nl.getValue(NetworkInfoPopup.MESH))
        // BT — white while the adapter is ON (grey OFF); its dots count connected devices (BtLinks).
        btView       = makeIconLabel(nl.getValue(NetworkInfoPopup.BLUETOOTH))
        // ADB — lit while USB debugging or Wireless debugging is on (the two
        // Settings.Global switches, watched by a ContentObserver).
        adbView      = makeIconLabel(nl.getValue(NetworkInfoPopup.ADB))
        // Data — lit only when a USB-C cable is in a DATA mode (MTP/PTP/RNDIS/
        // NCM/MIDI…) or the phone is the OTG host; dim on charge-only or
        // unplugged. One rule, UsbDataModel, shared with the popup's section.
        dataView     = makeIconLabel(nl.getValue(NetworkInfoPopup.DATA))
        // HS — lit while Wi-Fi hotspot, USB or Bluetooth tethering is on
        // (TETHER_STATE_CHANGED + WIFI_AP_STATE_CHANGED, both sticky).
        hsView       = makeIconLabel(nl.getValue(NetworkInfoPopup.HOTSPOT))
        // KDE Connect — lit when ≥1 paired device is connected over the mesh.
        kdeView      = makeIconLabel(nl.getValue(NetworkInfoPopup.KDE))
        // Any of the left-cluster icons → NetworkInfoPopup (shared
        // popup per cluster, per Diego's "yes click any, they are a
        // cluster" answer), scrolled to the tapped icon's own section.
        // Reusing the same anchor (the tapped icon) keeps the bubble close
        // to where the user tapped.
        val focusOf = mapOf(
            signal5gView to NetworkInfoPopup.CELLULAR, wifiView to NetworkInfoPopup.WIFI,
            wgView to NetworkInfoPopup.MESH, kdeView to NetworkInfoPopup.KDE,
            btView to NetworkInfoPopup.BLUETOOTH, adbView to NetworkInfoPopup.ADB,
            dataView to NetworkInfoPopup.DATA, hsView to NetworkInfoPopup.HOTSPOT,
        )
        for ((v, focus) in focusOf) {
            v.isClickable = true
            v.setOnClickListener { NetworkInfoPopup.show(context, v, focus) }
        }
        // Each tool becomes a vertical [pet, icon] column → Line 0 pet sits
        // directly above its Line 1 icon, and the icon carries its signal
        // dots underneath. makeToolColumn falls back to the bare [icon, dots]
        // when pets are disabled or unconfigured for that tool.
        // In NetworkSections.ORDER — the same list the popup lays its sections out by.
        val columns = mapOf(
            NetworkInfoPopup.CELLULAR to { makeToolColumn("cellular", withDots(signal5gView, cellDots)) },
            NetworkInfoPopup.WIFI to { makeToolColumn("wifi", withDots(wifiView, wifiDots)) },
            NetworkInfoPopup.BLUETOOTH to { makeToolColumn("bluetooth", withDots(btView, btDots)) },
            NetworkInfoPopup.MESH to { makeToolColumn("vpn", withDots(wgView, wgDots)) },
            NetworkInfoPopup.KDE to { makeToolColumn("kde", withDots(kdeView, emptyDots())) },
            NetworkInfoPopup.ADB to { makeToolColumn("adb", withDots(adbView, emptyDots())) },
            NetworkInfoPopup.DATA to { makeToolColumn("usb", withDots(dataView, emptyDots())) },  // the old USB pet now rides Data
            NetworkInfoPopup.HOTSPOT to { makeToolColumn("hotspot", withDots(hsView, emptyDots())) },
        )
        for (column in com.diegonmarcos.superapp.network.NetworkSections.inOrder(columns)) leftCluster.addView(column())
        innerRow.addView(leftCluster)

        // ── CENTER: date + time, true screen-centre ────────────────
        dateTimeView = TextView(context).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setShadowLayer(4f, 0f, 1f, 0xCC000000.toInt())
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
            // Tap → CalendarAgendaPopup (next-7-days mini-list, same
            // data source as section:cal/agenda — placeholder for now,
            // wires to libs:cal once CalDAV slice D lands).
            isClickable = true
            setOnClickListener { CalendarAgendaPopup.show(context, this) }
        }
        // Center column: date/time on Line 1. A spacer reserves the Line 0
        // (pet) height so the clock stays aligned with the icon row instead
        // of floating in the vertical middle of the taller two-line strip.
        // No pet over the centre — that's the camera punch-hole.
        val centerCol = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
        }
        // #407 — THE CLOCK MUST CLEAR THE CAMERA AT EVERY SCALE STEP.
        // This strip deliberately draws INSIDE the display cutout: ShellActivity
        // sets LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES so the strip's top band
        // OWNS the camera row instead of being letterboxed below it, and that
        // stays true — the strip still starts at window y=0 and the galaxy still
        // fills the camera band. What changes is that the ONE child sitting on
        // the screen's centre line, where a Samsung punch-hole is, now keeps its
        // distance from the hole by the hole's OWN measurement.
        //
        // It cannot be a dp. SystemDisplay.applyScale writes `wm density`, so
        // every dp in this file converts to FEWER pixels at a reduced Scale step
        // while the camera stays the same physical pixels — a clearance tuned at
        // one step rides up under the lens at a smaller one, which is exactly
        // what the owner saw ("the home screen watch time date below the samsung
        // camera got hidden behind the camera"). displayCutout() is reported in
        // raw pixels off the real cutout, so it is the same number at all
        // eleven steps; and unlike systemBars() it is still dispatched while the
        // status bar is hidden, which in launcher mode it always is.
        //
        // Unconditional, not `if (petsEnabled)`: the pet row was only ever
        // incidental clearance, and a phone with pets off had none at all.
        centerCol.addView(cutoutSpacer, LinearLayout.LayoutParams(1, centreTopReservePx()))
        dateTimeView.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        centerCol.addView(dateTimeView)
        // The left cluster grew by the signal-dots row; the same reserve at the
        // bottom of the centre and right columns keeps every column's vertical
        // centre where it was, so the clock and the right icons do not drift.
        centerCol.setPadding(0, 0, 0, dotsRowPx())
        innerRow.addView(centerCol)

        // ── RIGHT cluster: RAM% · Storage% · Battery (anchored END) ─
        val rightCluster = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL,
            )
        }
        ramView     = makeIconLabel("R 0%")
        storageView = makeIconLabel("S 0%")
        cpuView     = makeIconLabel("C 0s")
        // RAM + Storage + CPU → shared SystemInfoPopup (per Diego's cluster
        // rule). Same anchor (tapped icon) as battery / network popups.
        // CPU = 1-min /proc/loadavg normalised by core count (the popup
        // already breaks out the 1m·5m·15m windows).
        val openSystemPopup = OnClickListener { v -> SystemInfoPopup.show(context, v) }
        for (v in listOf(ramView, storageView, cpuView)) {
            v.isClickable = true
            v.setOnClickListener(openSystemPopup)
        }
        batteryView = BatteryIconView(context).apply {
            val mlp = (4 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = mlp }
            // Tap → popup with "Estimated battery last" + session
            // metrics (same numbers Configs/About/Battery & Usage
            // shows, just one-glance accessible from the strip).
            isClickable = true
            setOnClickListener { BatteryEstimatePopup.show(context, this) }
        }
        rightCluster.addView(makeToolColumn("ram", ramView))
        rightCluster.addView(makeToolColumn("storage", storageView))
        rightCluster.addView(makeToolColumn("cpu", cpuView))
        rightCluster.addView(makeToolColumn("battery", batteryView))
        rightCluster.setPadding(0, 0, 0, dotsRowPx())
        innerRow.addView(rightCluster)

        // innerRow IS the two lines now: each tool's column stacks its pet
        // (Line 0) over its icon (Line 1). No separate band.
        addView(innerRow)

        // ── Bottom hairline ───────────────────────────────────────
        // Faint white separator pinned to the bottom of the strip.
        // MainActivity nudges the toolbar island down so there's a
        // 6dp gap between THIS hairline and the dynamic island below.
        addView(View(context).apply {
            setBackgroundColor(0x33FFFFFF.toInt())
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                maxOf(1, (resources.displayMetrics.density * 0.75f).toInt()),
            )
        })

        // Returns the insets UNCHANGED. This view reads the cutout, it does not
        // consume it: the siblings dispatched after it (the toolbar island) and
        // ShellActivity's own shell_linear listener must still see the same
        // window insets they saw before #407.
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.displayCutout()).top
            if (top != cutoutTopPx) {
                cutoutTopPx = top
                applyCutoutReserve()
            }
            insets
        }
    }

    /** How far below the strip's top edge the clock has to start: whichever is
     *  taller, the Line-0 pet row it has always been aligned to, or the display
     *  cutout. Measured from the strip's top edge — in launcher mode the system
     *  bars are hidden, systemBars().top is dispatched as 0, shell_linear's
     *  paddingTop is 0 and this strip therefore sits at window y=0, which is the
     *  same origin displayCutout() measures from. */
    private fun centreTopReservePx(): Int =
        maxOf(if (petsEnabled) petPx else 0, cutoutTopPx - innerRowTopPadPx)

    private fun applyCutoutReserve() {
        cutoutSpacer.layoutParams = cutoutSpacer.layoutParams.apply {
            height = centreTopReservePx()
        }
        cutoutSpacer.requestLayout()
    }

    /** Decode BuildConfig.STATUS_PETS_B64 (build.json::status_pets) → per-tool
     *  animal config. Empty/disabled-safe: leaves petsEnabled=false so the
     *  strip renders as the plain icon row. */
    private fun parsePetsConfig() {
        runCatching {
            val b64 = BuildConfig.STATUS_PETS_B64
            if (b64.isBlank()) return
            val o = JSONObject(String(Base64.decode(b64, Base64.DEFAULT)))
            petsEnabled = o.optBoolean("enabled", false)
            petPx = (o.optInt("px", 20) * resources.displayMetrics.density).toInt()
            o.optJSONArray("gaits")?.let { g -> gaits = (0 until g.length()).map { g.getString(it) } }
            val tools = o.optJSONObject("tools") ?: return
            for (k in tools.keys()) {
                val t = tools.getJSONObject(k)
                val buckets = t.optJSONArray("buckets")
                    ?.let { b -> (0 until b.length()).map { b.getInt(it) } } ?: emptyList()
                toolCfg[k] = ToolPet(
                    t.optString("animal"), t.optString("variant"),
                    t.optString("type", "bool"), t.optInt("on_level", gaits.lastIndex), buckets,
                )
            }
        }
    }

    /** The icon over its signal dots, centred: the dots row is the only height added to Line 1. */
    private fun withDots(icon: TextView, dots: LinearLayout): View = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(icon)
        addView(dots)
    }

    /** Height of one dots row, for the other columns' matching reserve. */
    private fun dotsRowPx(): Int = SignalDots.heightPx(context)

    /** Wrap a tool's [iconView] in a vertical [pet, icon] column so its pet
     *  sits on Line 0 directly above the icon on Line 1. Returns the bare
     *  iconView unchanged when pets are off / unconfigured for this tool. */
    private fun makeToolColumn(toolId: String, iconView: View): View {
        val cfg = toolCfg[toolId]
        if (!petsEnabled || cfg == null) return iconView
        val pet = PetStrengthView(context).apply {
            layoutParams = LinearLayout.LayoutParams(petPx, petPx)
            // Configs → Launcher → Others → "Animal animations" gate. Read once
            // (the strip re-inits when the toggle flips via chrome re-render).
            animate = runCatching {
                com.diegonmarcos.superapp.settings.LauncherSettingsPrefs(context).anim("pets_anim")
            }.getOrDefault(true)
            setAnimal(cfg.animal, cfg.variant)
            setGait(gaits.firstOrNull() ?: "idle")
        }
        petViews[toolId] = pet
        return LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(pet)
            addView(iconView)
        }
    }

    private fun gaitForLevel(level: Int): String =
        gaits.getOrElse(level.coerceIn(0, gaits.lastIndex)) { gaits.last() }

    /** percent → level by counting how many ascending [buckets] it clears. */
    private fun bucketLevel(pct: Int, buckets: List<Int>): Int =
        buckets.count { pct >= it }

    private fun updatePet(toolId: String, level: Int) {
        petViews[toolId]?.setGait(gaitForLevel(level))
    }

    private fun updateBoolPet(toolId: String, on: Boolean) {
        val cfg = toolCfg[toolId] ?: return
        updatePet(toolId, if (on) cfg.onLevel else 0)
    }

    /** Configs → Launcher → Others → "Animal animations". Re-read the pref and
     *  freeze/resume every pet. Public so MainActivity can re-apply LIVE — the
     *  strip lives in the activity shell and isn't recreated on a chrome
     *  re-render, so the init-time read wouldn't pick up a toggle flip. */
    fun applyPetsPref() {
        val on = runCatching {
            com.diegonmarcos.superapp.settings.LauncherSettingsPrefs(context).anim("pets_anim")
        }.getOrDefault(true)
        petViews.values.forEach { it.animate = on }
    }

    /** Shared small monospace label used by left + right cluster
     *  members. Color set per-state via [applyIconTints] /
     *  [refreshMetrics]. */
    private fun makeIconLabel(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(0x66FFFFFF.toInt())
        textSize = 10f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setShadowLayer(3f, 0f, 1f, 0xCC000000.toInt())
        val px = (3 * resources.displayMetrics.density).toInt()
        setPadding(px, 0, px, 0)
        maxLines = 1
    }

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            refreshTime()
            // Piggy-back metrics on the once-a-minute tick (cheap vs the 10s
            // poll) — but ONLY while live stats are on, so "Live system stats"
            // OFF truly freezes RAM/CPU/storage. The clock keeps ticking.
            if (com.diegonmarcos.superapp.settings.LauncherSettingsPrefs(context).toggle("status_live"))
                refreshMetrics()
            // A WireGuard handshake ages with no event to say so: re-read its
            // level on the clock's own once-a-minute tick (no extra timer).
            refreshWgLevel()
        }
    }
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { refreshBattery(i) }
    }
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            // ATTACHED / DETACHED of an OTG device carry no USB_STATE: re-read the sticky one.
            val state = if (i.action == USB_STATE) i
                else runCatching { context.registerReceiver(null, IntentFilter(USB_STATE)) }.getOrNull()
            refreshUsb(state)
        }
    }
    private val tetherReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { refreshTether(i) }
    }
    /** Adapter on/off, ACL + profile connects/disconnects, battery levels: re-read BT. */
    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            com.diegonmarcos.superapp.network.BtLinks.noteBattery(i)
            refreshBluetooth()
        }
    }
    private val rssiReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { refreshWifiLevel() }
    }
    /** USB debugging / Wireless debugging flips: Settings.Global, observed, never polled. */
    private val adbObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { refreshAdb() }
    }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            post { refreshNetworkFromConnectivity() }
        }
        override fun onLost(network: Network) {
            post { refreshNetworkFromConnectivity() }
        }
        override fun onAvailable(network: Network) {
            post { refreshNetworkFromConnectivity() }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        runCatching {
            context.registerReceiver(timeReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_TIME_CHANGED)
            })
        }
        runCatching {
            val battery = context.registerReceiver(
                batteryReceiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            )
            if (battery != null) refreshBattery(battery)
        }
        runCatching {
            // ACTION_USB_STATE is a sticky broadcast — registering returns
            // the current USB state intent, so the icon is correct on attach.
            val usb = context.registerReceiver(
                usbReceiver,
                IntentFilter("android.hardware.usb.action.USB_STATE"),
            )
            if (usb != null) refreshUsb(usb)
            context.registerReceiver(usbReceiver, IntentFilter().apply {
                addAction("android.hardware.usb.action.USB_DEVICE_ATTACHED")
                addAction("android.hardware.usb.action.USB_DEVICE_DETACHED")
            })
        }
        runCatching {
            // Both sticky: registering hands back the current tethering / AP state.
            context.registerReceiver(tetherReceiver, IntentFilter(TETHER_STATE))?.let { refreshTether(it) }
            context.registerReceiver(tetherReceiver, IntentFilter(AP_STATE))?.let { refreshTether(it) }
        }
        runCatching {
            context.registerReceiver(rssiReceiver, IntentFilter(android.net.wifi.WifiManager.RSSI_CHANGED_ACTION))
        }
        runCatching {
            // System broadcasts only: NOT_EXPORTED still hears the Bluetooth stack, no other app can poke it.
            androidx.core.content.ContextCompat.registerReceiver(context, btReceiver, IntentFilter().apply {
                com.diegonmarcos.superapp.network.BtLinks.ACTIONS.forEach { addAction(it) }
            }, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        com.diegonmarcos.superapp.network.BtLinks.onProxy = { post { refreshBluetooth() } }
        runCatching {
            val cr = context.contentResolver
            cr.registerContentObserver(android.provider.Settings.Global.getUriFor(android.provider.Settings.Global.ADB_ENABLED), false, adbObserver)
            cr.registerContentObserver(android.provider.Settings.Global.getUriFor(ADB_WIFI), false, adbObserver)
        }
        registerSignalListener()
        refreshAdb()
        refreshWgLevel()
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.registerNetworkCallback(
                NetworkRequest.Builder().build(),
                networkCallback,
            )
        }
        refreshTime()
        refreshNetworkFromConnectivity()
        refreshMetrics()   // one render → static numbers even when live stats are off
        // Start the 10s RAM/CPU/storage refresh loop only when enabled. Off keeps
        // the frozen values on screen (UI stays intact) but skips the polling.
        if (com.diegonmarcos.superapp.settings.LauncherSettingsPrefs(context).toggle("status_live"))
            mainHandler.post(metricsTicker)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        runCatching { context.unregisterReceiver(timeReceiver) }
        runCatching { context.unregisterReceiver(batteryReceiver) }
        runCatching { context.unregisterReceiver(usbReceiver) }
        runCatching { context.unregisterReceiver(tetherReceiver) }
        runCatching { context.unregisterReceiver(rssiReceiver) }
        runCatching { context.unregisterReceiver(btReceiver) }
        com.diegonmarcos.superapp.network.BtLinks.onProxy = null
        runCatching { context.contentResolver.unregisterContentObserver(adbObserver) }
        unregisterSignalListener()
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.unregisterNetworkCallback(networkCallback)
        }
        mainHandler.removeCallbacks(metricsTicker)
        removeCallbacks(wgRefresh)
    }

    private fun refreshTime() {
        dateTimeView.text = SimpleDateFormat("dd-MM-yyyy HH:mm EEE", Locale.US).format(Date())
    }

    private fun refreshBattery(intent: Intent) {
        val level    = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale    = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status   = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        batteryView.setBattery(pct, charging)
        if (pct >= 0) toolCfg["battery"]?.let { updatePet("battery", bucketLevel(pct, it.buckets)) }
    }

    /** USB cable DATA-transfer state from ACTION_USB_STATE (+ any attached
     *  OTG device). "Data" = a data function active (MTP/PTP/RNDIS/NCM/MIDI…)
     *  or we're the OTG host — NOT charge-only. The rule is UsbDataModel's,
     *  the same one the popup's Data section prints. */
    private fun refreshUsb(intent: Intent?) {
        val extras = UsbDataModel.EXTRA_KEYS.associateWith { intent?.getBooleanExtra(it, false) == true }
        val otg = runCatching {
            (context.getSystemService(Context.USB_SERVICE) as? android.hardware.usb.UsbManager)
                ?.deviceList?.keys?.toList()
        }.getOrNull() ?: emptyList()
        hasUsbData = UsbDataModel.state(extras, 0, otg).data
        usbTetherFn = extras["rndis"] == true || extras["ncm"] == true
        hasHotspot = TetherModel.State(tetherIfaces, apState, usbTetherFn).active
        applyIconTints()
    }

    /** TETHER_STATE_CHANGED ("tetherArray" = tethered interfaces) or
     *  WIFI_AP_STATE_CHANGED ("wifi_state"); each updates its half. */
    private fun refreshTether(i: Intent) {
        when (i.action) {
            TETHER_STATE -> tetherIfaces = runCatching { i.getStringArrayListExtra("tetherArray") }.getOrNull()?.toList() ?: emptyList()
            AP_STATE -> apState = i.getIntExtra("wifi_state", -1).takeIf { it >= 0 }
        }
        hasHotspot = TetherModel.State(tetherIfaces, apState, usbTetherFn).active
        applyIconTints()
    }

    private fun refreshAdb() {
        val cr = context.contentResolver
        val usb = runCatching { android.provider.Settings.Global.getInt(cr, android.provider.Settings.Global.ADB_ENABLED, 0) == 1 }.getOrDefault(false)
        // Wireless debugging: the lib's own read (Settings.Global adb_wifi_enabled, API 30+).
        val wifi = runCatching { com.diegonmarcos.superapp.adbdebug.WirelessDebugging.isOn(context) }.getOrDefault(false)
        hasAdb = usb || wifi
        applyIconTints()
    }

    /** Wi-Fi RSSI → 0..4 (RSSI_CHANGED_ACTION + every connectivity change). No location needed for RSSI. */
    private fun refreshWifiLevel() {
        wifiLevel = if (!hasWifi) SignalLevels.NONE else runCatching {
            @Suppress("DEPRECATION")
            val rssi = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager)
                ?.connectionInfo?.rssi
            SignalLevels.wifi(rssi)
        }.getOrDefault(SignalLevels.NONE)
        applyIconTints()
    }

    /** WG state + level from the engine (WgLink: tunnel state + freshest peer handshake). The engine
     *  is another process (binder), so it is read off the main thread, at most one read in flight. */
    private fun refreshWgLevel() {
        if (!wgReading.compareAndSet(false, true)) return
        val app = context.applicationContext
        val vpn = hasVpn
        Thread {
            val r = try { NetworkInfoPopup.wgLink(app, vpn) }
                catch (_: Throwable) { com.diegonmarcos.superapp.network.WgLink.derive(false, emptyList(), 0L) }
                finally { wgReading.set(false) }
            post { wgLink = r; applyIconTints() }
        }.start()
    }

    /** Cellular level of the DATA SIM, pushed by the radio (no permission needed for signal strength). */
    private fun registerSignalListener() {
        if (signalListener != null) return
        runCatching {
            val tm = dataSimTelephony() ?: return
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                val cb = SignalCallback31 { lvl -> post { cellLevel = SignalLevels.cell(lvl); applyIconTints() } }
                tm.registerTelephonyCallback(context.mainExecutor, cb)
                signalListener = cb
            } else {
                @Suppress("DEPRECATION")
                val l = object : android.telephony.PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onSignalStrengthsChanged(ss: android.telephony.SignalStrength?) {
                        cellLevel = SignalLevels.cell(ss?.level); applyIconTints()
                    }
                }
                @Suppress("DEPRECATION")
                tm.listen(l, android.telephony.PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
                signalListener = l
            }
        }
    }

    private fun unregisterSignalListener() {
        val l = signalListener ?: return
        signalListener = null
        runCatching {
            val tm = dataSimTelephony() ?: return
            if (android.os.Build.VERSION.SDK_INT >= 31 && l is android.telephony.TelephonyCallback) tm.unregisterTelephonyCallback(l)
            else if (l is android.telephony.PhoneStateListener) {
                @Suppress("DEPRECATION")
                tm.listen(l, android.telephony.PhoneStateListener.LISTEN_NONE)
            }
        }
    }

    private fun dataSimTelephony(): android.telephony.TelephonyManager? {
        val tm = context.applicationContext.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager ?: return null
        val sub = runCatching { android.telephony.SubscriptionManager.getDefaultDataSubscriptionId() }.getOrDefault(-1)
        return if (sub >= 0) runCatching { tm.createForSubscriptionId(sub) }.getOrDefault(tm) else tm
    }

    /** Walk all known networks via ConnectivityManager and decide
     *  which of the 3 LEFT labels should light up. VPN is checked
     *  across ALL networks (a VPN can co-exist with the active
     *  Wi-Fi / cellular network, and we want the WG indicator
     *  bright in that case). */
    private fun refreshNetworkFromConnectivity() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        var wifi = false; var cell = false; var vpn = false
        runCatching {
            cm?.allNetworks?.forEach { n ->
                cm.getNetworkCapabilities(n)?.let { c ->
                    if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))     wifi = true
                    if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) cell = true
                    if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN))      vpn  = true
                }
            }
        }
        hasWifi = wifi; hasCellular = cell; hasVpn = vpn
        refreshBluetooth(apply = false)
        refreshWifiLevel()   // also applies the tints
        // Any network change can take the hubs away (Wi-Fi -> cellular, a VPN swap): re-ask the
        // engine, debounced, instead of trusting the VPN transport (the DNS-only tunnel is one too).
        removeCallbacks(wgRefresh)
        postDelayed(wgRefresh, 500)
    }

    /** Adapter on/off (the BT label's tint) + connected devices (its dots), one BtLinks reading.
     *  Without BLUETOOTH_CONNECT the count is 0; the popup's BT section says what to grant. */
    private fun refreshBluetooth(apply: Boolean = true) {
        val r = runCatching { com.diegonmarcos.superapp.network.BtLinks.read(context) }.getOrNull()
        hasBluetooth = r?.adapterOn == true
        btCount = r?.count ?: 0
        if (apply) applyIconTints()
    }

    private fun applyIconTints() {
        val on  = com.diegonmarcos.superapp.network.WgLink.TINT_ON
        val off = com.diegonmarcos.superapp.network.WgLink.TINT_OFF
        signal5gView.setTextColor(if (hasCellular)  on else off)
        wifiView    .setTextColor(if (hasWifi)      on else off)
        wgView      .setTextColor(com.diegonmarcos.superapp.network.WgLink.tint(wgLink.state))
        btView      .setTextColor(com.diegonmarcos.superapp.network.BtLinks.tint(hasBluetooth))
        adbView     .setTextColor(if (hasAdb)       on else off)
        dataView    .setTextColor(if (hasUsbData)   on else off)
        hsView      .setTextColor(if (hasHotspot)   on else off)
        // Signal dots take the icon's own tint (bright when active, faint when not).
        SignalDots.set(cellDots, cellLevel, if (hasCellular) on else off)
        SignalDots.set(wifiDots, wifiLevel, if (hasWifi) on else off)
        // BT dots = connected devices (0..4, 4 = four or more); all four grey at 0.
        SignalDots.set(btDots, com.diegonmarcos.superapp.network.BtLinks.dots(btCount), com.diegonmarcos.superapp.network.BtLinks.tint(hasBluetooth))
        SignalDots.set(wgDots, if (wgLink.onMesh) wgLink.level else SignalLevels.NONE, com.diegonmarcos.superapp.network.WgLink.tint(wgLink.state))
        hasKde = runCatching {
            com.diegonmarcos.superapp.kdeconnect.KdeConnectManager.connectedIds().isNotEmpty()
        }.getOrDefault(false)
        kdeView     .setTextColor(if (hasKde)       on else off)
        // Pets: on → run (energetic), off → idle. on_level is data-driven.
        updateBoolPet("cellular", hasCellular)
        updateBoolPet("wifi", hasWifi)
        updateBoolPet("vpn", wgLink.onMesh)
        updateBoolPet("bluetooth", hasBluetooth)
        updateBoolPet("usb", hasUsbData)
        updateBoolPet("adb", hasAdb)
        updateBoolPet("hotspot", hasHotspot)
        updateBoolPet("kde", hasKde)
    }

    /** Read RAM + /data storage utilisation and update the right
     *  cluster labels. Both reads are cheap (no IO) and safe to call
     *  on the main thread. */
    private fun refreshMetrics() {
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.let {
                val info = ActivityManager.MemoryInfo()
                it.getMemoryInfo(info)
                val pct = ((1.0 - info.availMem.toDouble() / info.totalMem.toDouble()) * 100).toInt()
                ramView.text = "R$pct%"
                toolCfg["ram"]?.let { updatePet("ram", bucketLevel(pct, it.buckets)) }
            }
        }
        runCatching {
            val path = Environment.getDataDirectory().absolutePath
            val stat = StatFs(path)
            val total = stat.blockCountLong * stat.blockSizeLong
            val avail = stat.availableBlocksLong * stat.blockSizeLong
            val pct = ((1.0 - avail.toDouble() / total.toDouble()) * 100).toInt()
            storageView.text = "S$pct%"
            toolCfg["storage"]?.let { updatePet("storage", bucketLevel(pct, it.buckets)) }
        }
        runCatching {
            // CPU load — the raw 1-minute /proc/loadavg figure, SAME source +
            // value the SystemInfoPopup shows (SysfsProc.cpuLoad().loadAvg1m).
            // Suffixed "s" per Diego's spec; NOT a core-normalised percentage
            // (that rounded to 0 on an idle phone — the bug being fixed).
            com.diegonmarcos.superapp.battery.SysfsProc.cpuLoad()?.let { c ->
                cpuView.text = "C%.2fs".format(c.loadAvg1m)
                // pet bucket still wants an int "level"; reuse the normalised
                // load% (0-100+) purely for that, independent of the label.
                val lvlPct = (c.loadAvg1m / c.cores.coerceAtLeast(1) * 100).toInt().coerceAtLeast(0)
                toolCfg["cpu"]?.let { updatePet("cpu", bucketLevel(lvlPct, it.buckets)) }
            }
        }
    }

    private companion object {
        const val USB_STATE = "android.hardware.usb.action.USB_STATE"
        /** ConnectivityManager.ACTION_TETHER_STATE_CHANGED (hidden constant, sticky since API 8). */
        const val TETHER_STATE = "android.net.conn.TETHER_STATE_CHANGED"
        /** WifiManager.WIFI_AP_STATE_CHANGED_ACTION (hidden constant, sticky). */
        const val AP_STATE = "android.net.wifi.WIFI_AP_STATE_CHANGED"
        /** Settings.Global.ADB_WIFI_ENABLED (@hide, API 30+). */
        const val ADB_WIFI = "adb_wifi_enabled"
    }
}

/** API 31+ signal callback, in its own class so pre-31 runtimes never load TelephonyCallback. */
@androidx.annotation.RequiresApi(31)
private class SignalCallback31(private val onLevel: (Int) -> Unit) :
    android.telephony.TelephonyCallback(), android.telephony.TelephonyCallback.SignalStrengthsListener {
    override fun onSignalStrengthsChanged(ss: android.telephony.SignalStrength) { onLevel(ss.level) }
}

/**
 * SignalDots(level, tint): four tiny dots in a row, filled left to right for a 0..4 signal level,
 * the unfilled ones a faint copy of the same tint. Sits centred under a status-strip icon (~2 dp
 * dots, ~1.5 dp apart, ~1 dp above them), so the row grows ~3 dp and the icon keeps its size.
 * Level -1 draws nothing but keeps the same footprint, so an icon with no signal (ADB, Data, HS,
 * BT) stays aligned with its neighbours.
 *
 * Built from four plain Views with oval backgrounds rather than a View subclass, and kept in this
 * file rather than a new one: the app's custom-view and View-file counts only go down
 * (cloud-android-compose-ratchet.py). Internal, so any View-based surface of the app can reuse it.
 */
internal object SignalDots {

    /** A new, empty (level -1) dots row. */
    fun create(ctx: Context): LinearLayout {
        val d = ctx.resources.displayMetrics.density
        val dot = maxOf(2, (2f * d + 0.5f).toInt())
        val gap = maxOf(1, (1.5f * d + 0.5f).toInt())
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, maxOf(1, (1f * d + 0.5f).toInt()), 0, 0)
            for (i in 0 until 4) addView(View(ctx).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0) }
            }, LinearLayout.LayoutParams(dot, dot).apply { if (i > 0) marginStart = gap })
            tag = Pair(-1, 0)
        }
    }

    /** [level] 0..4 (or -1 = none); [tint] the icon's current text colour. Repaints only on change. */
    fun set(row: LinearLayout, level: Int, tint: Int) {
        val l = level.coerceIn(-1, 4)
        if (row.tag == Pair(l, tint)) return
        row.tag = Pair(l, tint)
        val alpha = (tint ushr 24) and 0xFF
        for (i in 0 until row.childCount) {
            val a = when {
                l < 0 -> 0
                i < l -> alpha
                else -> (alpha * 0.3f).toInt()   // unfilled: same colour, ~30% of the icon's alpha
            }
            (row.getChildAt(i).background as? GradientDrawable)?.setColor((tint and 0x00FFFFFF) or (a shl 24))
        }
    }

    /** Height a dots row adds under an icon, for the columns that carry none. */
    fun heightPx(ctx: Context): Int = create(ctx).let { it.measure(0, 0); it.measuredHeight }
}
