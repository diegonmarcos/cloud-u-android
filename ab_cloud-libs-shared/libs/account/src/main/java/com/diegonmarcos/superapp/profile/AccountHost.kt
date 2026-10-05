package com.diegonmarcos.superapp.profile

import android.app.Activity
import android.content.Context
import android.view.View
import androidx.compose.ui.graphics.toArgb
import com.diegonmarcos.cloudlib.auth.VaultConnect
import com.diegonmarcos.cloudlib.auth.VaultFile
import com.diegonmarcos.superapp.uikit.KitPalette
import com.google.android.material.tabs.TabLayout

/**
 * #867 What Account needs from whatever app is hosting it.
 *
 * Account is a library now (libs:account), hosted by cloud-superapp and by
 * cloud-account, so it cannot reach the launcher's own classes: the theme
 * palette, the tab-pill chrome, the icon table, the tile router, haptics and
 * the mesh tunnel's prefs all live in the SuperApp's module. The host supplies
 * them once, in Application.onCreate. Every default degrades to a plain but
 * working screen, never a crash, so a host that sets nothing still shows Account.
 *
 * Same shape as libs:appstore's AppStoreHost: plain fields, no interface to implement.
 */
object AccountHost {

    /** The host's theme (SuperApp: LauncherPalette.kit). Default: a neutral dark set. */
    @Volatile var palette: (Context) -> KitPalette = { DEFAULT_PALETTE }

    /** Styles a TabLayout like the host's own strips (SuperApp: AppTabsStyle.apply + equalise). */
    @Volatile var styleTabs: (TabLayout) -> Unit = {}

    /** Drawable for a declared icon name, 0 when the host has none (SuperApp: Sections.iconResFor). */
    @Volatile var iconFor: (Context, String) -> Int = { _, _ -> 0 }

    /**
     * Opens a launcher route (`page:…`, `section:…`, `extapp:…`) that Account links
     * to. False when the host cannot route it, and Account then says so rather than
     * doing nothing. SuperApp: its TileClickListener.
     */
    @Volatile var route: (Activity, String) -> Boolean = { _, _ -> false }

    /** A short haptic tick on a tap (SuperApp: Haptics.tap). */
    @Volatile var tap: (View) -> Unit = {}

    /**
     * THE classifier for a picked or pasted config (SuperApp: ImportConfigsFragment.classify,
     * which reads the import schema it bakes). The default knows the declared sign-in schema
     * versions and no paste-shape blob sections.
     */
    @Volatile var classify: (String) -> VaultFile.Verdict =
        { text -> VaultFile.classify(text, VaultConnect.knownSchemaVersions, emptySet()) }

    /**
     * Why a file is NOT the decrypted vault export, in the host's own sentences; null for the
     * export itself (SuperApp: ImportConfigsFragment.refusal). The default is the verdict's name.
     */
    @Volatile var refusal: (Context, VaultFile.Verdict) -> String? =
        { _, v -> if (v is VaultFile.Verdict.Bundle) null else "Not a vault export: ${v::class.java.simpleName}" }

    /**
     * The mesh tunnel the Fleet tab reads and configures. It lives in the SuperApp
     * (libs:net and its WireGuard prefs); a host without one leaves this null and
     * the cockpit reports the tunnel as not available in this app.
     */
    @Volatile var mesh: Mesh? = null

    /** The mesh tunnel as Account sees it. */
    interface Mesh {
        /** This device's address on the mesh, "" when none is configured. */
        fun interfaceAddress(ctx: Context): String
        /** What the tunnel is right now, for the cockpit's comparison column. */
        fun state(ctx: Context): VaultCockpit.TunnelState
        /** Applies the mesh profile [conf] named [name]; returns the one-line report. */
        fun apply(ctx: Context, name: String, conf: String): String
    }

    /**
     * #867 Whether an empty imported-configs blob reads through to the Cloud Account app. SuperApp
     * turns it on; Cloud Account is the owner and keeps the default (off).
     */
    @Volatile var readThrough: Boolean = false

    val DEFAULT_PALETTE: KitPalette = KitPalette.fromArgb(
        surface = 0xFF15161A.toInt(), surfaceSelected = 0xFF23252B.toInt(),
        textPrimary = 0xFFE6E6E6.toInt(), textSecondary = 0xFF9AA0A6.toInt(),
        accent = 0xFF7AA2F7.toInt(), hairline = 0xFF2A2C33.toInt(), tileInk = 0xFF000000.toInt(),
    )
}

/** The host palette as ARGB ints, for Account's View-based cockpits. */
class AccountColors private constructor(p: KitPalette) {
    val surface = p.surface.toArgb()
    val surfaceSelected = p.surfaceSelected.toArgb()
    val textPrimary = p.textPrimary.toArgb()
    val textSecondary = p.textSecondary.toArgb()
    val accent = p.accent.toArgb()
    val hairline = p.hairline.toArgb()
    val tileInk = p.tileInk.toArgb()

    companion object {
        fun of(ctx: Context) = AccountColors(AccountHost.palette(ctx))
    }
}
