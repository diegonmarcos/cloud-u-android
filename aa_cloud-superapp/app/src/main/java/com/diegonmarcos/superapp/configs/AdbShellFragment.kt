package com.diegonmarcos.superapp.configs

import androidx.compose.runtime.Composable
import com.diegonmarcos.superapp.adbdebug.AdbShellScreen
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.uikit.KitComposeFragment

/**
 * Configs > Setup > Network > ADB Shell. Only hosts the lib's [AdbShellScreen] (Compose): the same page
 * Cloud Store, Cloud Account and every status chip open, so there is no second copy of the UI. The page's
 * label is `build.json` config page `adb-shell`. A KitComposeFragment, so it sits under the dense CloudKitTheme
 * like every other SuperApp page (#773).
 */
class AdbShellFragment : KitComposeFragment() {
    override fun palette() = LauncherPalette.kit(requireContext())

    @Composable
    override fun Content() { AdbShellScreen(dark = true) }

    companion object { fun newInstance() = AdbShellFragment() }
}
