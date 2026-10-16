package com.diegonmarcos.cloudwallet

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.fragment.app.commit
import com.diegonmarcos.cloudwallet.profile.BusinessCard
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitFragmentHost
import com.diegonmarcos.superapp.uikit.KitFragmentHostState
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.bottomnav.FleetChrome
import com.diegonmarcos.superapp.bottomnav.NavDecl
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.bottomnav.PageTabs
import com.diegonmarcos.superapp.updater.Updater
import com.diegonmarcos.superapp.updater.UpdateProgress
import com.diegonmarcos.superapp.wallet.WalletFragment
import com.diegonmarcos.superapp.wallet.WalletHost

/**
 * Single-activity shell for Cloud Wallet. Hosts [WalletFragment] full-screen
 * and wires the self-updater (Updater) so the app can silently update itself
 * from GHCR — same engine the SuperApp uses for each constellation member.
 *
 * 2026-10-16 owner decision: all apps must be Compose. The shell is a Compose screen under
 * CloudKitTheme (libs:ui-kit): the wallet fragment sits in a KitFragmentHost, padded for the system
 * bars as the XML container was, and the business card is a composable drawn over it.
 *
 * Implements [WalletHost] for the one cross-surface callback the wallet lib
 * needs: [onOpenVcard].
 */
class MainActivity : AppCompatActivity(), WalletHost {

    /** The business card is open over the wallet (it was a fragment on the back stack). */
    private var cardOpen by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        // The wallet fragment is re-added by KitFragmentHost once Compose has a container for it.
        super.onCreate(KitFragmentHostState.withoutFragments(savedInstanceState))
        FleetChrome.apply(this)
        setContent {
            val palette = KitPalette(
                surface = colorResource(R.color.wallet_card),
                surfaceSelected = colorResource(R.color.wallet_card),
                textPrimary = colorResource(R.color.wallet_text),
                textSecondary = colorResource(R.color.wallet_text_dim),
                accent = colorResource(R.color.wallet_accent),
                hairline = colorResource(R.color.wallet_hairline),
                tileInk = colorResource(R.color.wallet_bg),
            )
            CloudKitTheme(palette) {
                // systemBarsPadding: the container pads for the bars once, so the island inside it
                // takes zero insets (what fitsSystemWindows on the XML root did).
                Box(Modifier.fillMaxSize().background(colorResource(R.color.wallet_bg)).systemBarsPadding()) {
                    KitFragmentHost(R.id.fragment_container, Modifier.fillMaxSize()) { WalletFragment.newInstance() }
                    if (cardOpen) {
                        BackHandler { cardOpen = false }
                        BusinessCard()
                    }
                }
            }
        }

        // Self-update: start the periodic GHCR check + one-shot 30s after
        // launch so the first update fires promptly (periodic alone defers by
        // a full interval on first install).
        Updater.start(this)

        // Drive the update overlay: listen to UpdateProgress and show / hide
        // the overlay fragment accordingly — same pattern as SuperApp.
        UpdateProgress.setListener { state ->
            runOnUiThread { handleUpdateState(state) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        UpdateProgress.setListener(null)
    }

    override fun onCheckForUpdates() { Updater.checkNow(this) }

    /** #868 build.json::ui as baked into BuildConfig: the wallet's bar and its Events strip. */
    override val nav: NavDecl by lazy {
        NavDecl.fromBuildConfig(BuildConfig.UI_SECTIONS_B64, BuildConfig.UI_BOTTOM_NAV, BuildConfig.UI_DEFAULT_SECTION)
    }

    override fun onOpenVcard() { cardOpen = true }

    /** #868 The Events section's pages as the fleet's page-tab strip, under the content's own top edge. */
    @Composable
    override fun PageStrip(pages: List<NavPage>, selectedId: String?, onSelect: (NavPage) -> Unit) {
        PageTabs(pages = pages, selectedId = selectedId, onSelect = onSelect, underTopChrome = false)
    }

    private fun handleUpdateState(state: UpdateProgress.State) {
        val tag = "update_overlay"
        val frag = supportFragmentManager.findFragmentByTag(tag)
        // An unattended pass, or a manual one the user minimized, draws
        // nothing. Failed states are never suppressed.
        if (UpdateProgress.suppressed(this, state)) {
            frag?.let { supportFragmentManager.commit(allowStateLoss = true) { remove(it) } }
            return
        }
        when (state) {
            is UpdateProgress.State.Idle -> {
                frag?.let { supportFragmentManager.commit { remove(it) } }
            }
            else -> {
                if (frag == null) {
                    supportFragmentManager.commit {
                        add(android.R.id.content,
                            com.diegonmarcos.superapp.updater.UpdateOverlayFragment.newInstance(),
                            tag)
                    }
                } else {
                    (frag as? com.diegonmarcos.superapp.updater.UpdateOverlayFragment)
                        ?.applyState(state)
                }
            }
        }
    }
}
