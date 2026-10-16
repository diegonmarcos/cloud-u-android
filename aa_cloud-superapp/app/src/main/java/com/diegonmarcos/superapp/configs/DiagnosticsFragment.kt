package com.diegonmarcos.superapp.configs

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.diegonmarcos.superapp.settings.ConfigButton
import com.diegonmarcos.superapp.settings.ConfigCaption
import com.diegonmarcos.superapp.settings.ConfigHeader
import com.diegonmarcos.superapp.settings.ConfigReadout
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import kotlinx.coroutines.delay

/** Configs ▸ Observability ▸ Diagnostics (build.json config page `diagnostics`): [Diagnostics.text], live, plus Share. */
class DiagnosticsFragment : KitComposeFragment() {
    override fun palette() = LauncherPalette.kit(requireContext())

    @Composable
    override fun Content() {
        val ctx = LocalContext.current
        var report by remember { mutableStateOf(Diagnostics.text(Diagnostics.collect(ctx))) }
        val lifecycle = viewLifecycleOwner.lifecycle
        LaunchedEffect(Unit) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) { report = Diagnostics.text(Diagnostics.collect(ctx)); delay(1000) }
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
            ConfigHeader("Diagnostics")
            ConfigCaption("What this phone really does: screenshot this page, or Share it. The same data is read remotely by diagnostics/get.")
            ConfigReadout(report)
            ConfigButton("Share diagnostics") {
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "SuperApp diagnostics").putExtra(Intent.EXTRA_TEXT, report)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "Share diagnostics").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    companion object { fun newInstance() = DiagnosticsFragment() }
}
