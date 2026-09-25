package com.diegonmarcos.superapp.profile

import android.content.Context
import android.util.Base64
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.appstore.AppInventory
import com.diegonmarcos.superapp.mail.JmapPrefs
import com.diegonmarcos.superapp.network.WireGuardPrefs
import com.diegonmarcos.superapp.settings.ConfigsPrefs
import com.diegonmarcos.superapp.texttools.TextToolsClient
import com.diegonmarcos.superapp.ui.StatusLight
import com.wireguard.config.Config
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.StringReader

/**
 * Configs ▸ Profile ▸ Fleet (#570): the vault bundle as the fleet configurator.
 *
 * Every section here reads ONE thing — the bundle [VaultConnect.fetch] returned
 * — and compares it with what this device holds. The section list, its labels
 * and which vault sections feed each one are `build.json::ui.vault_connect.cockpit`
 * (baked to [BuildConfig.UI_VAULT_CONNECT_COCKPIT_B64]); nothing in here restates
 * the vault's schema, and a vault section this build has no cockpit for still
 * shows up raw in the Fleet tab.
 *
 * THE DEVICE IS CHOSEN, NEVER TYPED. The owner picks WHICH of the vault's
 * declared devices (the `electronics` section: every entry carrying a
 * `wg_peer`) this phone is. Its mesh identity — address, key, profiles —
 * derives from that declaration: a mesh profile belongs to the device whose
 * WireGuard address appears on the profile's own `Address` line. No IP, key or
 * hostname is ever entered by hand on this screen.
 *
 * NOTHING IS APPLIED BY RENDERING. Every `apply*` runs on a tap on its own
 * section's button, and the render path only reads. The testers pin that.
 */
object VaultCockpit {

    // ── layout: build.json::ui.vault_connect.cockpit ─────────────────────

    /**
     * [icon]: the drawable name the card's round badge shows. [observed] false
     * marks a section whose device side this app cannot read at all, so its
     * light is [StatusLight.State.UNVERIFIABLE] rather than a colour it could
     * not justify — see the `_doc_cards` note beside the declaration.
     */
    data class Section(
        val id: String, val label: String, val vault: List<String>,
        val icon: String = "", val observed: Boolean = true,
    )

    /** [aiTokens]: vault `ai.tokens.<item>` → the device provider id the token feeds.
     *  [deviceIcons]: electronics `type` → the hero orb's drawable; `_default` for the rest.
     *  [journeyIcons]: the Connect journey's step (#573, `sign_in`/`who`/`device`/`get`) → its badge's drawable. */
    data class Layout(
        val sections: List<Section>, val aiTokens: Map<String, String>,
        val deviceIcons: Map<String, String> = emptyMap(),
        val journeyIcons: Map<String, String> = emptyMap(),
    )

    fun parseLayout(o: JSONObject): Layout {
        val arr = o.optJSONArray("sections") ?: JSONArray()
        val sections = (0 until arr.length()).map { i ->
            val s = arr.getJSONObject(i)
            val v = s.optJSONArray("vault") ?: JSONArray()
            Section(s.getString("id"), s.getString("label"), (0 until v.length()).map { v.getString(it) },
                s.optString("icon"), s.optBoolean("observed", true))
        }
        val tokens = o.optJSONObject("ai_tokens") ?: JSONObject()
        val icons = o.optJSONObject("device_icons") ?: JSONObject()
        val journey = o.optJSONObject("journey_icons") ?: JSONObject()
        return Layout(
            sections,
            tokens.keys().asSequence().associateWith { tokens.getString(it) },
            icons.keys().asSequence().associateWith { icons.getString(it) },
            journey.keys().asSequence().associateWith { journey.getString(it) },
        )
    }

    /** The hero orb's drawable name for [device] (null = nothing chosen yet):
     *  its declared type's entry, else `_default`, else blank (the icon lookup's own fallback). */
    fun deviceIcon(layout: Layout, device: Device?): String =
        layout.deviceIcons[device?.type.orEmpty()] ?: layout.deviceIcons[DEVICE_ICON_DEFAULT] ?: ""

