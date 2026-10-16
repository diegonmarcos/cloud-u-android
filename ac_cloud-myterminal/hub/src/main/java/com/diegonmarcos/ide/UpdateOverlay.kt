package com.diegonmarcos.ide

import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import androidx.activity.ComponentDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.DialogFragment
import com.diegonmarcos.ide.update.UpdateProgress
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitPalette

/**
 * Fullscreen update overlay — the cloud-myterminal equivalent of aa_cloud-superapp's
 * UpdateOverlayFragment. Dark scrim + headline + horizontal progress bar +
 * detail line, driven live by UpdateProgress state transitions. Terminal states
 * (UpToDate / Done / Failed) show a Dismiss button and auto-close.
 *
 *   CheckingManifest → indeterminate + "Checking for updates…"
 *   Downloading      → percent bar + "Downloading 42%" + MiB
 *   Installing       → indeterminate + "Installing…"
 *   UpToDate         → "Up to date" + auto-dismiss
 *   Done             → "Done"
 *   Failed           → message + Dismiss
 *
 * 2026-10-16 owner decision: all apps must be Compose. The overlay is a ComposeView under
 * CloudKitTheme in a ComponentDialog (which gives it its view-tree owners); the same
 * UpdateProgress contract drives it.
 */
class UpdateOverlay : DialogFragment() {

    private var title by mutableStateOf("")
    private var detail by mutableStateOf("")

    /** null = indeterminate bar; otherwise 0..100. */
    private var percent by mutableStateOf<Int?>(null)
    private var dismissShown by mutableStateOf(false)
    private val main = Handler(Looper.getMainLooper())

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val dialog = ComponentDialog(ctx, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.window?.setBackgroundDrawable(ColorDrawable(ctx.getColor(R.color.ide_scrim)))
        dialog.setContentView(ComposeView(ctx).apply {
            setContent {
                val palette = KitPalette(
                    surface = colorResource(R.color.ide_card),
                    surfaceSelected = colorResource(R.color.ide_accent),
                    textPrimary = colorResource(R.color.ide_text),
                    textSecondary = colorResource(R.color.ide_text_dim),
                    accent = colorResource(R.color.ide_accent),
                    hairline = colorResource(R.color.ide_card_stroke),
                    tileInk = colorResource(R.color.ide_ink),
                )
                CloudKitTheme(palette) { Body() }
            }
        })
        dialog.setCanceledOnTouchOutside(false)
        return dialog
    }

    @Composable
    private fun Body() {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start,
        ) {
            Text(title, color = colorResource(R.color.ide_lavender), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            val pct = percent
            val barMod = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp).height(8.dp)
            if (pct == null) LinearProgressIndicator(modifier = barMod)
            else LinearProgressIndicator(progress = { pct / 100f }, modifier = barMod)
            Text(detail, color = colorResource(R.color.ide_text_dim), fontSize = 14.sp)
            if (dismissShown) Button(onClick = { dismissAllowingStateLoss() }) { Text("Dismiss") }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(MATCH, MATCH)
        UpdateProgress.setListener { state -> main.post { applyState(state) } }
    }

    override fun onStop() {
        super.onStop()
        UpdateProgress.setListener(null)
    }

    private fun applyState(state: UpdateProgress.State) {
        when (state) {
            is UpdateProgress.State.Idle -> {}
            is UpdateProgress.State.CheckingManifest -> {
                title = "Checking for updates…"; detail = "Reading GHCR manifest"
                percent = null; dismissShown = false
            }
            is UpdateProgress.State.Downloading -> {
                title = "Downloading ${state.percent}%"
                detail = "${state.bytes.toMib()} / ${state.total.toMib()} MiB"
                percent = if (state.total <= 0) null else state.percent
            }
            is UpdateProgress.State.Installing -> {
                title = "Installing…"; detail = "Handing the APK to the system installer"
                percent = null
            }
            is UpdateProgress.State.UpToDate -> {
                title = "Up to date"; detail = "Latest: ${state.tag}"
                percent = 100
                autoClose()
            }
            is UpdateProgress.State.Done -> {
                title = "Done"; detail = "Update complete"
                percent = 100; autoClose()
            }
            is UpdateProgress.State.Failed -> {
                title = "Update failed"; detail = state.message
                percent = 0
                dismissShown = true
            }
        }
    }

    private fun autoClose() {
        dismissShown = true
        main.postDelayed({ if (isAdded) dismissAllowingStateLoss() }, 1800)
    }

    private fun Long.toMib(): String = "%.2f".format(this / (1024.0 * 1024.0))

    companion object {
        const val TAG = "update_overlay"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        fun newInstance() = UpdateOverlay()
    }
}
