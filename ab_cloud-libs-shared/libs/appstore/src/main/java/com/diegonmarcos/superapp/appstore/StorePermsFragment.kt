package com.diegonmarcos.superapp.appstore

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.updater.Fleet

/**
 * Store > Perms (#896) - the fleet's permissions page, its own bottom-nav destination.
 * It was the last tab of the Cloud page (StoreCloudFragment.renderPerms); it moved here whole,
 * so the Cloud page is only the fleet list and the page that audits trust sits beside Feed.
 */
class StorePermsFragment : Fragment() {

    // Declared in libs:core's manifest at protectionLevel="signature" and merged
    // into every constellation app. Kept as one constant so the UI and any future
    // ContentProvider guard name the same string.
    private val CONSTELLATION_PERM = "com.diegonmarcos.cloud.permission.CONSTELLATION_DATA"

    private val fleet by lazy { Fleet.parse(BuildConfig.CONSTELLATION_FLEET_B64) }
    private val controls by lazy { StoreControls.load(requireContext()) }
    private lateinit var body: LinearLayout
    // Which per-app permission pane is open, keyed by package (0 = Android, 1 = Cloud).
    private val permTab = HashMap<String, Int>()

    private val cUp = 0xFF48BB78.toInt()
    private val cMiss = 0xFF63B3ED.toInt(); private val cBlk = 0xFFF56565.toInt()
    private val cDim = 0x99FFFFFF.toInt()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext()
        body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(ctx, StoreDensity.S12); setPadding(p, p, p, p)
        }
        renderPerms(ctx)
        return ScrollView(ctx).apply { addView(body) }
    }

    // ── Perms tab ────────────────────────────────────────────────────────────
    // The constellation is a trusted environment because every APK is signed
    // with the SAME key. That is what makes signature-level permissions usable
    // between our apps: an app exposes a ContentProvider guarded by a
    // `signature` permission, and only same-key packages can bind/read it.
    //
    // Each app card carries two panes:
    //
    //   Android Perms — the platform's own runtime grants (camera, location,
    //     contacts…). Listed read-only, because only the system UI may change
    //     them; "System settings ↗" hands off to exactly that screen.
    //
    //   Cloud Perms — CONSTELLATION_DATA, declared in libs:core (shared by
    //     reference into every app, so it merges into all their manifests) at
    //     protectionLevel="signature". Android grants it at install to every
    //     APK carrying our key and refuses it to everyone else, so "all apps
    //     talk freely to each other" is the DEFAULT, enforced by the OS.
    //
    // Neither pane renders a toggle, and that is the point: the Android grants
    // aren't ours to flip, and the Cloud grant is already on by construction.
    // A switch here could only misreport state it doesn't control.
    private fun renderPerms(ctx: Context) {
        body.addView(caption(ctx,
            "One signing key across the constellation = signature-level trust. Each app " +
            "opens on two panes: Android Perms (the OS's own runtime grants — read-only " +
            "here, the system screen owns them) and Cloud Perms (our constellation " +
            "permission, granted automatically to every app carrying the Cloud key, so " +
            "they talk freely to each other by default)."))

        val me = ctx.packageName
        for (app in fleet) {
            if (app.pkg == me) continue
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(0xFF1C1C24.toInt())
                val ph = dp(ctx, StoreDensity.S12); val pv = dp(ctx, StoreDensity.S8)
                setPadding(ph, pv, ph, pv)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.setMargins(0, dp(ctx, StoreDensity.S4), 0, dp(ctx, StoreDensity.S4)); layoutParams = lp
            }
            val pkg = Fleet.installedId(ctx, app)
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(ctx).apply {
                text = app.label + (if (app.kind == "lib") "  ·  lib" else "")
                textSize = StoreDensity.T_TITLE; setTextColor(0xFFFFFFFF.toInt()); typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            card.addView(row)
            card.addView(mono(ctx, pkg ?: app.pkg))

            val trust = TextView(ctx).apply {
                textSize = StoreDensity.T_META; setPadding(0, dp(ctx, StoreDensity.S2), 0, dp(ctx, StoreDensity.S2))
            }
            when {
                pkg == null -> { trust.setTextColor(cMiss); trust.text = "◯ not installed" }
                sameSignature(ctx, pkg) -> { trust.setTextColor(cUp); trust.text = "🔑 same key  ·  eligible for signature-level data access" }
                else -> { trust.setTextColor(cBlk); trust.text = "⚠ different signature  ·  NOT eligible — reinstall from our release" }
            }
            card.addView(trust)

            if (pkg != null) {
                // Per-app sub-tabs: these are two genuinely different systems, so
                // they get separate panes instead of one mixed list. Android Perms
                // = the OS's own runtime grants, which only the system UI can
                // change. Cloud Perms = our constellation permission, which needs
                // no control at all because it is granted by signature. Keyed by
                // package so each card remembers which pane was open.
                val sub = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                val tabs = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(ctx, StoreDensity.S6), 0, dp(ctx, StoreDensity.S4))
                }
                val chips = ArrayList<TextView>()
                fun paint() {
                    val sel = permTab[pkg] ?: 0
                    chips.forEachIndexed { i, c ->
                        c.setBackgroundColor(if (i == sel) 0xFF7C3AED.toInt() else 0xFF2A2A33.toInt())
                    }
                    sub.removeAllViews()
                    if (sel == 0) renderAndroidPerms(ctx, sub, pkg) else renderCloudPerms(ctx, sub, pkg)
                }
                listOf("Android Perms", "Cloud Perms").forEachIndexed { i, label ->
                    val c = TextView(ctx).apply {
                        text = label
                        textSize = StoreDensity.T_META; gravity = Gravity.CENTER
                        setTextColor(0xFFFFFFFF.toInt())
                        setPadding(dp(ctx, StoreDensity.S8), dp(ctx, StoreDensity.S6), dp(ctx, StoreDensity.S8), dp(ctx, StoreDensity.S6))
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                            .apply { setMargins(if (i == 0) 0 else dp(ctx, StoreDensity.S4), 0, 0, 0) }
                        setOnClickListener { permTab[pkg] = i; paint() }
                    }
                    chips.add(c); tabs.addView(c)
                }
                card.addView(tabs)
                card.addView(sub)
                paint()
            }
            body.addView(card)
        }
    }

    /** Android's own runtime permissions for [pkg]. Read-only by design: only
     *  the system UI may change these, so we list what the package requests and
     *  whether it currently holds it, then hand off to the system screen. */
    private fun renderAndroidPerms(ctx: Context, into: LinearLayout, pkg: String) {
        val pm = ctx.packageManager
        val requested = runCatching {
            pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS).requestedPermissions?.toList()
        }.getOrNull().orEmpty()
            // Our constellation permission lives in the other pane; here we show
            // the platform's own, which is what the system screen can act on.
            .filter { it.startsWith("android.permission.") }
            .sorted()

        if (requested.isEmpty()) {
            into.addView(caption(ctx, "Requests no Android permissions."))
        } else {
            for (p in requested) {
                val granted = pm.checkPermission(p, pkg) == PackageManager.PERMISSION_GRANTED
                into.addView(TextView(ctx).apply {
                    text = (if (granted) "✓  " else "·  ") + p.removePrefix("android.permission.")
                    textSize = StoreDensity.T_CAPTION
                    setTextColor(if (granted) cUp else cMiss)
                    setPadding(0, dp(ctx, StoreDensity.S1), 0, dp(ctx, StoreDensity.S1))
                })
            }
        }
        into.addView(buttonRow(ctx, btn(ctx, "System settings ↗", 0xFF2A2A33.toInt()) {
            runCatching {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", pkg, null)))
            }.onFailure { Toast.makeText(ctx, "No settings screen", Toast.LENGTH_SHORT).show() }
        }))
    }

    /** The constellation's own permission. There is deliberately no switch here:
     *  CONSTELLATION_DATA is protectionLevel="signature", so Android grants it at
     *  install time to every APK carrying our signing key and refuses it to every
     *  other APK. "All apps talk freely to each other" is therefore the DEFAULT
     *  state, enforced by the OS itself — a toggle could only lie about it.
     *  Declared once in libs:core, which every app now shares by reference, so it
     *  manifest-merges into all of them. */
    private fun renderCloudPerms(ctx: Context, into: LinearLayout, pkg: String) {
        val pm = ctx.packageManager
        val holds = pm.checkPermission(CONSTELLATION_PERM, pkg) == PackageManager.PERMISSION_GRANTED
        val weHold = pm.checkPermission(CONSTELLATION_PERM, ctx.packageName) == PackageManager.PERMISSION_GRANTED

        into.addView(TextView(ctx).apply {
            text = if (holds) "✓  Cloud data access — granted"
                   else "✕  Cloud data access — not granted"
            textSize = StoreDensity.T_BODY
            setTextColor(if (holds) cUp else cBlk)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(ctx, StoreDensity.S2), 0, dp(ctx, StoreDensity.S2))
        })
        into.addView(mono(ctx, CONSTELLATION_PERM))
        into.addView(caption(ctx, when {
            holds && weHold ->
                "Two-way: this app and SuperApp can each read the other's constellation data. " +
                "Granted automatically at install because both carry the Cloud signing key — " +
                "no prompt, and no outside APK can obtain it."
            holds ->
                "This app holds it but SuperApp does not — reinstall SuperApp from our release."
            sameSignature(ctx, pkg) ->
                "Same signing key, but this build predates the constellation permission. " +
                "Update it from the Apps tab; the grant lands on reinstall."
            else ->
                "Signed with a different key, so Android refuses this permission. " +
                "Reinstall from our release to bring it into the constellation."
        }))
    }

    /** True when [pkg] is signed with the same key as us — the whole basis of
     *  `signature`-level permissions inside the constellation. */
    @Suppress("DEPRECATION")
    private fun sameSignature(ctx: Context, pkg: String): Boolean = runCatching {
        ctx.packageManager.checkSignatures(ctx.packageName, pkg) == PackageManager.SIGNATURE_MATCH
    }.getOrDefault(false)

    private fun dp(ctx: Context, v: Int) = StoreDensity.dp(ctx, v)
    private fun caption(ctx: Context, t: String) = TextView(ctx).apply {
        text = t; textSize = StoreDensity.T_META; setTextColor(cDim); setPadding(0, 0, 0, dp(ctx, StoreDensity.S8))
    }
    private fun mono(ctx: Context, t: String) = TextView(ctx).apply {
        text = t; textSize = StoreDensity.T_CAPTION; setTextColor(cDim); typeface = Typeface.MONOSPACE
    }
    private fun btn(ctx: Context, label: String, bg: Int, onClick: () -> Unit): TextView =
        StoreBar.button(ctx, controls.action, label, bg, onClick)
    private fun buttonRow(ctx: Context, vararg views: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; for (v in views) addView(v)
    }
}