    val layout: Layout by lazy {
        runCatching {
            parseLayout(JSONObject(String(Base64.decode(BuildConfig.UI_VAULT_CONNECT_COCKPIT_B64, Base64.DEFAULT))))
        }.getOrDefault(Layout(emptyList(), emptyMap()))
    }

    // ── the device this phone is ─────────────────────────────────────────

    /** [type]: the entry's declared kind (notebook, phone, …), as the vault spells it. */
    data class Device(val id: String, val label: String, val wgIp: String, val wgIpv6: String, val type: String = "")

    /**
     * Every device the vault's `electronics` section declares with a `wg_peer`
     * (a fleet WireGuard client: name, wg_ip, wg_ipv6). Groups (computers,
     * phones, …) are walked, not named. A pending group or entry is simply not
     * a selectable device.
     */
    fun devices(bundle: JSONObject): List<Device> {
        val out = mutableListOf<Device>()
        val electronics = bundle.optJSONObject("electronics")
        electronics?.keys()?.forEach { group ->
            val g = electronics.optJSONObject(group) ?: return@forEach
            g.keys().forEach { id ->
                val entry = g.optJSONObject(id) ?: return@forEach
                val peer = entry.optJSONObject("wg_peer") ?: return@forEach
                if (peer.optBoolean("pending")) return@forEach
                val ip = peer.optString("wg_ip")
                if (ip.isBlank()) return@forEach
                out += Device(id, peer.optString("name").ifBlank { id }, ip, peer.optString("wg_ipv6"),
                    (entry.opt("type") as? String).orEmpty())
            }
        }
        // #573: the vault's peers section (`peers.<id>.wg0`, one entry per
        // device of the ONE user declaration) declares devices the same way;
        // an id already known from electronics is not listed twice.
        val peers = bundle.optJSONObject("peers")
        peers?.keys()?.forEach { id ->
            val entry = peers.optJSONObject(id) ?: return@forEach
            val wg0 = entry.optJSONObject("wg0") ?: return@forEach
            if (wg0.optBoolean("pending")) return@forEach
            val ip = wg0.optString("wg_ip")
            if (ip.isBlank() || out.any { it.id == id }) return@forEach
            out += Device(id, wg0.optString("name").ifBlank { id }, ip, wg0.optString("wg_ipv6"), "")
        }
        return out
    }

