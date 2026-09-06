package com.diegonmarcos.superapp.network
import com.diegonmarcos.superapp.profile.ProfileFragment

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.net.AidlBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.crypto.KeyPair
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Configs → WireGuard — Interface + a list of Peers form bound to
 * [WireGuardPrefs]. Auto-saves on every text change. Extra controls:
 * Generate Keypair (fills private + public), Import .conf (parses
 * upstream wg-quick format — multi-peer aware), Export .conf (writes
 * wg-quick), Connect/Disconnect Switch (drives [AidlBackend]). Add Peer
 * / Remove buttons let the user grow or shrink the peer list at
 * runtime.
 *
 * Mirrors [ProfileFragment]'s programmatic UI shape — no XML layouts.
 */
class WireGuardFragment : Fragment() {

    private lateinit var prefs: WireGuardPrefs
    /** Shared process-wide tunnel client + Tunnel — see [WgState]. The engine
     *  itself lives in Cloud-Lib-Net-Wg.apk; this is the binder client. */
    private val goBackend: AidlBackend? get() = context?.let { WgState.backend(it) }
    private val tunnel get() = WgState.tunnel

    /** Re-attach to redraw fields after structural changes (Generate,
     *  Import, Add Peer, Remove Peer). */
    private fun reattach() {
        parentFragmentManager.beginTransaction().detach(this).commitNow()
        parentFragmentManager.beginTransaction().attach(this).commitNow()
    }

