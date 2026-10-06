package com.diegonmarcos.superapp.network.mesh

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.diegonmarcos.superapp.network.WireGuardProfiles
import java.util.concurrent.Executors

/** Why a control that the engine CAN honour cannot be used right now. */
enum class Block { ENGINE_MISSING, NO_KEY }

/**
 * #877 The Cloud Mesh page's state and its verbs. The page's composables read the observable fields and
 * call [run] with an `engine` id from the declaration; nothing in a composable touches the engine,
 * the prefs or a thread. Reads and writes go to [port] OFF the main thread; results come back as
 * [notice] (the line under the tab strip) and as journal entries, so no action ends silently.
 *
 * [exec] runs a unit of work; the default is one background thread so engine calls never reorder.
 */
class MeshStore(
    val port: MeshPort,
    val decl: MeshDecl.Decl,
    val host: MeshHost,
    private val clock: () -> Long = System::currentTimeMillis,
    private val exec: (Runnable) -> Unit = defaultExec,
) {
    // ── observable state ──
    var snapshot by mutableStateOf<MeshSnapshot?>(null)
    var page by mutableStateOf(decl.startPage.id)
    var notice by mutableStateOf("")
    var busy by mutableStateOf(false)
    /** The tunnel is up on a config older than the stored one. */
    var pending by mutableStateOf(false)
    /** Bumped when something the page reads from the port was written: composables re-read. */
    var rev by mutableIntStateOf(0)
    var journalRev by mutableIntStateOf(0)
    var dnsPreset by mutableStateOf("")
    var provider by mutableStateOf("")
    var profiles by mutableStateOf<Map<String, String>>(emptyMap())
    var excluded by mutableStateOf<List<String>>(emptyList())
    var hasKey by mutableStateOf(false)
    var alwaysOn by mutableStateOf<Boolean?>(null)
    var lockdown by mutableStateOf<Boolean?>(null)
    var apps by mutableStateOf<List<Pair<String, String>>>(emptyList())
    var dnsChoices by mutableStateOf<List<Triple<String, String, String>>>(emptyList())
    /** A declared profile id whose diff / QR is open. */
    var diffFor by mutableStateOf<String?>(null)
    var qrFor by mutableStateOf<String?>(null)
    /** A pending Cloud-over-custom confirmation. */
    var confirmCloud by mutableStateOf(false)
    /** The control values that are view state, not tunnel state (sort, filter, topology), seeded from the declaration. */
    val view = mutableStateMapOf<String, String>().also { m ->
        for (c in decl.pages.flatMap { it.controls }) if (c.engine.startsWith("view.") && c.kind in setOf("choice", "switch")) m[c.id] = c.default
    }
    val journal = MeshJournal(decl.logCap)

    private val th = Thresholds(decl.handshakeFreshS, decl.handshakeStaleS)
    private var prev: MeshSnapshot? = null
    private var tick = 0

    // ── polling ──

    /** One poll: engine counters, config, the journal lines they imply. Blocking: never on the main thread. */
    @Synchronized fun poll() {
        val now = clock()
        val cfg = runCatching { port.config() }.getOrElse { fail("read config", it); return }
        var s = runCatching { port.sample(now) }.getOrElse { fail("read the engine", it); return }
        val probe = s.up && tick % decl.latencyEveryTicks == 0 && (page == "status" || page == "peers")
        if (probe) s = s.copy(latency = runCatching { port.probeLatency(cfg, decl.latencyTimeoutMs, now) }.getOrNull())
        tick++
        val (snap, events) = MeshReducer.reduce(prev, cfg, s, th)
        prev = snap
        if (events.isNotEmpty()) { journal.addAll(events); journalRev++ }
        snapshot = snap
        refreshStatic()
    }

    private fun refreshStatic() {
        runCatching {
            pending = port.pendingApply()
            dnsPreset = port.dnsPreset()
            provider = port.provider()
            profiles = port.storedProfiles()
            excluded = port.excludedApps()
            hasKey = port.hasPrivateKey()
            alwaysOn = port.alwaysOn()
            lockdown = port.lockdown()
            if (dnsChoices.isEmpty()) dnsChoices = port.dnsChoices()
        }
    }

    /** Whether the ticker should be running: only while the page is resumed, never in the background. */
    fun shouldPoll(resumed: Boolean): Boolean = resumed

    // ── actions ──

    private fun fail(what: String, t: Throwable) {
        notice = "$what failed: ${t.message ?: t.javaClass.simpleName}"
        journal.add(LogEvent(clock(), "error", notice)); journalRev++
    }

    /** Run [block] off the main thread; its result (or its failure's reason) becomes the notice and a journal line. */
    fun perform(label: String, block: () -> String) {
        busy = true
        exec(Runnable {
            try {
                val m = block()
                notice = "$label: $m"
                journal.add(LogEvent(clock(), "action", notice))
            } catch (t: Throwable) {
                notice = "$label failed: ${t.message ?: t.javaClass.simpleName}"
                journal.add(LogEvent(clock(), "error", notice))
            } finally {
                journalRev++; rev++
                runCatching { poll() }
                busy = false
            }
        })
    }

    /** Why [c] cannot be used right now although the engine could honour it; null when it can. */
    fun blockOf(c: MeshDecl.Control): Block? {
        if (c.engine in ENGINE_BOUND && snapshot?.engineInstalled == false) return Block.ENGINE_MISSING
        if (c.engine in NEEDS_KEY && !hasKey && !connected()) return Block.NO_KEY
        return null
    }

    fun connected(): Boolean = snapshot?.up == true

    /** The Connected switch / Reconnect button: ask for VPN consent when needed, then drive the engine. */
    private fun connectFlow(label: String, call: () -> String) {
        val intent = runCatching { port.consentIntent() }.getOrNull()
        if (intent == null) perform(label, call) else host.requestConsent(intent) { perform(label, call) }
    }

    /** The one dispatch table: every `engine` id the declaration may name, and what it does. */
    private val handlers: Map<String, (String, Int) -> Unit> = mapOf(
        "state.set" to { arg, _ -> if (arg == "on") connectFlow("connect") { port.connect() } else perform("disconnect") { port.disconnect() } },
        "state.reconnect" to { _, _ -> connectFlow("reconnect") { port.reconnect() } },
        "read.lockdown" to { _, _ -> lockdown = port.lockdown() },
        "nav.vpnSettings" to { _, _ -> host.openVpnSettings(); notice = "VPN settings opened" },
        "nav.account" to { _, _ -> host.openAccount(); notice = "Account opened: Runtime > Apply all re-imports the vault's profiles" },
        "prefs.mtu" to { a, _ -> perform("MTU") { port.setMtu(a) } },
        "prefs.keepalive" to { a, _ -> perform("keepalive") { port.setKeepalive(a) } },
        "prefs.tunnelName" to { a, _ -> perform("tunnel name") { port.setTunnelName(a) } },
        "prefs.address" to { a, _ -> perform("address") { port.setAddress(a) } },
        "prefs.listenPort" to { a, _ -> perform("listen port") { port.setListenPort(a) } },
        "prefs.privateKey" to { a, _ -> perform("private key") { port.setPrivateKey(a) } },
        "prefs.generateKey" to { _, _ -> perform("generate key") { "public half " + port.generateKey() + " - add it to the hub as this phone's peer" } },
        "prefs.excludedApps" to { a, _ -> perform("excluded apps") { port.setExcludedApps(a.split(',').map { it.trim() }.filter { it.isNotEmpty() }) } },
        "prefs.peer.add" to { _, _ -> perform("add peer") { port.addPeer() } },
        "prefs.peer.remove" to { _, i -> perform("remove peer") { port.removePeer(i) } },
        "prefs.provider" to { a, _ ->
            if (a == "custom") perform("provider") { port.setProviderCustom() }
            else if (port.matchesCloudPreset()) perform("provider") { port.applyCloudPreset() }
            else confirmCloud = true
        },
        "prefs.profile.activate" to { a, _ ->
            perform("profile") { storedKey(port.storedProfiles(), a)?.let { port.activateProfile(it) } ?: port.installDeclared(a) }
        },
        "mesh.applyMesh" to { a, _ ->
            val text = host.paste()
            if (text.isBlank()) { notice = "re-import from clipboard: the clipboard holds no text"; journal.add(LogEvent(clock(), "error", notice)); journalRev++ }
            else perform("re-import $a") { port.importText(nameOf(a), text) }
        },
        "fleetdns.preset" to { a, _ -> perform("DNS preset") { port.setDnsPreset(a) } },
        "view.sort" to { a, _ -> view["peer_sort"] = a },
        "view.topology" to { a, _ -> view["topology"] = a },
        "view.routeFilter" to { a, _ -> view["route_filter"] = a },
        "view.diff" to { a, _ -> diffFor = if (diffFor == a) null else a },
        "view.qr" to { a, _ -> qrFor = if (qrFor == a) null else a },
        "clipboard.peer" to { _, i ->
            val p = snapshot?.cfg?.peers?.getOrNull(i)
            if (p == null) notice = "copy peer: no such peer" else { host.copy("peer", MeshText.peerBlock(p)); notice = "peer ${p.name.ifBlank { i + 1 }} copied (no pre-shared key)" }
        },
        "clipboard.profile" to { a, _ ->
            val t = profileText(a)
            if (t == null) notice = "copy profile: $a is not declared" else { host.copy(a, t); notice = "$a copied - a template, it carries no private key" }
        },
        "clipboard.log" to { _, _ -> host.copy("mesh log", journal.text()); notice = "${journal.list().size} journal lines copied" },
        "log.clear" to { _, _ -> journal.clear(); journalRev++; notice = "journal cleared" },
        "saf.importConf" to { _, _ -> host.importConf() },
        "saf.exportConf" to { _, _ -> host.exportConf() },
        "saf.exportProfiles" to { _, _ -> host.exportProfiles() },
    )

    /** The engine ids this store can dispatch - the declaration may name no other. */
    val engineIds: Set<String> get() = handlers.keys

    /** Dispatch declared control [c]'s engine id with [arg] (a value, or a profile id) and [index] (a peer). */
    fun run(engineId: String, arg: String = "", index: Int = -1) {
        val h = handlers[engineId]
        if (h == null) { notice = "no engine call named $engineId"; return }
        h(arg, index)
    }

    /** The Cloud preset after the owner confirmed replacing their settings. */
    fun confirmCloudPreset() { confirmCloud = false; perform("provider") { port.applyCloudPreset() } }

    /** The stored-set name of declared profile [id] (its export file name without `.conf`). */
    private fun nameOf(id: String): String = WireGuardProfiles.byId(id)?.fileName?.removeSuffix(".conf") ?: id

    /** The key under which this phone stores declared profile [id], if it does (a vault may prefix a folder). */
    fun storedKey(stored: Map<String, String>, id: String): String? =
        nameOf(id).let { n -> stored.keys.firstOrNull { it.substringAfterLast('/') == n } }

    /** Declared profile [id] as key-free wg-quick text. */
    fun profileText(id: String): String? = WireGuardProfiles.byId(id)?.let { WireGuardProfiles.render(it) }

    companion object {
        private val ENGINE_BOUND = setOf("state.set", "state.reconnect", "fleetdns.preset", "read.lockdown")
        private val NEEDS_KEY = setOf("state.set", "state.reconnect")
        val defaultExec: (Runnable) -> Unit = Executors.newSingleThreadExecutor { r -> Thread(r, "mesh-page").apply { isDaemon = true } }::execute
    }
}