    fun selectedDevice(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(K_DEVICE, "") ?: ""

    fun selectDevice(ctx: Context, id: String) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(K_DEVICE, id).apply()

    // ── rows ─────────────────────────────────────────────────────────────

    enum class State { MATCH, DIFFERS, ABSENT, PENDING }

    /** One comparison: what the vault declares against what the device holds. */
    data class Row(val label: String, val declared: String, val device: String, val state: State)

    /**
     * One card's light, from its rows, through the SHARED [StatusLight] states.
     *
     * A section this app cannot observe is UNVERIFIABLE whatever the rows say.
     * Otherwise a single row that differs or is absent turns the card OFF — a
     * card is configured only when everything on it is — a match with nothing
     * against it is ON, and rows that are all pending in the vault are UNKNOWN,
     * because nobody can currently say. No rows at all is UNKNOWN too.
     */
    fun sectionLight(rows: List<Row>, observed: Boolean = true): StatusLight.State = when {
        !observed -> StatusLight.State.UNVERIFIABLE
        rows.isEmpty() -> StatusLight.State.UNKNOWN
        rows.any { it.state == State.DIFFERS || it.state == State.ABSENT } -> StatusLight.State.OFF
        rows.any { it.state == State.MATCH } -> StatusLight.State.ON
        else -> StatusLight.State.UNKNOWN
    }

    /** The hero's light over every card's: any OFF is OFF, all ON is ON, else nobody can say. */
    fun overallLight(lights: Collection<StatusLight.State>): StatusLight.State = when {
        lights.isEmpty() -> StatusLight.State.UNKNOWN
        lights.any { it == StatusLight.State.OFF } -> StatusLight.State.OFF
        lights.all { it == StatusLight.State.ON } -> StatusLight.State.ON
        else -> StatusLight.State.UNKNOWN
    }

    /** Row counts for a card's summary line: matching, differing (absent counts as differing), pending. */
    data class Tally(val match: Int, val differ: Int, val pending: Int)

    fun tally(rows: List<Row>) = Tally(
        rows.count { it.state == State.MATCH },
        rows.count { it.state == State.DIFFERS || it.state == State.ABSENT },
        rows.count { it.state == State.PENDING },
    )

    private fun pending(v: Any?) = v is JSONObject && v.optBoolean("pending")
    private fun pendingText(v: JSONObject) = "pending · ${v.optString("source")} · ${v.optString("reason")}"

    // ── mesh ─────────────────────────────────────────────────────────────

    /** `Address = a, b, c` of a wg-quick text, as its bare addresses (no prefix length). */
    private fun addressesOf(conf: String): List<String> =
        conf.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("Address", ignoreCase = true) && it.contains('=') }
            ?.substringAfter('=')?.split(',')?.map { it.trim().substringBefore('/') }
            .orEmpty()

    /**
     * The mesh profiles that are [device]'s: every entry of `mesh.profiles`
     * whose Address line carries the device's declared wg_ip or wg_ipv6.
     * Ownership is derived from the two declarations, never from a file name.
     */
    fun meshProfiles(bundle: JSONObject, device: Device): Map<String, String> {
        val mine = setOf(device.wgIp, device.wgIpv6).filter { it.isNotBlank() }
        val candidates = mutableMapOf<String, String>()
        bundle.optJSONObject("mesh")?.optJSONObject("profiles")?.let { profiles ->
            profiles.keys().forEach { name -> (profiles.opt(name) as? String)?.let { candidates[name] = it } }
        }
        // #573: the peers section files each phone's profiles under its own id;
        // the same rule applies — a profile is the device's by its Address line.
        bundle.optJSONObject("peers")?.let { peers ->
            peers.keys().forEach { id ->
                val profiles = peers.optJSONObject(id)?.optJSONObject("profiles") ?: return@forEach
                profiles.keys().forEach { name -> (profiles.opt(name) as? String)?.let { candidates.putIfAbsent("$id/$name", it) } }
            }
        }
        return candidates.filter { (_, conf) -> addressesOf(conf).any { it in mine } }.toSortedMap()
    }

    /** What this device's tunnel is right now, for the comparison column. */
    data class TunnelState(val name: String, val address: String, val peerKeys: Set<String>)

    fun tunnelState(prefs: WireGuardPrefs) = TunnelState(
        prefs.tunnelName, prefs.interfaceAddress, prefs.peers().map { it.publicKey }.toSet())

    fun meshRows(bundle: JSONObject, device: Device, current: TunnelState): List<Row> =
        meshProfiles(bundle, device).map { (name, conf) ->
            val cfg = runCatching { Config.parse(BufferedReader(StringReader(conf))) }.getOrNull()
            val declaredAddress = cfg?.getInterface()?.addresses?.joinToString(", ") ?: addressesOf(conf).joinToString(", ")
            val declaredKeys = cfg?.peers?.map { it.publicKey.toBase64() }?.toSet() ?: emptySet()
            val same = declaredAddress == current.address && declaredKeys == current.peerKeys
            Row(name, "$declaredAddress · ${declaredKeys.size} peers",
                "${current.name.ifBlank { "—" }} · ${current.address.ifBlank { "no address" }} · ${current.peerKeys.size} peers",
                if (same) State.MATCH else State.DIFFERS)
        }

