package com.diegonmarcos.superapp.configs

import androidx.compose.runtime.Composable
import com.diegonmarcos.superapp.adbdebug.AdbShellHostedPage
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.uikit.KitComposeFragment

/**
 * Configs > Setup > Network > ADB Shell. Only hosts the lib's [AdbShellHostedPage]: the SAME composable Cloud Store hosts
 * in its Configs frame (scroll, padding and all), so the two apps draw one page and there is no second copy of any of it.
 * The page's label is `build.json` config page `adb-shell`. A KitComposeFragment, so it sits under the dense CloudKitTheme
 * like every other SuperApp page (#773). Where the page starts is the shell's business (ShellTopInset), not this page's.
 */
class AdbShellFragment : KitComposeFragment() {
    override fun palette() = LauncherPalette.kit(requireContext())

    @Composable
    override fun Content() { AdbShellHostedPage() }

    companion object { fun newInstance() = AdbShellFragment() }
}
