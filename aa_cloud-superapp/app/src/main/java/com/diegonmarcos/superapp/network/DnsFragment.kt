package com.diegonmarcos.superapp.network

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.firewall.FirewallController
import com.wireguard.android.backend.Tunnel
import java.net.InetAddress
import java.text.DateFormat
import java.util.Date

/**
 * #740 Configs ▸ Watchdog ▸ Mesh ▸ DNS. Three blocks, every word of the
 * choices from `build.json::ui.dns` through [FleetDns.decl]:
 *  1. Android Private DNS — a replica of Android's own menu showing what the
 *     phone is set to and what the active network really uses; changed through
 *     the privileged shell channel when armed, else Android's settings open.
 *  2. Fleet DNS — Mirror Android or one declared preset (+ the ordered
 *     fallbacks for Private with fallbacks). Saving re-applies it to a running
 *     Cloud Mesh tunnel, which is the VPN every fleet app resolves through, and
 *     (#751) hands the engine the mesh-down form it carries while the mesh is off.
 *  3. Status — (#794) whether the preset is IN EFFECT: what it promises next
 *     to the DNS servers Android really hands this app, in red when they
 *     differ. When the engine that owns the VPN slot (Cloud-Lib-Net-Wg) has no
 *     VPN consent, one tap asks for it, raises the tunnel and checks again.
 *     Plus the last successful lookup and a Test lookup naming the upstream.
 *  4. Bridges and 5. DNS servers (#794) — every fleet member's path to an
 *     answer with its terminal bridge, and every known server probed now;
 *     both rendered from [DnsOverview.collect], the body of
 *     /api/net/dns/overview.
 */
class DnsFragment : Fragment() {

    private val decl get() = FleetDns.decl
    private lateinit var prefs: FleetDns.Prefs
    private lateinit var androidState: TextView
    private lateinit var status: TextView
    private lateinit var consentButton: View
    private lateinit var results: TextView
    private lateinit var bridges: TextView
    private lateinit var servers: TextView

