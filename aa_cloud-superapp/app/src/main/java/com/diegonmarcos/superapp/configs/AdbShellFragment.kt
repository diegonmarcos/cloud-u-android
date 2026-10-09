package com.diegonmarcos.superapp.configs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.adbdebug.ShellChannelPanel

/**
 * Configs > Setup > Network > ADB Shell. Only hosts the lib's [ShellChannelPanel] (Compose) - the same
 * mode selector, layer status and actions Cloud Store's Privileged channel setting uses - so there
 * is no second copy of the UI. The page's label is `build.json` config page `adb-shell`.
 */
class AdbShellFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        ComposeView(requireContext()).apply {
            setContent {
                MaterialTheme(darkColorScheme()) {
                    Surface { androidx.compose.foundation.layout.Box(Modifier.verticalScroll(rememberScrollState())) { ShellChannelPanel() } }
                }
            }
        }

    companion object { fun newInstance() = AdbShellFragment() }
}
