package com.diegonmarcos.superapp.kdeconnect

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.launch
import kotlin.math.abs

/** The two status lights' colours, resolved from kde_dot_* when drawn. */
internal enum class KdeDot(val res: Int) {
    IDLE(R.color.kde_dot_idle),   // gray  — not connected
    BUSY(R.color.kde_dot_busy),   // amber — connecting/handshaking
    OK(R.color.kde_dot_ok),       // green — connected & paired
    ERR(R.color.kde_dot_err),     // red   — error
}

/** Which palette role a result line is drawn in. */
internal enum class KdeTone { PLAIN, DIM, OK, WARN, BAD }

/** One line of the probe trace / self-test result. */
internal class KdeTestLine(val text: String, val tone: KdeTone, val bold: Boolean = false)

/** The selected peer's card state: effective name/host/port plus the two lights. */
internal class KdePeerState(
    val device: KdeConnectConfig.Device,
    label: String,
    host: String,
    port: Int,
    pairedBefore: Boolean,
) {
    var label by mutableStateOf(label)
    var host by mutableStateOf(host)       // effective (override or default) wg ip
    var port by mutableStateOf(port)
    var deviceId: String? = null
    var connDot by mutableStateOf(KdeDot.IDLE)
    var connText by mutableStateOf("Not connected")
    var pairDot by mutableStateOf(if (pairedBefore) KdeDot.OK else KdeDot.IDLE)
    var pairText by mutableStateOf(if (pairedBefore) "Paired" else "Not paired")
    var pingEnabled by mutableStateOf(pairedBefore)   // tappable once paired (auto-reconnects)
    var pairLabel by mutableStateOf("Pair")
}

/**
 * Configs ▸ KDE page — driver for our SELF-CONTAINED KDE-Connect client
 * ([KdeConnectManager]). No org.kde app, no middleman: each declared device
 * (build.json::ui.kde_connect.devices) gets Connect → Pair → Ping over the
 * wg0 mesh, with live state. Pairing remembers the peer's TLS cert; reconnects
 * are cert-pinned.
 *
 * 2026-10-16 owner decision: all apps must be Compose. The page is a ComposeView under
 * CloudKitTheme (libs:ui-kit) with its palette in the kde_* colour resources; the host
 * contract (newInstance, the peer selector's test tag) is unchanged.
 */
class KdeConnectFragment : Fragment(), KdeConnectManager.Listener {

