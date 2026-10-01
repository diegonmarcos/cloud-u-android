package com.diegonmarcos.superapp.network

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
import androidx.fragment.app.Fragment
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
 *     Cloud Mesh tunnel, which is the VPN every fleet app resolves through.
 *  3. Status — the resolver list in force, the last successful lookup and a
 *     Test lookup that names the upstream that answered.
 */
class DnsFragment : Fragment() {

    private val decl get() = FleetDns.decl
    private lateinit var prefs: FleetDns.Prefs
    private lateinit var androidState: TextView
    private lateinit var status: TextView
    private lateinit var results: TextView

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
        col.addView(caption(ctx, "Applied once, at the SuperApp's VPN (the Cloud Mesh tunnel): every fleet app resolves through it. Mesh names (${decl.meshZones.joinToString(", ") { "*.$it" }}) go to the fleet resolver whenever the mesh is up."))
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
        col.addView(button(ctx, "Test lookup") { testLookup(ctx) })
        results = readonly(ctx, "${decl.testPublic} · ${decl.testMesh}"); col.addView(results)
        refreshStatus(ctx)

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

    private fun meshUp(ctx: Context): Boolean =
        runCatching { WgState.backend(ctx).getState(WgState.tunnel) == Tunnel.State.UP }.getOrDefault(false)

    private fun fleetResolvers(ctx: Context) = FleetDns.splitServers(WgState.prefs(ctx).interfaceDns)

    private fun refreshStatus(ctx: Context) {
        background({
            val servers = runCatching { FleetDns.vpnServers(ctx, WgState.prefs(ctx).interfaceDns) }
            Triple(meshUp(ctx), servers, prefs.lastLookup)
        }) { (up, servers, last) ->
            val p = FleetDns.effective(decl, prefs.preset)
            status.text = buildString {
                append("Preset: ${p.label}\nCloud Mesh: ${if (up) "up" else "down"}\n")
                append("Active resolver: ")
                append(servers.fold({ l -> if (l.isEmpty()) "Android's own (mirror)" else l.joinToString(" → ") },
                                    { e -> "ERROR — ${e.message}" }))
                if (!up) append("\n(applies the next time Cloud Mesh connects; with it down, apps use Android's DNS)")
                append("\nLast successful lookup: ${last.ifEmpty { "none yet" }}")
            }
        }
    }

    /** Re-establish a running tunnel so its VPN carries the new DNS list. */
    private fun applyToTunnel(ctx: Context) {
        background({
            runCatching {
                if (!meshUp(ctx)) return@runCatching false
                WgState.backend(ctx).setState(WgState.tunnel, Tunnel.State.UP, WgState.prefs(ctx).toTunnelConfig())
                true
            }
        }) { r ->
            r.fold({ applied -> if (applied) toast("Cloud Mesh re-applied with the new DNS") else Unit },
                   { e -> toast("Not applied: ${e.message}") })
            refreshStatus(ctx)
        }
    }

    private fun testLookup(ctx: Context) {
        results.text = "Resolving…"
        background({
            val up = meshUp(ctx)
            val fleet = fleetResolvers(ctx)
            val plan = runCatching { FleetDns.vpnServers(ctx, WgState.prefs(ctx).interfaceDns) }
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