    /** Read an imported .conf into prefs (multi-peer aware). */
    private val importLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri ?: return@registerForActivityResult
            runCatching {
                requireContext().contentResolver.openInputStream(uri)?.use { stream ->
                    val cfg = Config.parse(BufferedReader(InputStreamReader(stream)))
                    hydrateFromConfig(cfg)
                }
            }.onFailure { t ->
                toast("Import failed: ${t.message}")
            }.onSuccess {
                toast("Imported .conf")
                reattach()
            }
        }

    /** Save current state to a user-chosen .conf via Storage Access Framework. */
    private val exportLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            uri ?: return@registerForActivityResult
            runCatching {
                val cfg = prefs.toWgConfig()
                requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(cfg.toWgQuickString().toByteArray())
                }
            }.onFailure { t ->
                toast("Export failed: ${t.message}")
            }.onSuccess {
                toast("Exported .conf")
            }
        }

    /** First-ever Connect needs Android's VPN-consent dialog. */
    private val vpnConsentLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) bringTunnelUp() else {
                prefs.tunnelEnabled = false
                toast("VPN consent denied")
                reattach()
            }
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        prefs = WgState.prefs(ctx)
        // Touch the backend so it's eagerly initialised — DevControl
        // queries getStatistics() against the same Tunnel object.
        WgState.backend(ctx)

        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(ctx, 18); setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        scroll.addView(col)

        col.addView(sectionHeader(ctx, "WireGuard"))
        col.addView(caption(ctx, "Edit your tunnel — auto-saved on change. Use Generate Keypair to mint a fresh private+public, Import to load an existing .conf (multi-peer aware), or Connect to bring the tunnel up."))

        col.addView(label(ctx, "Tunnel name (≤15 chars: A-Z a-z 0-9 _ = + . -)"))
        col.addView(field(ctx, prefs.tunnelName) { prefs.tunnelName = it }.apply {
            filters = arrayOf(android.text.InputFilter.LengthFilter(Tunnel.NAME_MAX_LENGTH))
        })

        // ── Provider ─────────────────────────────────────────────────
        // Merged here from Configs → Profile, which used to carry a second,
        // partial copy of this screen. Where the tunnel's PUBLIC half comes
        // from: "Cloud" writes the fleet preset out of BuildConfig (baked from
        // build.json::ui.wireguard_default), "Custom" leaves everything below
        // exactly as set. Neither ever fills the private key.
        col.addView(sectionHeader(ctx, "Provider"))
        col.addView(providerSelector(ctx))
        col.addView(caption(ctx, PROVIDER_TEXT))

        // ── Interface ────────────────────────────────────────────────
        col.addView(sectionHeader(ctx, "Interface"))

        col.addView(label(ctx, "Private key (base64, 32 bytes)"))
        col.addView(field(ctx, prefs.interfacePrivateKey) {
            prefs.interfacePrivateKey = it
            updatePublicKeyView()
        }.also {
            interfacePrivateKeyField = it
            // MASKED. This box is still edit-in-place — the key has to be
            // pasteable and the derived public key is shown below it — but a
            // private key rendered as plaintext is readable over a shoulder,
            // in a recents thumbnail and in the accessibility tree. Masking
            // costs nothing here and was the one privacy property the Profile
            // copy had that this screen did not.
            it.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD
            it.imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            it.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        })

        col.addView(label(ctx, "Public key (derived from private key — read-only)"))
        col.addView(readonly(ctx, prefs.derivedInterfacePublicKey()).also { interfacePublicKeyView = it })

        col.addView(rowOfButtons(ctx,
            "Generate Keypair" to { generateKeypair() },
            "Import .conf"     to { importLauncher.launch("*/*") },
            "Export .conf"     to { exportLauncher.launch("${prefs.tunnelName.ifBlank { "wg" }}.conf") },
        ))

        // ── Export the fleet's four profiles ─────────────────────────
        // NOT the same thing as "Export .conf" above, and the difference is
        // the private key. That one serialises THIS tunnel, key included, to
        // move a working config you already own. This one writes the four
        // fleet TEMPLATES with no key in them at all — see [EXPORT_TEXT].
        col.addView(sectionHeader(ctx, "Export tunnel profiles"))
        col.addView(caption(ctx, EXPORT_TEXT))
        col.addView(caption(ctx, WireGuardProfiles.all
            .joinToString("\n") { "• ${it.fileName} — ${it.label}" }
            .ifBlank { "This build carries no profiles — nothing to export." }))
        col.addView(rowOfButtons(ctx,
            "Export 4 profiles…" to {
                if (WireGuardProfiles.all.isEmpty()) toast("No profiles in this build")
                else profileFolderPicker.launch(null)
            },
        ))

        col.addView(label(ctx, "Address (CIDR, e.g. 10.0.0.6/32)"))
        col.addView(field(ctx, prefs.interfaceAddress) { prefs.interfaceAddress = it })

        col.addView(label(ctx, "DNS"))
        col.addView(field(ctx, prefs.interfaceDns) { prefs.interfaceDns = it })

        col.addView(label(ctx, "Listen port (optional)"))
        col.addView(field(ctx, prefs.interfaceListenPort) { prefs.interfaceListenPort = it }.apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        })

        col.addView(label(ctx, "MTU (optional, default 1420)"))
        col.addView(field(ctx, prefs.interfaceMtu) { prefs.interfaceMtu = it }.apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        })

        // ── Peers ────────────────────────────────────────────────────
        val peers = prefs.peers()
        for ((idx, p) in peers.withIndex()) {
            col.addView(peerCard(ctx, idx, p, peers))
        }
        col.addView(addPeerButton(ctx, peers))

        // ── Connect/Disconnect ──────────────────────────────────────
        // Status header carries a live colour dot — green UP, amber CANNOT
        // TELL, grey down.
        //
        // THREE STATES, NOT TWO. [WgState.backend] returns a working object
        // even when the engine APK is absent, and then reports DOWN for
        // everything — so a grey dot used to mean "your tunnel is down" and
        // "this app has no way to know", which are not the same claim. With
        // the engine missing the truth is only discoverable by tapping
        // Connect and reading a toast. Merged from the Profile screen, which
        // had the honest version.
        val enginePresent = runCatching { goBackend?.isEngineInstalled() == true }
            .getOrDefault(false)
        val tunnelUp = enginePresent && (goBackend?.getState(tunnel) == Tunnel.State.UP)
        col.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(sectionHeader(ctx, "Status"))
            addView(View(ctx).apply {
                val d = dp(ctx, 13)
                layoutParams = LinearLayout.LayoutParams(d, d).apply { leftMargin = dp(ctx, 10) }
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(when {
                        tunnelUp -> 0xFF34C759.toInt()        // up
                        !enginePresent -> 0xFFF59E0B.toInt()  // cannot tell
                        else -> 0xFF888888.toInt()            // down
                    })
                }
            })
        })
        col.addView(Switch(ctx).apply {
            text = "Connect"
            isChecked = tunnelUp
            val pad = dp(ctx, 6); setPadding(pad, pad, pad, pad)
            setOnCheckedChangeListener { _, checked ->
                if (checked) requestConnect() else requestDisconnect()
            }
        })
        col.addView(tunnelStatusView(ctx, enginePresent, tunnelUp))

        // ── Mesh — all infos folded onto this one page ───────────────
        // The canonical wg-mesh table (data/mesh.json) rendered inline via
        // the shared MeshView builder, so Configs → WireGuard is a single
        // page: config + import + status + the full mesh topology.
        col.addView(sectionHeader(ctx, "Mesh"))
        col.addView(caption(ctx, com.diegonmarcos.superapp.cloud.MeshView.statusText(ctx)))
        col.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            com.diegonmarcos.superapp.cloud.MeshView.render(ctx, inflater, this)
        })

        return scroll
    }

    private var interfacePrivateKeyField: EditText? = null
    private var interfacePublicKeyView: TextView? = null

    /**
     * Renders one peer's full set of fields in a card-like container.
     * Edits mutate the in-memory `peers` list AND persist on every
     * keystroke. Remove button cuts the peer and reattaches.
     */
    private fun peerCard(
        ctx: android.content.Context,
        index: Int,
        peer: WireGuardPrefs.PeerData,
        peers: MutableList<WireGuardPrefs.PeerData>,
    ): View {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(ctx, 10); setPadding(pad, pad, pad, pad)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(ctx, 16) }
            layoutParams = lp
            setBackgroundColor(0x22FFFFFF)
        }

        // Header row: "Peer N — <name>" + Remove button (right).
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        headerRow.addView(TextView(ctx).apply {
            text = "Peer ${index + 1}"
            setTextAppearance(android.R.style.TextAppearance_Material_Title)
            layoutParams = LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        headerRow.addView(TextView(ctx).apply {
            text = "Remove"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFFB91C1C.toInt())
            setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 10), dp(ctx, 6))
            isClickable = true; isFocusable = true
            setOnClickListener {
                peers.removeAt(index)
                prefs.savePeers(peers)
                toast("Removed peer ${index + 1}")
                reattach()
            }
        })
        card.addView(headerRow)

        card.addView(label(ctx, "Name (UI label, e.g. gcp-proxy)"))
        card.addView(peerField(ctx, peer.name, peers, index) { p, v -> p.copy(name = v) })

        card.addView(label(ctx, "Public key"))
        card.addView(peerField(ctx, peer.publicKey, peers, index) { p, v -> p.copy(publicKey = v) })

        card.addView(label(ctx, "Pre-shared key (optional)"))
        card.addView(peerField(ctx, peer.presharedKey, peers, index) { p, v -> p.copy(presharedKey = v) })

        card.addView(label(ctx, "Endpoint (host:port)"))
        card.addView(peerField(ctx, peer.endpoint, peers, index) { p, v -> p.copy(endpoint = v) })

        card.addView(label(ctx, "Allowed IPs (comma-separated)"))
        card.addView(peerField(ctx, peer.allowedIps, peers, index) { p, v -> p.copy(allowedIps = v) })

        card.addView(label(ctx, "Persistent keepalive (seconds, optional)"))
        card.addView(peerField(ctx, peer.persistentKeepalive, peers, index) { p, v -> p.copy(persistentKeepalive = v) }.apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        })

        return card
    }

    /**
     * Per-peer EditText. The `mutate` lambda gets the current
     * [WireGuardPrefs.PeerData] plus the new text value, and returns
     * an updated copy. The TextWatcher swaps it into the list and
     * persists on every keystroke.
     */
    private fun peerField(
        ctx: android.content.Context,
        initial: String,
        peers: MutableList<WireGuardPrefs.PeerData>,
        index: Int,
        mutate: (WireGuardPrefs.PeerData, String) -> WireGuardPrefs.PeerData,
    ): EditText = EditText(ctx).apply {
        setText(initial)
        setSingleLine()
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (index < peers.size) {
                    peers[index] = mutate(peers[index], s?.toString().orEmpty())
                    prefs.savePeers(peers)
                }
            }
        })
    }

    private fun addPeerButton(
        ctx: android.content.Context,
        peers: MutableList<WireGuardPrefs.PeerData>,
    ): View = TextView(ctx).apply {
        text = "+ Add Peer"
        setTextColor(0xFFFFFFFF.toInt())
        setBackgroundColor(0xFF059669.toInt())
        gravity = android.view.Gravity.CENTER
        setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
        isClickable = true; isFocusable = true
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(ctx, 12) }
        layoutParams = lp
        setOnClickListener {
            peers.add(WireGuardPrefs.PeerData.EMPTY)
            prefs.savePeers(peers)
            toast("Added empty peer — fill it in")
            reattach()
        }
    }

    private fun updatePublicKeyView() {
        interfacePublicKeyView?.text = prefs.derivedInterfacePublicKey()
    }

    private fun generateKeypair() {
        val kp = KeyPair()
        prefs.interfacePrivateKey = kp.privateKey.toBase64()
        interfacePrivateKeyField?.setText(prefs.interfacePrivateKey)
        updatePublicKeyView()
        toast("Generated keypair")
    }

    /** Delegates to [WireGuardPrefs.hydrateFromConfig] — the single import
     *  path shared with the Authelia auto-import in Configs → Profile. */
    private fun hydrateFromConfig(cfg: Config) = prefs.hydrateFromConfig(cfg)

    private fun requestConnect() {
        val backend = goBackend
        if (backend == null || !backend.isEngineInstalled()) {
            // VpnService.prepare() only grants consent to the package owning
            // the service, and that package is the engine APK now - so with it
            // absent there is nothing to ask and nothing to start. Say which
            // APK and where to get it rather than failing silently.
            Toast.makeText(requireContext(),
                "WireGuard engine not installed — install Cloud-Lib-Net-Wg from Configs → Constellation → Libs",
                Toast.LENGTH_LONG).show()
            return
        }
        val intent = backend.consentIntent()
        if (intent != null) {
            // The engine's consent activity returns RESULT_OK once the system
            // dialog is answered; the existing launcher already brings the
            // tunnel up on OK.
            vpnConsentLauncher.launch(intent)
        } else {
            bringTunnelUp()
        }
    }

    private fun bringTunnelUp() {
        runCatching {
            val cfg = prefs.toWgConfig()
            goBackend?.setState(tunnel, Tunnel.State.UP, cfg)
            prefs.tunnelEnabled = true
        }.onFailure { t ->
            prefs.tunnelEnabled = false
            toast("Connect failed: ${t.message}")
            reattach()
        }
    }

    private fun requestDisconnect() {
        runCatching {
            goBackend?.setState(tunnel, Tunnel.State.DOWN, null)
            prefs.tunnelEnabled = false
        }.onFailure { t ->
            toast("Disconnect failed: ${t.message}")
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    // ── widget factories ──

    private fun rowOfButtons(ctx: android.content.Context, vararg pairs: Pair<String, () -> Unit>): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            val pad = dp(ctx, 6); setPadding(0, pad, 0, pad)
        }
        for ((idx, p) in pairs.withIndex()) {
            val btn = TextView(ctx).apply {
                text = p.first
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xFF7C3AED.toInt())
                setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8))
                gravity = android.view.Gravity.CENTER
                isClickable = true; isFocusable = true
                setOnClickListener { p.second() }
            }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (idx > 0) leftMargin = dp(ctx, 6)
            }
            btn.layoutParams = lp
            row.addView(btn)
        }
        return row
    }

    private fun sectionHeader(ctx: android.content.Context, text: String): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextAppearance(android.R.style.TextAppearance_Material_Headline)
            setPadding(0, dp(ctx, 16), 0, dp(ctx, 4))
        }

    private fun label(ctx: android.content.Context, text: String): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
            alpha = 0.85f
            setPadding(0, dp(ctx, 12), 0, dp(ctx, 4))
        }

    private fun caption(ctx: android.content.Context, text: String): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            alpha = 0.55f
            setPadding(0, 0, 0, dp(ctx, 8))
        }

    private fun field(ctx: android.content.Context, initial: String, save: (String) -> Unit): EditText =
        EditText(ctx).apply {
            setText(initial)
            setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) { save(s?.toString().orEmpty()) }
            })
        }

    private fun readonly(ctx: android.content.Context, text: String): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(0xFFDDDDDD.toInt())
            setPadding(dp(ctx, 8), dp(ctx, 10), dp(ctx, 8), dp(ctx, 10))
            setBackgroundColor(0x33000000)
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }

    private fun dp(ctx: android.content.Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    // ── provider ─────────────────────────────────────────────────────────
    // Everything below this line was merged in from Configs → Profile, which
    // used to carry a second, partial copy of this screen: it had the Provider
    // preset, the key-free profile export and the honest status, but no peer
    // editor and no .conf import. One screen now, so there is nothing to keep
    // in step.

    /**
     * Where the tunnel's PUBLIC configuration comes from.
     *
     * Choosing Cloud over settings that are NOT already the preset ASKS FIRST.
     * The values it would replace can be a hand-built tunnel the user has no
     * copy of, and a dropdown quietly eating them is not a trade this screen
     * gets to make. Choosing Custom never writes anything.
     */
    private fun providerSelector(ctx: android.content.Context): View {
        val spinner = android.widget.Spinner(ctx)
        spinner.adapter = android.widget.ArrayAdapter(
            ctx,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("Cloud", "Custom"),
        )
        spinner.setSelection(if (prefs.configProvider == WireGuardPrefs.PROVIDER_CUSTOM) 1 else 0)
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?, v: View?, position: Int, id: Long,
            ) {
                val picked =
                    if (position == 1) WireGuardPrefs.PROVIDER_CUSTOM
                    else WireGuardPrefs.PROVIDER_CLOUD
                // Swallows the callback Spinner fires for the setSelection
                // above, and the one the revert fires — neither is a choice.
                if (picked == prefs.configProvider) return
                if (picked == WireGuardPrefs.PROVIDER_CUSTOM) {
                    prefs.configProvider = picked
                    toast("Provider: Custom — your WireGuard settings are untouched")
                } else if (prefs.matchesCloudPreset()) {
                    prefs.configProvider = picked
                    toast("Provider: Cloud")
                } else {
                    confirmCloudPreset(ctx) { spinner.setSelection(1) }
                }
            }
        }
        return spinner
    }

    /** Ask before Cloud overwrites a config the user actually entered. */
    private fun confirmCloudPreset(ctx: android.content.Context, onKeepMine: () -> Unit) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle("Replace your WireGuard settings?")
            .setMessage(
                "Cloud replaces the tunnel's addresses, DNS, MTU and entire peer " +
                "list with the fleet preset. What is stored now differs from it, so " +
                "those values are lost.\n\n" +
                "Your private key is NOT touched by either provider."
            )
            .setNegativeButton("Keep mine") { _, _ -> onKeepMine() }
            .setOnCancelListener { onKeepMine() }
            .setPositiveButton("Use Cloud") { _, _ ->
                prefs.applyCloudPreset()
                prefs.configProvider = WireGuardPrefs.PROVIDER_CLOUD
                toast("Cloud preset applied — this device still needs its own private key")
                reattach()
            }
            .show()
    }

    // ── export · the four fleet profiles ─────────────────────────────────

    /**
     * Write the four profiles into a folder the USER picks.
     *
     * A folder rather than four save dialogs, and SAF rather than a path: the
     * app writes only where it has just been handed permission, and nothing at
     * all until then.
     */
    private val profileFolderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
            tree ?: return@registerForActivityResult
            exportProfilesTo(tree)
        }

    private fun exportProfilesTo(tree: android.net.Uri) {
        val resolver = requireContext().contentResolver
        // Platform SAF, not androidx.documentfile — that artifact is not a
        // dependency of this module and one export is not worth adding one.
        val dirUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(
            tree, android.provider.DocumentsContract.getTreeDocumentId(tree))
        val written = mutableListOf<String>()
        val failed = mutableListOf<String>()
        WireGuardProfiles.all.forEach { profile ->
            val ok = runCatching {
                deleteExisting(dirUri, profile.fileName)
                val fileUri = android.provider.DocumentsContract.createDocument(
                    resolver, dirUri, "text/plain", profile.fileName,
                ) ?: error("could not create ${profile.fileName}")
                resolver.openOutputStream(fileUri)?.use {
                    it.write(WireGuardProfiles.render(profile).toByteArray())
                } ?: error("could not write ${profile.fileName}")
            }.isSuccess
            if (ok) written += profile.fileName else failed += profile.fileName
        }
        // Reported per file. "Exported" over a partial write is how a missing
        // profile gets discovered on the train instead of here.
        toast(
            if (failed.isEmpty()) "Exported ${written.size} profiles — no private key included"
            else "Exported ${written.size}, FAILED ${failed.size}: ${failed.joinToString()}"
        )
    }

    /**
     * Drop a same-named file before writing.
     *
     * SAF's `createDocument` RENAMES on collision rather than replacing, so a
     * re-export after the hub moves would leave "config-v4-split (1).conf"
     * next to the stale file the user already imported — and the stale one
     * keeps the name they would reach for. Best-effort.
     */
    private fun deleteExisting(dirUri: android.net.Uri, name: String) {
        val resolver = requireContext().contentResolver
        runCatching {
            val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
                dirUri, android.provider.DocumentsContract.getDocumentId(dirUri))
            resolver.query(
                children,
                arrayOf(
                    android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                ),
                null, null, null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(1) != name) continue
                    android.provider.DocumentsContract.deleteDocument(
                        resolver,
                        android.provider.DocumentsContract.buildDocumentUriUsingTree(
                            dirUri, cursor.getString(0)),
                    )
                }
            }
        }
    }

    // ── status · what this device can honestly say ───────────────────────

    /**
     * The text beside the dot, and the per-peer live counters.
     *
     * The app can read [Tunnel.State] and per-peer handshake/byte counters,
     * but ONLY for the tunnel it owns through the engine APK — there is no
     * netlink, no `wg show`, and no visibility into a tunnel the official
     * WireGuard app is running. So peer rows are CONFIGURATION — what this
     * device would dial — and carry live figures only where the backend
     * supplied them.
     */
    private fun tunnelStatusView(
        ctx: android.content.Context,
        enginePresent: Boolean,
        tunnelUp: Boolean,
    ): View {
        val stats = if (enginePresent) runCatching {
            goBackend?.getStatistics(tunnel)
        }.getOrNull() else null

        val head = when {
            !enginePresent ->
                "CANNOT TELL — the WireGuard engine app is not installed, so this app " +
                "cannot read tunnel state. If your mesh is up, another app is running " +
                "it and nothing here can see it."
            tunnelUp -> "CONNECTED — this app's tunnel \"${prefs.tunnelName}\" is up."
            else ->
                "NOT CONNECTED — this app's tunnel \"${prefs.tunnelName}\" is down. A " +
                "tunnel run by the official WireGuard app is invisible here and would " +
                "also read as down."
        }

        val peers = prefs.peers().joinToString("\n\n") { peer ->
            val live = runCatching {
                stats?.peer(com.wireguard.crypto.Key.fromBase64(peer.publicKey))
            }.getOrNull()
            val handshake = live?.latestHandshakeEpochMillis() ?: 0L
            buildString {
                append("${peer.name.ifBlank { "peer" }} · ${peer.endpoint}")
                append("\n  last handshake: ")
                append(when {
                    !enginePresent -> "unknown"
                    handshake > 0L -> "${(System.currentTimeMillis() - handshake) / 1000}s ago"
                    tunnelUp -> "never — configured but not talking"
                    else -> "—"
                })
                if (live != null) {
                    append("\n  traffic: down ${live.rxBytes()} B · up ${live.txBytes()} B")
                }
            }
        }.ifBlank { "No peers configured." }

        return caption(ctx, "$head\n\n$peers\n\n$STATUS_TEXT").apply {
            setTextIsSelectable(true)
        }
    }

    companion object {
        fun newInstance(): WireGuardFragment = WireGuardFragment()

        /**
         * What each provider does and does not fill.
         *
         * The private-key paragraph is not boilerplate. "Cloud" reads as
         * "everything is handled", and the one field it cannot hand over is
         * the one without which nothing connects — so the limit is stated
         * where the choice is made rather than discovered at Connect time.
         */
        private const val PROVIDER_TEXT =
            "Cloud fills the PUBLIC half of the tunnel from the fleet's own " +
            "configuration: hub endpoint and public key, allowed IPs, this device's " +
            "addresses, DNS, MTU and keepalive. Custom leaves everything below " +
            "exactly as you set it.\n\n" +
            "Neither provider fills the private key, and no preset ever will. A " +
            "WireGuard private key identifies ONE device — two devices sharing one " +
            "are a single peer to the hub, and they take turns knocking each other " +
            "off the mesh. Generate this device's own pair, or paste a key you " +
            "already hold. Either way the public half has to be added to the hub " +
            "before the tunnel can hand shake."

        /**
         * Why this export omits the key, said where the export is offered —
         * and why it is not the "Export .conf" button above.
         */
        private const val EXPORT_TEXT =
            "Four ready-made tunnel profiles for the fleet — the two axes that " +
            "actually change on a phone: which address family the wifi gives you, " +
            "and how much of your traffic goes inside the tunnel. Each file is ONE " +
            "config carrying BOTH meshes as two peers, because Android runs one " +
            "tunnel at a time.\n\n" +
            "YOUR PRIVATE KEY IS NOT INCLUDED, which is what makes this different " +
            "from \"Export .conf\" above: that one serialises the tunnel you already " +
            "have, key and all, to move it somewhere. These are templates. Each file " +
            "has an empty PrivateKey line with a note, and the WireGuard app will " +
            "refuse the import until you fill it in — expected, not a broken export."

        /**
         * The limit of what this screen can observe, stated on the screen that
         * observes it — a status readout that will not say "I cannot tell" is
         * a status readout that lies exactly when it matters.
         */
        private const val STATUS_TEXT =
            "This reads the tunnel THIS APP owns, through the WireGuard engine app. " +
            "It cannot see a tunnel run by the official WireGuard app, and it has no " +
            "way to ask the operating system directly — so peer rows are what this " +
            "device is configured to dial, and only handshake and byte counts are live."
    }
}
