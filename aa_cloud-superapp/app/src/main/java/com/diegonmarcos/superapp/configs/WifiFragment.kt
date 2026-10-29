package com.diegonmarcos.superapp.configs

import android.os.Bundle
import android.util.Base64
import androidx.compose.runtime.Composable
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.ShellActivity
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.wifi.WifiHostConfig
import com.diegonmarcos.superapp.wifi.WifiHub
import com.diegonmarcos.superapp.wifi.WifiPanel
import com.diegonmarcos.superapp.wifi.WifiTab

/**
 * Configs ▸ Setup ▸ Network ▸ WiFi (#1263): one tab of the page, drawn by libs:wifi's [WifiPanel] over the process-wide [WifiHub]
 * controller, so scans, the throttle window and running tools survive a tab switch. The page declares `tabs` in build.json
 * (wifi-scan, -channels, -link, -tools, -log), so the strip is the shared one; this fragment is a tab's body. The host config is
 * build.json `wifi_analyzer` (BuildConfig.WIFI_ANALYZER_B64). The panel is a LazyColumn under KitComposeFragment, so its scrolling
 * reaches the shell's scroll sink, and its trailing clearance keeps the last card above the bottom bar.
 */
class WifiFragment : KitComposeFragment() {

    companion object {
        private const val ARG_TAB = "tab"

        fun newInstance(tab: WifiTab) = WifiFragment().apply { arguments = Bundle().apply { putString(ARG_TAB, tab.id) } }

        /** The Configs page id of a tab: `wifi-<tab id>`. */
        fun pageId(tab: WifiTab) = "wifi-${tab.id}"

        fun hostConfig() = WifiHostConfig(
            runCatching { String(Base64.decode(BuildConfig.WIFI_ANALYZER_B64, Base64.DEFAULT)) }.getOrDefault("{}"), "Cloud SuperApp",
        )
    }

    private val tab: WifiTab get() = WifiTab.of(arguments?.getString(ARG_TAB))

    override fun palette() = LauncherPalette.kit(requireContext())

    @Composable
    override fun Content() {
        val controller = WifiHub.controller(requireContext().applicationContext, hostConfig())
        WifiPanel(controller, tab) { next -> (activity as? ShellActivity)?.dispatchTarget("page:config/" + pageId(next)) }
    }
}
