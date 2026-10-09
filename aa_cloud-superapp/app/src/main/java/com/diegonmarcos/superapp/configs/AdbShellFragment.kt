package com.diegonmarcos.superapp.configs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.adbdebug.AdbShellScreen

/**
 * Configs > Setup > Network > ADB Shell. Only hosts the lib's [AdbShellScreen] (Compose): the same page
 * Cloud Store, Cloud Account and every status chip open, so there is no second copy of the UI. The page's
 * label is `build.json` config page `adb-shell`.
 */
class AdbShellFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        ComposeView(requireContext()).apply { setContent { AdbShellScreen(dark = true) } }

    companion object { fun newInstance() = AdbShellFragment() }
}
