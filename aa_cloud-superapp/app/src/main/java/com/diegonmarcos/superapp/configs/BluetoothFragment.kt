package com.diegonmarcos.superapp.configs

import android.os.Bundle
import androidx.compose.runtime.Composable
import com.diegonmarcos.superapp.ShellActivity
import com.diegonmarcos.superapp.bluetooth.BluetoothHub
import com.diegonmarcos.superapp.bluetooth.BluetoothPanel
import com.diegonmarcos.superapp.bluetooth.BtTab
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.uikit.KitComposeFragment

/**
 * Configs ▸ Fleet ▸ Bluetooth (#1221): one tab of the page, drawn by libs:bluetooth's [BluetoothPanel] over the process-wide
 * [BluetoothHub] controller, so the connection and the log survive a tab switch. The page declares `tabs` in build.json
 * (bluetooth-find, -connect, -interact, -debug, -dev), so the strip is the shared one; this fragment is a tab's body.
 * The panel is a LazyColumn under KitComposeFragment, so its scrolling reaches the shell's scroll sink.
 */
class BluetoothFragment : KitComposeFragment() {

    companion object {
        private const val ARG_TAB = "tab"

        fun newInstance(tab: BtTab) = BluetoothFragment().apply { arguments = Bundle().apply { putString(ARG_TAB, tab.id) } }

        /** The Configs page id of a tab: `bluetooth-<tab id>`. */
        fun pageId(tab: BtTab) = "bluetooth-${tab.id}"
    }

    private val tab: BtTab get() = BtTab.of(arguments?.getString(ARG_TAB))

    override fun palette() = LauncherPalette.kit(requireContext())

    @Composable
    override fun Content() {
        val controller = BluetoothHub.controller(requireContext().applicationContext)
        BluetoothPanel(controller, tab) { next -> (activity as? ShellActivity)?.dispatchTarget("page:config/" + pageId(next)) }
    }
}