    /** #794 The engine's consent activity answers RESULT_OK once Android's VPN dialog is
     *  accepted (at once when consent is already held): hand it the choice and check it took. */
    private val consentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val ctx = context ?: return@registerForActivityResult
        if (r.resultCode != Activity.RESULT_OK) { toast("VPN permission refused — the preset stays off without the mesh"); refreshStatus(ctx); return@registerForActivityResult }
        status.text = "VPN allowed — starting the DNS tunnel and checking Android's resolver…"
        background({ runCatching { FleetDns.syncAndCheck(ctx, raiseNow = !FirewallController.isEnabled(ctx)) } }) { v ->
            v.fold({ toast(if (it.ok) "In effect: ${it.actual.joinToString(", ")}" else "Still not in effect — see Status") },
                   { toast("Not applied: ${it.message}") })
            refreshStatus(ctx)
        }
    }

    private fun askConsent(ctx: Context) {
        WgState.backend(ctx).consentIntent()?.let { consentLauncher.launch(it) }
            ?: toast("Cloud-Lib-Net-Wg is not installed — install it from Store ▸ Cloud Constellation ▸ Libs")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        prefs = FleetDns.Prefs(ctx)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(ctx, 18); setPadding(pad, pad, pad, pad)
        }

        // ── 1. Android Private DNS ──────────────────────────────────────
        col.addView(header(ctx, "Android Private DNS"))
        androidState = caption(ctx, "Reading…"); col.addView(androidState)
        val modeGroup = RadioGroup(ctx)
        decl.androidModes.forEachIndexed { i, (modeId, label) ->
            modeGroup.addView(RadioButton(ctx).apply { id = 1000 + i; text = label; tag = modeId })
        }
        col.addView(modeGroup)
        val host = EditText(ctx).apply { setSingleLine(); hint = decl.hostnameSuggestions.joinToString(" · ") }
        col.addView(host)
        col.addView(button(ctx, "Apply to Android") {
            val mode = modeGroup.findViewById<RadioButton>(modeGroup.checkedRadioButtonId)?.tag as? String
                ?: return@button toast("Pick a Private DNS mode")
            val hostname = host.text.toString().trim().ifEmpty { decl.hostnameSuggestions.firstOrNull().orEmpty() }
            background({ runCatching { FleetDns.writeAndroid(ctx, mode, hostname) } }) { r ->
                when {
                    r.isFailure -> toast("Refused: ${r.exceptionOrNull()?.message}")
                    r.getOrNull() == null -> {
                        toast("No privileged channel — set it in Android's Network settings")
                        startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                    }
                    else -> { toast("Android Private DNS set"); refreshAndroid(modeGroup, host) }
                }
            }
        })
        refreshAndroid(modeGroup, host)

        // ── 2. Fleet DNS ────────────────────────────────────────────────
        col.addView(header(ctx, "Fleet DNS"))
        col.addView(caption(ctx, "Applied once, at the VPN every fleet app resolves through: the Cloud Mesh tunnel while it is up, and without the mesh a DNS-only tunnel that routes nothing (the fleet resolver is unreachable then, so Private with fallbacks uses its fallbacks alone and Private only fails lookups). Mesh names (${decl.meshZones.joinToString(", ") { "*.$it" }}) go to the fleet resolver whenever the mesh is up."))
        val presetGroup = RadioGroup(ctx)
        val fallbackBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val current = FleetDns.effective(decl, prefs.preset)
        decl.presets.forEachIndexed { i, p ->
            presetGroup.addView(RadioButton(ctx).apply {
                id = 2000 + i
                text = if (p.available) "${p.label}\n${p.subtitle}" else "${p.label} — unavailable\n${p.unavailableReason}"
                isEnabled = p.available
                isChecked = p.id == current.id
                tag = p.id
            })
        }
        col.addView(presetGroup)
        col.addView(fallbackBox)
        fun drawFallbacks() {
            fallbackBox.removeAllViews()
            val p = FleetDns.effective(decl, prefs.preset)
            if (!p.fallbackAllowed) return
            fallbackBox.addView(caption(ctx, "Fallbacks, in the order you tick them: " +
                prefs.fallbacks.mapNotNull { decl.preset(it)?.label }.joinToString(" → ").ifEmpty { "none" }))
            for (fid in p.fallbackChoices) {
                val f = decl.preset(fid) ?: continue
                fallbackBox.addView(CheckBox(ctx).apply {
                    text = if (f.available) f.label else "${f.label} — unavailable"
                    isEnabled = f.available
                    isChecked = fid in prefs.fallbacks
                    setOnCheckedChangeListener { _, on ->
                        prefs.fallbacks = prefs.fallbacks.filter { it != fid } + (if (on) listOf(fid) else emptyList())
                        applyToTunnel(ctx); drawFallbacks()
                    }
                })
            }
        }
        presetGroup.setOnCheckedChangeListener { g, checked ->
            prefs.preset = g.findViewById<RadioButton>(checked)?.tag as? String ?: return@setOnCheckedChangeListener
            applyToTunnel(ctx); drawFallbacks()
        }
        drawFallbacks()

        // ── 3. Status ───────────────────────────────────────────────────
        col.addView(header(ctx, "Status"))
        status = readonly(ctx, ""); col.addView(status)
        consentButton = button(ctx, "Allow the VPN for ${FleetDns.engineLabel(ctx)}") { askConsent(ctx) }
            .apply { setBackgroundColor(0xFFDC2626.toInt()); visibility = View.GONE }
        col.addView(consentButton)
        col.addView(button(ctx, "Test lookup") { testLookup(ctx) })
        results = readonly(ctx, "${decl.testPublic} · ${decl.testMesh}"); col.addView(results)
        refreshStatus(ctx)

        // ── 4. Bridges · 5. DNS servers ─────────────────────────────────
        col.addView(header(ctx, "Bridges"))
        col.addView(caption(ctx, "How each fleet app resolves, as that app reports it (/api/net/dns), with what it talks to. Terminals go through their 127.0.0.1 bridge to Android's resolver; apps that resolve by themselves are flagged."))
        bridges = readonly(ctx, "Scan to ask every app."); col.addView(bridges)
        col.addView(header(ctx, "DNS servers"))
        col.addView(caption(ctx, "Every server the presets, the mesh and Android name: protocol, role, which preset uses it, reachable now (a test query) and which one answers."))
        servers = readonly(ctx, "Scan to probe them."); col.addView(servers)
        col.addView(button(ctx, "Scan bridges and servers") { scan(ctx) })

        return ScrollView(ctx).apply { addView(col) }
    }

    private fun refreshAndroid(modeGroup: RadioGroup, host: EditText) {
        val ctx = context ?: return
        background({ FleetDns.readAndroid(ctx) }) { a ->
            (0 until modeGroup.childCount).map { modeGroup.getChildAt(it) as RadioButton }
                .firstOrNull { it.tag == (a.mode ?: "opportunistic") }?.isChecked = true
            if (host.text.isEmpty()) host.setText(a.specifier.orEmpty())
            val label = decl.androidModes.firstOrNull { it.first == a.mode }?.second
                ?: if (a.mode == null) "unset or not readable (Android's default is Automatic)" else a.mode
            androidState.text = buildString {
                append("Android is set to: $label")
                if (a.mode == "hostname") append(" — ${a.specifier}")
                append("\nActive network${if (a.onVpn) " (VPN)" else ""}: DNS ${a.activeServers.joinToString(", ").ifEmpty { "—" }}")
                a.privateDnsActive?.let { append("\nPrivate DNS in use: ${if (it) "yes" else "no"}${a.privateDnsServer?.let { s -> " ($s)" } ?: ""}") }
                if (a.mode == "hostname") append("\nStrict Private DNS takes precedence: every lookup goes to ${a.specifier}, whatever the fleet preset.")
                val enc = FleetDns.effective(decl, prefs.preset).encryption
                if (enc.isNotEmpty() && a.mode == "off") append("\nThe fleet preset is encrypted only when Android's Private DNS is Automatic.")
            }
        }
    }

    private fun idleLabel(ctx: Context, idle: String): String = when {
        idle == "UP" -> "carried by the engine's DNS-only tunnel"
        idle == "STANDBY" -> "ready — the engine raises it when Cloud Mesh goes down"
        idle == "OFF" && !prefs.chosen -> "Android's own DNS — pick a preset here to apply one without the mesh"
        idle == "OFF" -> "Android's own DNS (Mirror)"
        FirewallController.isEnabled(ctx) -> "the firewall holds the one VPN slot ($idle)"
        else -> idle
    }

    /** #794 Promise next to reality, from [FleetDns.live] — the verdict the alert and the API read too. */
    private fun refreshStatus(ctx: Context) {
        background({ runCatching { FleetDns.live(ctx) } to prefs.lastLookup }) { (r, last) ->
            val p = FleetDns.effective(decl, prefs.preset)
            val live = r.getOrElse { e -> status.text = "Status unreadable: ${e.message}"; return@background }
            val v = live.verdict
            status.text = buildString {
                append("Preset: ${p.label}${if (prefs.chosen) "" else " (default, not chosen here)"}\nCloud Mesh: ${if (live.meshUp) "up" else "down"}\n")
                append("Preset promises: ${v.promised.joinToString(" → ").ifEmpty { "Android's own (mirror)" }}\n")
                append("Android resolves with: ${v.actual.joinToString(", ").ifEmpty { "—" }}${if (live.android.onVpn) " (VPN)" else " (network)"}\n")
                append(if (v.ok) "✓ ${v.why}" else "✗ ${v.why}")
                append("\nWithout the mesh: ${idleLabel(ctx, live.idle)}")
                append("\nLast successful lookup: ${last.ifEmpty { "none yet" }}")
            }
            status.setTextColor(if (v.ok) 0xFFFFFFFF.toInt() else com.diegonmarcos.superapp.ui.StatusLight.colour(ctx, com.diegonmarcos.superapp.ui.StatusLight.State.OFF))
            status.setBackgroundColor(if (v.ok) 0x33000000 else 0x55DC2626)
            consentButton.visibility = if (v.needsConsent) View.VISIBLE else View.GONE
        }
    }

    /** #794 Sections 4 and 5: one [DnsOverview.collect], rendered as text. */
    private fun scan(ctx: Context) {
        bridges.text = "Asking every fleet app…"; servers.text = "Probing…"
        background({ runCatching { DnsOverview.collect(ctx) } }) { r ->
            r.fold({ o -> bridges.text = DnsOverview.pathsText(o); servers.text = DnsOverview.serversText(o) },
                   { e -> bridges.text = "Scan failed: ${e.message}"; servers.text = "" })
        }
    }

    /**
     * Re-establish a running tunnel so its VPN carries the new DNS list, and
     * (#751) hand the engine the mesh-down form. It takes the slot at once when
     * the mesh is down — unless the firewall holds it: that one stays, and the
     * engine raises the choice the next time the mesh goes down. (#794) Then
     * check Android took it; missing VPN consent goes straight to the one tap.
     */
    private fun applyToTunnel(ctx: Context) {
        status.text = "Applying ${FleetDns.effective(decl, prefs.preset).label}…"
        background({
            runCatching {
                val up = FleetDns.meshUp(ctx)
                if (up) WgState.backend(ctx).setState(WgState.tunnel, Tunnel.State.UP, WgState.prefs(ctx).toTunnelConfig())
                up to FleetDns.syncAndCheck(ctx, raiseNow = !FirewallController.isEnabled(ctx))
            }
        }) { r ->
            r.fold({ (up, v) ->
                when {
                    v.needsConsent -> {
                        toast("${FleetDns.engineLabel(ctx)} needs the VPN permission for this preset")
                        askConsent(ctx)
                    }
                    up && v.ok -> toast("Cloud Mesh re-applied with the new DNS")
                    else -> toast(v.why)
                }
            }, { e -> toast("Not applied: ${e.message}") })
            refreshStatus(ctx)
        }
    }

    private fun testLookup(ctx: Context) {
        results.text = "Resolving…"
        background({
            val up = FleetDns.meshUp(ctx)
            val fleet = FleetDns.fleetResolvers(ctx)
            val plan = runCatching { FleetDns.promised(decl, prefs.preset, prefs.fallbacks, prefs.chosen, fleet, up) }
            listOf(decl.testPublic, decl.testMesh).filter { it.isNotEmpty() }.map { name ->
                val sys = runCatching { InetAddress.getAllByName(name).joinToString(", ") { it.hostAddress ?: "" } }
                    .getOrElse { "FAILED (${it.javaClass.simpleName})" }
                val routed = plan.fold({ servers ->
                    val ups = FleetDns.upstreamsFor(decl, name, up, servers, fleet)
                    if (ups.isEmpty()) null else ups.firstNotNullOfOrNull { s ->
                        runCatching { "${FleetDns.query(s, name, decl.timeoutMs)} via $s" }.getOrNull()
                    } ?: "FAILED — no upstream answered (${ups.joinToString(", ")})"
                }, { "FAILED — ${it.message}" })
                Triple(name, sys, routed)
            }
        }) { rows ->
            results.text = rows.joinToString("\n\n") { (name, sys, routed) ->
                "$name\n  system resolver: $sys" + (routed?.let { "\n  fleet plan: $it" } ?: "")
            }
            rows.firstOrNull { (_, sys, routed) -> routed?.startsWith("FAILED") == false || (routed == null && !sys.startsWith("FAILED")) }
                ?.let { (name, sys, routed) ->
                    prefs.lastLookup = "$name → ${routed ?: "$sys (system)"} at ${DateFormat.getTimeInstance().format(Date())}"
                }
            refreshStatus(ctx)
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun <T> background(work: () -> T, done: (T) -> Unit) {
        Thread {
            val r = work()
            view?.post { if (isAdded) done(r) }
        }.start()
    }

    private fun toast(msg: String) { context?.let { Toast.makeText(it, msg, Toast.LENGTH_SHORT).show() } }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private fun header(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        setTextAppearance(android.R.style.TextAppearance_Material_Headline)
        setPadding(0, dp(ctx, 16), 0, dp(ctx, 4))
    }

    private fun caption(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        setTextAppearance(android.R.style.TextAppearance_Material_Caption)
        alpha = 0.7f
        setPadding(0, 0, 0, dp(ctx, 8))
    }

    private fun readonly(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        setPadding(dp(ctx, 8), dp(ctx, 10), dp(ctx, 8), dp(ctx, 10))
        setBackgroundColor(0x33000000)
        typeface = android.graphics.Typeface.MONOSPACE
        setTextIsSelectable(true)
    }

    private fun button(ctx: Context, text: String, onClick: () -> Unit) = TextView(ctx).apply {
        this.text = text
        setTextColor(0xFFFFFFFF.toInt())
        setBackgroundColor(0xFF7C3AED.toInt())
        setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8))
        gravity = android.view.Gravity.CENTER
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(ctx, 6); bottomMargin = dp(ctx, 6) }
    }

    companion object {
        fun newInstance() = DnsFragment()
    }
}