    /** SAF picker for "Send file" → KDE share payload upload. Registered at
     *  field init (before the fragment is STARTED) per the ActivityResult API. */
    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { onFilePicked(it) }
    }

    /** The one peer card on the page (null when no peer is declared). */
    private var peer: KdePeerState? by mutableStateOf(null)

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        KdeConnectManager.init(ctx)
        val cfg = KdeConnectConfig.get()
        // #733 THE PEER SELECTOR, at the top: which peer this whole page talks
        // to. The list is KdePeers.all() — declared KDE devices, then the wg
        // mesh nodes — and only the selected peer's card is drawn, so every
        // action below has exactly one target.
        val peers = KdePeers.all()
        selectPeer(ctx, KdePeers.selected(ctx, peers))
        return ComposeView(ctx).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val palette = KitPalette(
                    surface = colorResource(R.color.kde_surface),
                    surfaceSelected = colorResource(R.color.kde_surface_selected),
                    textPrimary = colorResource(R.color.kde_text),
                    textSecondary = colorResource(R.color.kde_text_dim),
                    accent = colorResource(R.color.kde_accent),
                    hairline = colorResource(R.color.kde_hairline),
                    tileInk = colorResource(R.color.kde_ink),
                    ok = colorResource(R.color.kde_ok),
                    warn = colorResource(R.color.kde_warn),
                    bad = colorResource(R.color.kde_bad),
                )
                CloudKitTheme(palette) { Page(ctx, cfg, peers) }
            }
        }
    }

    @Composable
    private fun Page(ctx: Context, cfg: KdeConnectConfig.Config, peers: List<KdeConnectConfig.Device>) {
        val p = LocalKitPalette.current
        val self = remember {
            "Self-contained client · this device = ${KdeIdentity.deviceName(ctx)} " +
                "(${KdeConnectManager.ownDeviceId().take(14)}…)"
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text("KDE Connect", color = p.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 2.dp))
            Text(self, color = p.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(bottom = 16.dp))

            PeerSelector(ctx, peers)
            peer?.let { DeviceCard(ctx, it) }

            // Clipboard — right after the device cards, because everything it
            // does needs a paired device to be there first.
            ClipboardCard(ctx)
            // Remote control (touchpad / keyboard / big-screen senders)
            RemoteCard()
            // Media remote + system volume (mpris / systemvolume senders)
            MediaCard()
            // Presenter (laser pointer + slide nav)
            PresenterCard()
            // One-shot actions (ring / browse / virtual monitor)
            ActionsCard()
            // Remote Desktop control (session + mouse/keyboard surface)
            RemoteDesktopCard()
            // Share text/links to the desktop (share sender)
            ShareCard()
            // Photo capture (open camera)
            PhotoCard(ctx)
            // Send SMS (this phone sends it)
            SmsCard(ctx)
            // Plugins catalog (data-driven from build.json)
            if (cfg.plugins.isNotEmpty()) PluginsCard(ctx, cfg)
            // Self-test — exercise every enabled plugin
            SelfTestCard(ctx, cfg)
            // Probe section — wg0 / hosts / peers / LAN reachability
            ProbeSection(ctx)

            // Explicit hand-off to the official client (separate from our own
            // self-contained pairing) — opens it if installed, else a toast.
            Spacer8()
            WideBtn("Open KDE Connect (App)", 12.sp) { openClient(cfg.pkg) }

            Text(
                "Connect dials <device>:${cfg.discoveryPort} over wg0, runs the KDE Connect " +
                    "TLS handshake (we are the TLS server), and re-exchanges identity encrypted. " +
                    "Pair once to trust the device's certificate; later connections are cert-pinned.",
                color = p.textSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 16.dp),
            )
        }
    }

    // ── Small building blocks ─────────────────────────────────────────────

    @Composable
    private fun Spacer8() { Box(Modifier.height(8.dp)) }

    /** A rounded card with a bold title + dim subtitle, then [content]. */
    @Composable
    private fun KdeCard(title: String, subtitle: String?, content: @Composable ColumnScope.() -> Unit) {
        val p = LocalKitPalette.current
        KitCard(Modifier.padding(bottom = 12.dp)) {
            Column {
                Text(title, color = p.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                if (subtitle != null) {
                    Text(subtitle, color = p.textSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                }
            }
            content()
        }
    }

    /** One compact button of an equal-weight row. */
    @Composable
    private fun RowScope.Btn(
        label: String,
        onClick: () -> Unit,
        weight: Float = 1f,
        enabled: Boolean = true,
        size: TextUnit = 12.sp,
    ) {
        com.diegonmarcos.superapp.uikit.KitButton(
            onClick = onClick, enabled = enabled, modifier = Modifier.weight(weight),
        ) { Text(label, fontSize = size, maxLines = 1) }
    }

    /** A horizontal row of equal-weight compact buttons (label → action). */
    @Composable
    private fun BtnRow(vararg btns: Pair<String, () -> Unit>) {
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((label, action) in btns) Btn(label = label, onClick = action)
        }
    }

    /** A full-width button. */
    @Composable
    private fun WideBtn(label: String, size: TextUnit = 12.sp, onClick: () -> Unit) {
        com.diegonmarcos.superapp.uikit.KitButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(label, fontSize = size) }
    }

    /** A small round status light + its text. */
    @Composable
    private fun LightRow(dot: KdeDot, text: String) {
        val p = LocalKitPalette.current
        Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.padding(end = 7.dp).size(9.dp).clip(CircleShape).background(colorResource(dot.res)))
            Text(text, color = p.accent, fontSize = 12.5.sp)
        }
    }

    @Composable
    private fun toneColor(t: KdeTone): Color {
        val p = LocalKitPalette.current
        return when (t) {
            KdeTone.PLAIN -> p.textPrimary
            KdeTone.DIM -> p.textSecondary
            KdeTone.OK -> p.ok
            KdeTone.WARN -> p.warn
            KdeTone.BAD -> p.bad
        }
    }

    @Composable
    private fun MonoLines(lines: List<KdeTestLine>, size: TextUnit) {
        for (l in lines) {
            Text(l.text, color = toneColor(l.tone), fontFamily = FontFamily.Monospace, fontSize = size,
                fontWeight = if (l.bold) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.padding(vertical = 2.dp))
        }
    }

    @Composable
    private fun KdeField(value: String, onChange: (String) -> Unit, hint: String, type: KeyboardType = KeyboardType.Text) {
        OutlinedTextField(
            value = value, onValueChange = onChange, singleLine = true,
            placeholder = { Text(hint) },
            keyboardOptions = KeyboardOptions(keyboardType = type),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    /**
     * A touch surface: [onMove] gets each drag delta (px) and the surface size, [onUp] gets whether the
     * finger moved and how long it was held (ms) when it lifts.
     */
    @Composable
    private fun Pad(
        height: Int,
        tag: String,
        onMove: (dx: Float, dy: Float, w: Float, h: Float) -> Unit,
        onUp: (moved: Boolean, heldMs: Long) -> Unit,
    ) {
        val p = LocalKitPalette.current
        val move by rememberUpdatedState(onMove)
        val up by rememberUpdatedState(onUp)
        val shape = RoundedCornerShape(10.dp)
        Box(
            Modifier.fillMaxWidth().height(height.dp).clip(shape)
                .background(p.surfaceSelected).border(1.dp, p.hairline, shape)
                .testTag(tag)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        var last: Offset = down.position
                        var moved = false
                        var held = 0L
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            held = ch.uptimeMillis - down.uptimeMillis
                            if (!ch.pressed) break
                            val d = ch.position - last
                            last = ch.position
                            if (abs(d.x) > 2 || abs(d.y) > 2) moved = true
                            if (d != Offset.Zero) move(d.x, d.y, size.width.toFloat(), size.height.toFloat())
                            ch.consume()
                        }
                        up(moved, held)
                    }
                },
        )
    }

    // ── Peer selector + device card ───────────────────────────────────────

    @Composable
    private fun PeerSelector(ctx: Context, peers: List<KdeConnectConfig.Device>) {
        val p = LocalKitPalette.current
        if (peers.isEmpty()) {
            Text("No peers declared in build.json::ui.kde_connect.devices or data/mesh.json", color = p.bad)
            return
        }
        var open by remember { mutableStateOf(false) }
        val cur = peer
        com.diegonmarcos.superapp.uikit.KitButton(
            onClick = { open = true },
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp).testTag(PEER_SELECTOR_TAG),
        ) {
            Text(cur?.let { "Peer: ${it.device.label} · ${it.device.wgIp}  ▾" } ?: "Peer: none", fontSize = 12.sp)
        }
        if (open) {
            AlertDialog(
                onDismissRequest = { open = false },
                title = { Text("Peer") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        for (d in peers) {
                            com.diegonmarcos.superapp.uikit.KitTextButton(
                                onClick = { KdePeers.select(ctx, d.id); selectPeer(ctx, d); open = false },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("${d.label} · ${d.wgIp}", modifier = Modifier.fillMaxWidth()) }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { com.diegonmarcos.superapp.uikit.KitTextButton(onClick = { open = false }) { Text("Cancel") } },
            )
        }
    }

    /** Make [device] the one card on the page (effective name/host/port come from the Edit overrides). */
    private fun selectPeer(ctx: Context, device: KdeConnectConfig.Device?) {
        peer = device?.let {
            val dprefs = KdeDevicePrefs(ctx)
            val paired = it.id.isNotBlank() && KdeConnectManager.isPaired(it.id)
            KdePeerState(
                it, dprefs.label(it.id, it.label), dprefs.host(it.id, it.wgIp),
                dprefs.port(it.id, KdeConnectConfig.get().discoveryPort), paired,
            ).also { r -> refreshPairButton(r) }
        }
    }

    @Composable
    private fun DeviceCard(ctx: Context, r: KdePeerState) {
        val p = LocalKitPalette.current
        val dprefs = remember { KdeDevicePrefs(ctx) }
        var editing by remember(r) { mutableStateOf(false) }
        KitCard(Modifier.padding(bottom = 12.dp)) {
            Text("${r.label}  ·  ${r.host}:${r.port}", color = p.textPrimary, fontSize = 15.sp,
                fontWeight = FontWeight.Bold)
            // Two status lights: connection + pairing.
            Column {
                LightRow(r.connDot, r.connText)
                LightRow(r.pairDot, r.pairText)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Btn(label = "Connect", size = 12.sp, onClick = {
                    setConn(r, KdeDot.BUSY, "Connecting…")
                    viewLifecycleOwner.lifecycleScope.launch {
                        KdeConnectManager.connect(r.host, r.port)
                            .onFailure { setConn(r, KdeDot.ERR, "Couldn't connect: ${it.message}") }
                    }
                })
                // Merged Pair/Unpair: label follows the paired state. Pairing is keyed
                // by the persistent device id (r.device.id == manager's idFor(host)),
                // so Unpair works even before connecting; Pair needs a live link.
                Btn(label = r.pairLabel, size = 12.sp, onClick = {
                    val liveId = r.deviceId
                    val key = (liveId ?: r.device.id).takeIf { it.isNotBlank() }
                    when {
                        key != null && KdeConnectManager.isPaired(key) -> {
                            KdeConnectManager.unpair(key)
                            setPair(r, KdeDot.IDLE, "Not paired"); refreshPairButton(r)
                        }
                        liveId != null -> {
                            if (!KdeConnectManager.requestPair(liveId)) toast("Connect first")
                            else setPair(r, KdeDot.BUSY, "Pair request sent — accept on ${r.label}")
                        }
                        else -> toast("Connect first")
                    }
                })
                Btn(label = "Ping", size = 12.sp, enabled = r.pingEnabled, onClick = {
                    val msg = "Ping from ${KdeIdentity.deviceName(ctx)}"
                    val live = r.deviceId?.takeIf { KdeConnectManager.isConnected(it) }
                    val sent = live != null && KdeConnectManager.sendPing(live, msg)
                    if (!sent) {
                        if (live != null) toast("Send failed — reconnecting…")
                        // No live link (dropped or first use) — re-dial this device, then ping.
                        toast("Reconnecting…")
                        viewLifecycleOwner.lifecycleScope.launch {
                            KdeConnectManager.connect(r.host, r.port)
                                .onSuccess { id -> KdeConnectManager.sendPing(id, msg) }
                                .onFailure { toast("Couldn't reconnect: ${it.message}") }
                        }
                    }
                })
                Btn(label = "Edit", size = 12.sp, onClick = { editing = true })
            }
        }
        if (editing) EditDialog(r, dprefs) { editing = false }
    }

    /** Edit dialog for the device Name / IP / Port — persists an override on
     *  top of the build.json default. */
    @Composable
    private fun EditDialog(r: KdePeerState, dprefs: KdeDevicePrefs, onClose: () -> Unit) {
        var name by remember { mutableStateOf(r.label) }
        var ip by remember { mutableStateOf(r.host) }
        var port by remember { mutableStateOf(r.port.toString()) }
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Edit ${r.device.label}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    KdeField(name, { name = it }, "Name")
                    KdeField(ip, { ip = it }, "IP (wg)")
                    KdeField(port, { port = it }, "Port", KeyboardType.Number)
                }
            },
            confirmButton = {
                com.diegonmarcos.superapp.uikit.KitTextButton(onClick = {
                    r.label = name.ifBlank { r.label }
                    r.host = ip.ifBlank { r.host }
                    r.port = port.toIntOrNull() ?: r.port
                    dprefs.save(r.device.id, r.label, r.host, r.port)
                    onClose()
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    com.diegonmarcos.superapp.uikit.KitTextButton(onClick = {
                        dprefs.save(r.device.id, r.device.label, r.device.wgIp,
                            KdeConnectConfig.get().discoveryPort)
                        r.label = r.device.label; r.host = r.device.wgIp
                        r.port = KdeConnectConfig.get().discoveryPort
                        onClose()
                    }) { Text("Reset to default") }
                    com.diegonmarcos.superapp.uikit.KitTextButton(onClick = onClose) { Text("Cancel") }
                }
            },
        )
    }

    private fun setConn(r: KdePeerState, dot: KdeDot, text: String) { r.connDot = dot; r.connText = text }
    private fun setPair(r: KdePeerState, dot: KdeDot, text: String) { r.pairDot = dot; r.pairText = text }

    /** Pair button shows "Unpair" when paired, "Pair" otherwise. Falls back to
     *  the persistent device id (the key the manager + the pair light both use)
     *  so a remembered pairing reads "Unpair" before this session connects. */
    private fun refreshPairButton(r: KdePeerState) {
        val key = r.deviceId ?: r.device.id
        val paired = key.isNotBlank() && KdeConnectManager.isPaired(key)
        r.pairLabel = if (paired) "Unpair" else "Pair"
    }

    /** Open the installed official client, or toast if absent. */
    private fun openClient(pkg: String) {
        val intent = requireContext().packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } else toast("KDE Connect ($pkg) is not installed")
    }

    override fun onResume() {
        super.onResume()
        KdeConnectManager.listener = this
    }

    override fun onPause() {
        super.onPause()
        if (KdeConnectManager.listener === this) KdeConnectManager.listener = null
    }

    /** State callbacks arrive on the main thread (manager posts them). Keyed on
     *  the peer wg IP ([host]) — known from the first CONNECTING event — and on
     *  the discovered deviceId once the handshake yields one. */
    override fun onState(deviceId: String, host: String?, state: KdeConnectManager.State, detail: String) {
        val row = peer?.takeIf {
            (host != null && it.host == host) || (deviceId.isNotBlank() && it.deviceId == deviceId)
        } ?: return
        if (deviceId.isNotBlank()) row.deviceId = deviceId
        val pairKey = row.deviceId ?: row.device.id
        val paired = pairKey.isNotBlank() && KdeConnectManager.isPaired(pairKey)
        when (state) {
            KdeConnectManager.State.CONNECTING   -> setConn(row, KdeDot.BUSY, "Connecting…")
            KdeConnectManager.State.HANDSHAKING  -> setConn(row, KdeDot.BUSY, "Connecting (securing)…")
            KdeConnectManager.State.NEEDS_PAIRING -> {
                setConn(row, KdeDot.OK, "Connected"); setPair(row, KdeDot.IDLE, "Not paired")
            }
            KdeConnectManager.State.PAIRED       -> {
                val keyPart = detail.substringAfter(" · ", "")
                setConn(row, KdeDot.OK, "Connected")
                setPair(row, KdeDot.OK, "Paired" + if (keyPart.isNotBlank()) " · $keyPart" else "")
            }
            KdeConnectManager.State.DISCONNECTED -> {
                setConn(row, KdeDot.IDLE, "Not connected")
                setPair(row, if (paired) KdeDot.OK else KdeDot.IDLE, if (paired) "Paired" else "Not paired")
            }
            KdeConnectManager.State.ERROR        -> setConn(row, KdeDot.ERR, "Error: $detail")
        }
        row.pingEnabled = paired   // paired → tappable; the handler auto-reconnects if the link dropped
        refreshPairButton(row)
    }

    /** #733 the live link of the SELECTED peer (the one card on the page) —
     *  never "whichever link happens to be open first". Matched by the id the
     *  handshake reported, the declared id, or the address the link dialled. */
    private fun selectedLiveId(): String? {
        val row = peer ?: return null
        return KdeConnectManager.connectedIds().firstOrNull { id ->
            id == row.deviceId || id == row.device.id ||
                KdeConnectManager.link(id)?.peerAddress?.hostAddress == row.host
        }
    }

    private fun toast(m: String) {
        context?.let { Toast.makeText(it, m, Toast.LENGTH_SHORT).show() }
    }

    // ── Plugins catalog ───────────────────────────────────────────────────

    /** The full KDE Connect plugin catalog — active (we implement + advertise)
     *  in lavender, planned in gray. Data-driven from build.json plugins[]. */
    @Composable
    private fun PluginsCard(ctx: Context, cfg: KdeConnectConfig.Config) {
        val p = LocalKitPalette.current
        val view = LocalView.current
        val prefs = remember { KdePluginPrefs(ctx) }
        var rev by remember { mutableStateOf(0) }
        val enabled = remember(rev) { cfg.plugins.associate { it.id to prefs.isEnabled(it.id) } }
        // Handled = a registered plugin actually processes/sends this packet.
        val handledPackets = remember { KdePluginRegistry.plugins.flatMap { it.incoming + it.outgoing }.toSet() }
        val on = enabled.values.count { it }
        KdeCard(
            "Plugins  ·  $on enabled / ${cfg.plugins.size} total",
            "Tap a plugin to enable/disable it (counts above update live). " +
                "● handled · ● advertised (no handler yet) · ◌ off.",
        ) {
            for (pl in cfg.plugins.sortedByDescending { it.packet in handledPackets }) {
                val isOn = enabled[pl.id] == true
                val real = pl.packet in handledPackets
                val arrow = when (pl.dir) { "in" -> "↓"; "out" -> "↑"; else -> "↕" }
                Text(
                    "${if (isOn) "●" else "◌"}  ${pl.name}  $arrow   ${pl.packet}",
                    color = when {
                        !isOn -> p.textSecondary        // off — dim
                        real  -> p.accent               // handled — lavender
                        else  -> p.warn                 // advertised-only — amber
                    },
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().clickable {
                        prefs.toggle(pl.id); rev++
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    }.padding(vertical = 3.dp),
                )
            }
        }
    }

    // ── Remote control ────────────────────────────────────────────────────

    /** Remote control card — drives the connected desktop: touchpad (move +
     *  tap-click), keyboard, and Big Screen text. Senders: RemoteInputPlugin /
     *  BigScreenPlugin. Targets the selected peer's live link. */
    @Composable
    private fun RemoteCard() {
        // Master input-share gate: when OFF the pad/keys send nothing (so the
        // phone in your pocket can't nudge the desktop cursor). Tap Start to arm.
        var inputOn by remember { mutableStateOf(false) }
        fun target(): String? = if (inputOn) selectedLiveId() else null
        var keys by remember { mutableStateOf("") }
        var bigScreen by remember { mutableStateOf("") }

        KdeCard("Remote control",
            "Controls the connected desktop. Drag the pad to move the cursor · tap = click · long-press area = right-click.") {
            // Share toggle — arm/disarm sending input to the desktop
            WideBtn(if (inputOn) "■ Stop control" else "▶ Start control") {
                inputOn = !inputOn
                toast(if (inputOn) "Input control ON — pad & keys drive the desktop" else "Input control OFF")
            }
            // Touchpad
            Pad(150, "kde:pad:remote",
                onMove = { dx, dy, _, _ ->
                    target()?.let { KdeConnectManager.send(it, RemoteInputPlugin.move(dx, dy)) }
                },
                onUp = { moved, held ->
                    val id = target()
                    if (!moved && id != null) {
                        KdeConnectManager.send(id, if (held > 500) RemoteInputPlugin.rightClick() else RemoteInputPlugin.click())
                    }
                    if (id == null) toast(if (!inputOn) "Tap 'Start control' first" else "Connect & pair first")
                })
            // Keyboard
            KdeField(keys, { keys = it }, "Type, then Send keys")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Btn(label = "Send keys", weight = 2f, size = 12.sp, onClick = {
                    val id = target()
                    if (id == null) toast("Connect & pair first")
                    else if (keys.isNotEmpty()) { KdeConnectManager.send(id, RemoteInputPlugin.key(keys)); keys = "" }
                })
                // KDE special keys: 1 = Backspace, 12 = Enter/Return.
                Btn(label = "⌫", size = 12.sp, onClick = { target()?.let { KdeConnectManager.send(it, RemoteInputPlugin.specialKey(1)) } })
                Btn(label = "⏎", size = 12.sp, onClick = { target()?.let { KdeConnectManager.send(it, RemoteInputPlugin.specialKey(12)) } })
            }
            // Big Screen
            KdeField(bigScreen, { bigScreen = it }, "Send a line to Plasma Big Screen")
            WideBtn("Send to Big Screen", 12.sp) {
                val id = target()
                if (id == null) toast("Connect & pair first")
                else if (bigScreen.isNotEmpty()) { KdeConnectManager.send(id, BigScreenPlugin.stt(bigScreen)); bigScreen = "" }
            }
        }
    }

    // ── Clipboard ─────────────────────────────────────────────────────────

    /**
     * Clipboard — both sides, as lists rather than a single slot.
     *
     * The switch is [KdePluginPrefs] for the `clipboard` plugin, not a new
     * preference: that flag already gates dispatch AND what we advertise in the
     * identity packet, so it IS sync on/off and a second toggle could only ever
     * disagree with it.
     *
     * ## What "the desktop's clipboard" can mean here
     * KDE Connect carries ONE string per clipboard packet and has no request
     * for a peer's history — Klipper's list is not exposed over the protocol.
     * So the desktop list is every clip it has pushed since sync was turned on,
     * kept by [KdeClipboardStore] instead of overwritten, and "Get host" is the
     * one pull the protocol does allow: `clipboard.connect` dated at the epoch,
     * which any desktop with a clipboard answers because its own is newer.
     * Ours is thinner again — Android only hands over the CURRENT clip, and
     * only while this page is focused, so the list is what we saw while it was
     * open. Both limits are printed on the card rather than hidden.
     */
    @Composable
    private fun ClipboardCard(ctx: Context) {
        val p = LocalKitPalette.current
        val prefs = remember { KdePluginPrefs(ctx) }
        // Which clipboard app receives an incoming clip. ONE setPrimaryClip
        // either way, because that is the only cross-app clipboard API Android
        // has; Cloud-Keyboard's pinned lists live in its own Room DB behind a
        // non-exported provider, so nothing here can write a pin directly.
        // What differs is what happens NEXT: Cloud-Keyboard files the clip into
        // its history where it can be pinned, the stock clipboard just replaces
        // its one slot. Said out loud so the card promises no pin it cannot make.
        val ours = remember {
            runCatching { ctx.packageManager.getPackageInfo(CLOUD_KEYBOARD_PKG, 0); true }.getOrDefault(false)
        }
        // Which list is on screen. Kept as state rather than two panes so a
        // long history does not push the buttons off the bottom of the card.
        var shown by remember { mutableStateOf(SHOW_BOTH) }
        var rev by remember { mutableStateOf(0) }
        val syncOn = remember(rev) { prefs.isEnabled(ClipboardPlugin.id) }
        val listText = remember(rev, shown) { clipboardLists(ctx, shown) }

        KdeCard("Clipboard", "Both clipboards, kept as lists — not just the last thing copied.") {
            Text(
                if (syncOn) "● Sync is ON — tap to turn off" else "◌ Sync is OFF — tap to turn on",
                color = if (syncOn) p.ok else p.textSecondary, fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().clickable {
                    prefs.toggle(ClipboardPlugin.id)
                    rev++
                    toast(if (prefs.isEnabled(ClipboardPlugin.id))
                        "Clipboard sync on — reconnect to advertise it" else "Clipboard sync off")
                }.padding(vertical = 2.dp),
            )
            Text(
                (if (ours)
                    "Cloud-Keyboard is installed: an incoming clip lands in its history, where you can pin it. "
                else
                    "Stock clipboard: an incoming clip replaces the single slot. Install Cloud-Keyboard for pinned lists. ") +
                    "Pins stay local — the protocol has no packet for them.",
                color = p.textSecondary, fontSize = 11.sp,
            )
            Text(listText, color = p.textPrimary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            BtnRow(
                "View desktop" to { shown = SHOW_HOST; rev++ },
                "View ours" to { shown = SHOW_OURS; rev++ },
                "Refresh" to { shown = SHOW_BOTH; rev++ },
            )
            BtnRow(
                "Send ours" to {
                    val text = KdeClipboardStore.ourText(ctx)
                    if (text.isEmpty()) toast("This phone's clipboard is empty (or Android refused the read)")
                    else { toTarget(ClipboardPlugin.build(text)); rev++; toast("Sent ${text.length} chars") }
                },
                "Get host" to {
                    // Epoch timestamp: we are claiming our clipboard is as old as
                    // possible, so the desktop's is newer and it answers with its
                    // own. The reply lands in ClipboardPlugin and shows up on the
                    // next refresh; there is nothing to await here.
                    toTarget(ClipboardPlugin.buildConnect(KdeClipboardStore.ourText(ctx), 0L))
                    toast("Asked the desktop — tap Refresh in a moment")
                },
                "Merge all" to {
                    val n = KdeClipboardStore.mergeAll(ctx)
                    shown = SHOW_BOTH; rev++
                    toast("Merged into $n entr${if (n == 1) "y" else "ies"} on both lists")
                },
            )
        }
    }

    /** The text of the list(s) on show. Reading the clipboard is only allowed
     *  while focused, so every refresh is also our one chance to notice what is
     *  on this phone. */
    private fun clipboardLists(ctx: Context, shown: Int): String {
        KdeClipboardStore.rememberOurs(ctx)
        val host = KdeClipboardStore.hostHistory(ctx)
        val mine = KdeClipboardStore.ourHistory(ctx)
        return when (shown) {
            SHOW_HOST -> renderClips("Desktop  ·  ${host.size} item(s)  ·  last ${ago(KdeClipboardStore.hostAt(ctx))}", host)
            SHOW_OURS -> renderClips("This phone  ·  ${mine.size} item(s)", mine)
            else -> renderClips("Desktop  ·  ${host.size}", host.take(4)) + "\n\n" +
                renderClips("This phone  ·  ${mine.size}", mine.take(4))
        }
    }

    private fun preview(t: String): String = if (t.length <= 90) t else t.take(90) + "…"

    private fun ago(at: Long): String {
        if (at == 0L) return "nothing received yet"
        val secs = (System.currentTimeMillis() - at) / 1000
        return when {
            secs < 60 -> "${secs}s ago"
            secs < 3600 -> "${secs / 60}m ago"
            else -> "${secs / 3600}h ago"
        }
    }

    private fun renderClips(title: String, items: List<String>): String =
        if (items.isEmpty()) "$title\n  (empty)"
        else "$title\n" + items.take(12).mapIndexed { i, t -> "  ${i + 1}. ${preview(t)}" }
            .joinToString("\n") + (if (items.size > 12) "\n  … ${items.size - 12} more" else "")

    // ── Sending ───────────────────────────────────────────────────────────

    /** Run [then] against a live link; if the link dropped, transparently
     *  re-dial the first device first (KDE reconnects on demand rather than
     *  holding one socket forever). */
    private fun targetOrReconnect(then: (String) -> Unit) {
        val live = selectedLiveId()
        if (live != null) { then(live); return }
        val row = peer ?: return toast("No device")
        if (row.deviceId?.let { KdeConnectManager.isPaired(it) } != true &&
            !KdeConnectManager.isPaired(row.device.id)) return toast("Pair first")
        toast("Reconnecting…")
        viewLifecycleOwner.lifecycleScope.launch {
            KdeConnectManager.connect(row.host, row.port)
                .onSuccess { id ->
                    if (KdeConnectManager.isConnected(id)) then(id)
                    else toast("Reconnected — tap again")
                }
                .onFailure { toast("Couldn't reconnect: ${it.message}") }
        }
    }

    /** A file was picked → stream it to the connected desktop via the KDE
     *  share payload channel (SharePlugin opens the TLS payload server). */
    private fun onFilePicked(uri: Uri) {
        val id = selectedLiveId()
        if (id == null) { toast("Connect & pair a desktop first"); return }
        val link = KdeConnectManager.link(id)
        if (link == null) { toast("No live link"); return }
        if (SharePlugin.sendFile(requireContext(), link, uri)) toast("Sending file…")
        else toast("Couldn't start file send")
    }

    /** Send a sender packet to a live link (reconnecting if needed). */
    private fun toTarget(packet: NetworkPacket?) {
        if (packet == null) return toast("No desktop state yet — try again")
        targetOrReconnect { id -> KdeConnectManager.send(id, packet) }
    }

    // ── Media / presenter / actions ───────────────────────────────────────

    /** Media remote — drives the desktop's player (mpris) + master volume
     *  (systemvolume). Requests current state on first open. */
    @Composable
    private fun MediaCard() {
        KdeCard("Media remote", "Controls the connected desktop's player and system volume.") {
            BtnRow(
                "⏮" to { toTarget(MprisPlugin.command("Previous")) },
                "⏯" to { toTarget(MprisPlugin.command("PlayPause")) },
                "⏭" to { toTarget(MprisPlugin.command("Next")) },
            )
            BtnRow(
                "Vol −" to { volNudge(-10) },
                "Mute"  to { toTarget(RemoteSystemVolumePlugin.mute(true)) },
                "Vol +" to { volNudge(+10) },
            )
        }
    }

    /** Nudge desktop volume; if we don't have its sink state yet, request it. */
    private fun volNudge(delta: Int) = targetOrReconnect { id ->
        val pkt = RemoteSystemVolumePlugin.nudge(delta)
        if (pkt == null) {
            KdeConnectManager.send(id, RemoteSystemVolumePlugin.requestSinks())
            toast("Fetching desktop volume — tap again")
        } else KdeConnectManager.send(id, pkt)
    }

    /** Presenter — a laser-pointer pad (drag) + slide navigation. The pointer
     *  uses kdeconnect.presenter; prev/next use mousepad PageUp/PageDown. */
    @Composable
    private fun PresenterCard() {
        KdeCard("Presenter", "Drag the pad to move the on-screen laser · release to hide it.") {
            Pad(110, "kde:pad:presenter",
                onMove = { dx, dy, w, h ->
                    selectedLiveId()?.let {
                        KdeConnectManager.send(it, PresenterPlugin.pointer(dx / w.coerceAtLeast(1f), dy / h.coerceAtLeast(1f)))
                    }
                },
                onUp = { _, _ ->
                    val id = selectedLiveId()
                    if (id != null) KdeConnectManager.send(id, PresenterPlugin.stop())
                    else toast("Connect & pair first")
                })
            BtnRow(
                "◀ Prev" to { toTarget(RemoteInputPlugin.specialKey(7)) },   // PageUp
                "Next ▶" to { toTarget(RemoteInputPlugin.specialKey(8)) },   // PageDown
            )
        }
    }

    /** One-shot sender actions — ring the desktop, ask it to expose its files,
     *  start a virtual monitor, or open a remote-desktop session. */
    @Composable
    private fun ActionsCard() {
        KdeCard("Actions", "Single-tap requests to the connected desktop.") {
            BtnRow(
                "🔔 Ring desktop" to { toTarget(FindMyPhonePlugin.ring()) },
                "📁 Send file" to { pickFile.launch("*/*") },
            )
            BtnRow(
                "🖥 Monitor" to { toTarget(VirtualMonitorPlugin.request()) },
                "🔒 Lock desktop" to { toTarget(LockDevicePlugin.lockRemote(true)) },
            )
        }
    }

    /** Remote Desktop control — asks Plasma to start a remote-desktop session
     *  (kdeconnect.remotedesktop), then exposes a full mouse + keyboard surface
     *  to drive it (mousepad senders: move/click/right-click/keys/arrows). */
    @Composable
    private fun RemoteDesktopCard() {
        fun tgt(): String? = selectedLiveId()
        // Stateful start/stop — label follows the session state.
        var rdpActive by remember { mutableStateOf(false) }
        var keys by remember { mutableStateOf("") }
        KdeCard("Remote Desktop control",
            "Asks Plasma to start a remote-desktop session (KDE streams the screen via an RDP/virtual-monitor portal). Drive it with the pad/keys below; full on-phone viewing needs an RDP client.") {
            WideBtn(if (rdpActive) "■ Stop Remote Desktop Control" else "▶ Start Remote Desktop Control") {
                if (!rdpActive) {
                    toTarget(RemoteDesktopPlugin.request())
                    rdpActive = true
                    toast("Requested a remote-desktop session on the desktop")
                } else {
                    toTarget(RemoteDesktopPlugin.stop())
                    rdpActive = false
                    toast("Stopped the remote-desktop session")
                }
            }
            // Mouse surface.
            Pad(140, "kde:pad:rdp",
                onMove = { dx, dy, _, _ ->
                    tgt()?.let { KdeConnectManager.send(it, RemoteInputPlugin.move(dx, dy)) }
                },
                onUp = { moved, held ->
                    val id = tgt()
                    if (!moved && id != null)
                        KdeConnectManager.send(id, if (held > 500) RemoteInputPlugin.rightClick() else RemoteInputPlugin.click())
                    if (id == null) toast("Connect & pair first")
                })
            // Keyboard + navigation keys (KDE special-key codes).
            KdeField(keys, { keys = it }, "Type, then Send")
            BtnRow(
                "Send" to {
                    val id = tgt()
                    if (id == null) toast("Connect & pair first")
                    else if (keys.isNotEmpty()) { KdeConnectManager.send(id, RemoteInputPlugin.key(keys)); keys = "" }
                },
                "⌫" to { tgt()?.let { KdeConnectManager.send(it, RemoteInputPlugin.specialKey(1)) } },
                "⏎" to { tgt()?.let { KdeConnectManager.send(it, RemoteInputPlugin.specialKey(12)) } },
                "Esc" to { tgt()?.let { KdeConnectManager.send(it, RemoteInputPlugin.specialKey(14)) } },
            )
            BtnRow(
                "←" to { tgt()?.let { KdeConnectManager.send(it, RemoteInputPlugin.specialKey(3)) } },
                "↑" to { tgt()?.let { KdeConnectManager.send(it, RemoteInputPlugin.specialKey(4)) } },
                "↓" to { tgt()?.let { KdeConnectManager.send(it, RemoteInputPlugin.specialKey(6)) } },
                "→" to { tgt()?.let { KdeConnectManager.send(it, RemoteInputPlugin.specialKey(5)) } },
            )
        }
    }

    /** Share — push text or a link to the connected desktop (kdeconnect.share).
     *  A link opens in the desktop browser; text lands on its clipboard. */
    @Composable
    private fun ShareCard() {
        var text by remember { mutableStateOf("") }
        KdeCard("Share", "Send text or a link to the connected desktop.") {
            KdeField(text, { text = it }, "Text or https://link")
            BtnRow(
                "Send text" to {
                    if (text.isNotBlank()) { toTarget(SharePlugin.text(text)); text = "" }
                },
                "Send link" to {
                    val t = text.trim()
                    if (t.isNotBlank()) { toTarget(SharePlugin.url(t)); text = "" }
                },
            )
        }
    }

    /** Send SMS — this phone sends the message directly via SmsManager (the
     *  phone is the SMS device; the desktop normally drives this via
     *  kdeconnect.sms.request). Needs the runtime SEND_SMS grant. */
    @Composable
    private fun SmsCard(ctx: Context) {
        var to by remember { mutableStateOf("") }
        var body by remember { mutableStateOf("") }
        KdeCard("Send SMS", "This phone sends the text (needs SMS permission).") {
            KdeField(to, { to = it }, "Recipient number", KeyboardType.Phone)
            KdeField(body, { body = it }, "Message")
            BtnRow(
                "Send SMS" to {
                    val num = to.trim()
                    when {
                        num.isBlank() || body.isBlank() -> toast("Recipient and message required")
                        SmsPlugin.sendDirect(ctx, num, body) -> { toast("SMS sent"); body = "" }
                        else -> toast("Couldn't send — grant SMS permission")
                    }
                },
            )
        }
    }

    /** Photo capture — opens the camera (the desktop normally triggers this via
     *  kdeconnect.photo.request; the button lets you fire it locally too). */
    @Composable
    private fun PhotoCard(ctx: Context) {
        KdeCard("Photo capture",
            "Open the camera. (Desktop sends kdeconnect.photo.request to trigger it remotely.)") {
            BtnRow("📷 Open camera" to { PhotoPlugin.capture(ctx) })
        }
    }

    // ── Self-test + probe ─────────────────────────────────────────────────

    /** Self-test — for every enabled plugin, report whether a handler is wired
     *  (packet known to the registry) and, when connected+paired, fire a
     *  representative packet for the sender plugins and confirm it left. */
    @Composable
    private fun SelfTestCard(ctx: Context, cfg: KdeConnectConfig.Config) {
        var lines by remember { mutableStateOf<List<KdeTestLine>>(emptyList()) }
        KdeCard("Self-test", "Tap to exercise every enabled plugin and list the result.") {
            WideBtn("Test all plugins") { lines = runSelfTest(ctx, cfg) }
            Column { MonoLines(lines, 12.sp) }
        }
    }

    private fun runSelfTest(ctx: Context, cfg: KdeConnectConfig.Config): List<KdeTestLine> {
        val out = mutableListOf<KdeTestLine>()
        val prefs = KdePluginPrefs(ctx)
        val handled = KdePluginRegistry.plugins.flatMap { it.incoming + it.outgoing }.toSet()
        val target = selectedLiveId()
        // Representative sender packets (only fired when connected+paired).
        val probes: Map<String, () -> NetworkPacket?> = mapOf(
            "ping"               to { PingPlugin.build("Self-test") },
            "mousepad"           to { RemoteInputPlugin.move(0f, 0f) },
            "bigscreen"          to { BigScreenPlugin.stt("Self-test") },
            "mprisremote"        to { MprisPlugin.requestPlayers() },
            "remotesystemvolume" to { RemoteSystemVolumePlugin.requestSinks() },
            "presenter_remote"   to { PresenterPlugin.pointer(0f, 0f) },
            "findthisdevice"     to { FindMyPhonePlugin.ring() },
            "sftp_browse"        to { SftpPlugin.request() },
            "virtualmonitor"     to { VirtualMonitorPlugin.request() },
            "screenconnector"    to { RemoteDesktopPlugin.request() },
            "lockdevice"         to { LockDevicePlugin.requestState() },  // queries, won't lock
            // sms is intentionally NOT probed — never send a real text during a self-test.
        )
        var pass = 0; var fail = 0
        for (pl in cfg.plugins) {
            val on = prefs.isEnabled(pl.id)
            val wired = pl.packet in handled
            val probe = probes[pl.id]
            val mark: String; val tone: KdeTone; val note: String
            when {
                !on -> { mark = "◌"; tone = KdeTone.DIM; note = "disabled" }
                !wired -> { mark = "✗"; tone = KdeTone.BAD; note = "no handler"; fail++ }
                probe != null && target != null -> {
                    runCatching { probe()?.let { KdeConnectManager.send(target, it) } }
                    mark = "✓"; tone = KdeTone.OK; note = "sent"; pass++
                }
                probe != null -> { mark = "•"; tone = KdeTone.WARN; note = "ready (not connected)"; pass++ }
                else -> { mark = "✓"; tone = KdeTone.OK; note = "handler ok"; pass++ }
            }
            out += KdeTestLine("$mark  ${pl.name} — $note", tone)
        }
        out += KdeTestLine(
            "→ $pass ok · $fail failed · ${cfg.plugins.size} total" +
                if (target == null) "  (connect+pair to fire senders)" else "",
            KdeTone.PLAIN, bold = true,
        )
        return out
    }

    /** Run the read-only reachability probe and render its step trace. */
    @Composable
    private fun ProbeSection(ctx: Context) {
        val p = LocalKitPalette.current
        var steps by remember { mutableStateOf<List<KdeTestLine>>(emptyList()) }
        Text("Probe", color = p.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
        WideBtn("Probe wg0 · hosts · peers · LAN", 12.sp) {
            steps = listOf(KdeTestLine("• probing…", KdeTone.PLAIN))
            viewLifecycleOwner.lifecycleScope.launch {
                steps = KdeConnectDiscovery.probeAll(ctx).map { stepLine(it) }
            }
        }
        Column { MonoLines(steps, 12.5.sp) }
    }

    private fun stepLine(step: KdeConnectDiscovery.Step): KdeTestLine {
        val mark = when (step.ok) { true -> "✓"; false -> "✗"; null -> "•" }
        val tone = when (step.ok) { true -> KdeTone.OK; false -> KdeTone.BAD; null -> KdeTone.PLAIN }
        return KdeTestLine("$mark  ${step.label}: ${step.detail}", tone)
    }

    companion object {
        fun newInstance() = KdeConnectFragment()
        /** #733 the peer selector's view tag (KdeConnectPeerSelectorTest finds it by this). */
        const val PEER_SELECTOR_TAG = "kde-peer-selector"

        /** Which clipboard list [ClipboardCard] is showing. */
        private const val SHOW_BOTH = 0
        private const val SHOW_HOST = 1
        private const val SHOW_OURS = 2

        /** Our own clipboard app — see [ClipboardCard]. */
        private const val CLOUD_KEYBOARD_PKG = "com.diegonmarcos.cloudkeyboard"
    }
}