    /** The vault's conf goes through the SAME parser the .conf import uses; a
     *  text the WireGuard parser rejects writes nothing. Returns the report line. */
    fun applyMesh(prefs: WireGuardPrefs, name: String, conf: String): String {
        val cfg = try {
            Config.parse(BufferedReader(StringReader(conf)))
        } catch (t: Throwable) {
            return "✗ $name rejected by the WireGuard parser: ${t.message}"
        }
        prefs.tunnelName = name.take(15)
        prefs.hydrateFromConfig(cfg)
        prefs.configProvider = WireGuardPrefs.PROVIDER_CUSTOM
        return "✓ $name applied: ${cfg.peers.size} peers, key from the vault"
    }

    // ── mail ─────────────────────────────────────────────────────────────

    data class MailDeclared(val account: String, val email: String, val password: String?, val domain: String)

    /**
     * The vault mail account for [email]: `mail.accounts` is the Stalwart
     * users map (key → {name, pass_env}); the one whose `name` is the local
     * part of the address is this device's, and its password is
     * `mail.passwords[pass_env]`. No match → null, and the section says so.
     */
    fun mailDeclared(bundle: JSONObject, email: String): MailDeclared? {
        val mail = bundle.optJSONObject("mail") ?: return null
        val local = email.substringBefore('@').trim()
        if (local.isBlank()) return null
        val accounts = mail.optJSONObject("accounts") ?: return null
        val key = accounts.keys().asSequence()
            .firstOrNull { accounts.optJSONObject(it)?.optString("name") == local } ?: return null
        val passEnv = accounts.getJSONObject(key).optString("pass_env")
        val pw = mail.optJSONObject("passwords")?.opt(passEnv)
        return MailDeclared(
            account = key,
            email = email,
            password = (pw as? String)?.takeIf { it.isNotBlank() },
            domain = mail.optJSONObject("endpoints")?.optString("domain").orEmpty(),
        )
    }

    fun mailRows(d: MailDeclared?, prefs: JmapPrefs): List<Row> {
        if (d == null) return emptyList()
        val emailState = if (prefs.email == d.email) State.MATCH else if (prefs.email.isBlank()) State.ABSENT else State.DIFFERS
        val pwState = when {
            d.password == null -> State.PENDING
            prefs.password.isBlank() -> State.ABSENT
            prefs.password == d.password -> State.MATCH
            else -> State.DIFFERS
        }
        return listOf(
            Row("account", "${d.account} → ${d.email}", prefs.email.ifBlank { "—" }, emailState),
            Row("password", if (d.password == null) "not in the vault" else "•••• (${d.password.length} chars)",
                if (prefs.password.isBlank()) "—" else "•••• stored", pwState),
            Row("jmap host", d.domain.ifBlank { "—" }, prefs.server.ifBlank { "—" },
                if (d.domain.isNotBlank() && prefs.server.contains(d.domain)) State.MATCH else State.DIFFERS),
        )
    }

    /** Writes the address and, when the vault holds one, the password. The
     *  JMAP server URL is left alone: the vault declares a host, not a
     *  session path, and inventing one here is how a wrong URL gets stored. */
    fun applyMail(prefs: JmapPrefs, d: MailDeclared): String {
        prefs.email = d.email
        if (d.password != null) prefs.password = d.password
        return "✓ ${d.email} applied" + if (d.password == null) " (no password in the vault)" else ""
    }

    // ── keyboard & clipboards ────────────────────────────────────────────

    /** `autocomplete.<list>`: a list is a JSON array of strings once exported;
     *  until then it is a pending marker. The keyboard owns its lists and this
     *  app cannot read them, so the device column states exactly that. */
    fun keyboardRows(bundle: JSONObject, deviceText: String): List<Row> {
        val auto = bundle.optJSONObject("autocomplete") ?: return emptyList()
        return auto.keys().asSequence().map { k ->
            val v = auto.opt(k)
            when {
                pending(v) -> Row(k, pendingText(v as JSONObject), deviceText, State.PENDING)
                v is JSONArray -> Row(k, "${v.length()} entries", deviceText, State.ABSENT)
                else -> Row(k, v.toString().take(80), deviceText, State.ABSENT)
            }
        }.toList()
    }

    // ── drive (private repos) ────────────────────────────────────────────

    fun driveRows(bundle: JSONObject, prefs: ConfigsPrefs): List<Row> {
        val git = bundle.optJSONObject("git") ?: return emptyList()
        fun secretRow(label: String, item: String, section: String, key: String): Row {
            val v = git.opt(item)
            val stored = prefs.secret(section, key)
            return when {
                pending(v) -> Row(label, pendingText(v as JSONObject), if (stored.isBlank()) "—" else "•••• stored", State.PENDING)
                v !is String || v.isBlank() -> Row(label, "not in the vault", if (stored.isBlank()) "—" else "•••• stored", State.PENDING)
                stored.isBlank() -> Row(label, "•••• (${v.length} chars)", "—", State.ABSENT)
                stored.trim() == v.trim() -> Row(label, "•••• (${v.length} chars)", "•••• stored", State.MATCH)
                else -> Row(label, "•••• (${v.length} chars)", "•••• stored, different", State.DIFFERS)
            }
        }
        val repos = git.optJSONArray("repos")
        val repoRow = Row("repos", when {
            repos != null -> (0 until repos.length()).joinToString(", ") { repos.opt(it).toString() }
            pending(git.opt("repos")) -> pendingText(git.getJSONObject("repos"))
            else -> "not in the vault"
        }, "declared list; cloud-drive reads it", if (repos != null) State.MATCH else State.PENDING)
        return listOf(
            secretRow("github token", "github_token", SECTION_GIT, K_GITHUB_TOKEN),
            secretRow("ssh key", "ssh_private_key", SECTION_SSH, K_VAULT_REPO_KEY),
            repoRow,
        )
    }

    /** Both credentials into the one encrypted blob, at the paths
     *  build.json::ui.import_schema declares (ssh.vault_repo_key, git.github_token). */
    fun applyDrive(bundle: JSONObject, prefs: ConfigsPrefs): String {
        val git = bundle.optJSONObject("git") ?: return "✗ no git section in the vault"
        val written = mutableListOf<String>()
        (git.opt("github_token") as? String)?.takeIf { it.isNotBlank() }?.let {
            prefs.putSecret(SECTION_GIT, K_GITHUB_TOKEN, it.trim()); written += "github token"
        }
        (git.opt("ssh_private_key") as? String)?.takeIf { it.isNotBlank() }?.let {
            prefs.putSecret(SECTION_SSH, K_VAULT_REPO_KEY, it); written += "ssh key"
        }
        return if (written.isEmpty()) "✗ the vault holds neither a token nor a key" else "✓ ${written.joinToString(" + ")} stored"
    }

    // ── AI ───────────────────────────────────────────────────────────────

    /** `providerId → (keyPresent, keyHint)` as the serving app answered. */
    data class AiState(val providers: Map<String, Pair<Boolean, String>>)

    fun aiState(snapshotJson: String?): AiState? {
        val root = runCatching { JSONObject(snapshotJson ?: return null) }.getOrNull() ?: return null
        val arr = root.optJSONArray("providers") ?: return AiState(emptyMap())
        return AiState((0 until arr.length()).associate { i ->
            val p = arr.getJSONObject(i)
            p.getString("id") to (p.optBoolean("key_present") to p.optString("key_hint"))
        })
    }

    fun aiRows(bundle: JSONObject, layout: Layout, state: AiState?, peerDownText: String, unmappedText: String): List<Row> {
        val tokens = bundle.optJSONObject("ai")?.optJSONObject("tokens") ?: return emptyList()
        return tokens.keys().asSequence().map { item ->
            val v = tokens.opt(item)
            val provider = layout.aiTokens[item]
            val declared = when {
                pending(v) -> pendingText(v as JSONObject)
                v is String -> "•••• (${v.length} chars)"
                else -> v.toString()
            }
            val device = when {
                provider == null -> unmappedText
                state == null -> peerDownText
                else -> state.providers[provider]?.let { (present, hint) ->
                    if (present) "$provider · key …$hint" else "$provider · no key"
                } ?: "$provider · unknown to the serving app"
            }
            val st = when {
                pending(v) || v !is String -> State.PENDING
                provider == null -> State.PENDING
                state == null -> State.ABSENT
                state.providers[provider]?.first == true &&
                    v.endsWith(state.providers[provider]!!.second) -> State.MATCH
                state.providers[provider]?.first == true -> State.DIFFERS
                else -> State.ABSENT
            }
            Row(item, declared, device, st)
        }.toList()
    }

    /** Every mapped, non-pending token goes to its provider through the same
     *  binder call the AI page's own "set key" uses. */
    fun applyAi(bundle: JSONObject, layout: Layout, client: TextToolsClient): String {
        val tokens = bundle.optJSONObject("ai")?.optJSONObject("tokens") ?: return "✗ no ai.tokens in the vault"
        val lines = layout.aiTokens.mapNotNull { (item, provider) ->
            val v = tokens.opt(item) as? String ?: return@mapNotNull null
            if (v.isBlank()) return@mapNotNull null
            val r = client.setAiRouting(provider, apiKey = v.trim())
            if (r.ok) "✓ $item → $provider" else "✗ $item → $provider: ${r.error}"
        }
        return if (lines.isEmpty()) "✗ nothing to apply: every mapped token is pending" else lines.joinToString("\n")
    }

    // ── apps ─────────────────────────────────────────────────────────────

    /**
     * The app set the vault declares for [device]: `apps.devices.<id>`, walked
     * for (a) any object that IS a Store ▸ Phone Apps inventory (#565's
     * `kind`), parsed by the one parser, and (b) any array of objects carrying
     * `package` (the fleet manifest referenced from the vault). Pending markers
     * contribute nothing. `ours` is THIS build's fleet, as in the import.
     */
    fun appsDeclared(bundle: JSONObject, device: Device, fleet: Set<String>): List<AppInventory.Entry> {
        val mine = bundle.optJSONObject("apps")?.optJSONObject("devices")?.opt(device.id) ?: return emptyList()
        val out = mutableListOf<AppInventory.Entry>()
        fun walk(v: Any?) {
            when {
                v is JSONObject && v.optString("kind") == AppInventory.KIND ->
                    out += runCatching { AppInventory.parse(v.toString()) }.getOrDefault(emptyList())
                pending(v) -> Unit
                v is JSONObject -> v.keys().forEach { walk(v.get(it)) }
                v is JSONArray -> (0 until v.length()).forEach { i ->
                    val o = v.optJSONObject(i)
                    val pkg = o?.optString("package").orEmpty()
                    if (pkg.isNotBlank()) out += AppInventory.Entry(pkg, "", 0, null, pkg in fleet, null)
                    else walk(v.get(i))
                }
            }
        }
        walk(mine)
        return out.distinctBy { it.pkg }.sortedBy { it.pkg }
    }

    /** Sections the cockpit consumed; the rest of the bundle is shown raw. */
    fun consumed(layout: Layout): Set<String> = layout.sections.flatMap { it.vault }.toSet()

    private const val PREFS = "vault_cockpit"
    private const val K_DEVICE = "device_id"
    private const val DEVICE_ICON_DEFAULT = "_default"
    const val SECTION_GIT = "git"
    const val K_GITHUB_TOKEN = "github_token"
    const val SECTION_SSH = "ssh"
    const val K_VAULT_REPO_KEY = "vault_repo_key"
}
