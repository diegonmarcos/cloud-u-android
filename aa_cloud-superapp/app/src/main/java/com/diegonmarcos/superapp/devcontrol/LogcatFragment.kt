package com.diegonmarcos.superapp.devcontrol

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.settings.ConfigHeader
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.uikit.KitComposeFragment

/**
 * Configs ▸ Observability ▸ Logcat (build.json config page `logcat`): the live viewer, [AboutLogcatViewer]. It was the LOGCAT
 * group at the bottom of About; it is its own tab now, immediately before About. Opening it never execs `logcat` (see the
 * viewer's note): the person taps This App / All Apps / Refresh.
 */
class LogcatFragment : KitComposeFragment() {
    override fun palette() = LauncherPalette.kit(requireContext())

    /** Buffer handed to the SAF writer — the picker is async, so the text has to survive the round trip. */
    private var pendingExport: String = ""

    /** Save-as through the system picker: no FileProvider to declare and no storage permission to request. */
    private val exportLog =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri == null) return@registerForActivityResult
            val ok = runCatching {
                requireContext().contentResolver.openOutputStream(uri)?.use { it.write(pendingExport.toByteArray()) }
            }.isSuccess
            Toast.makeText(requireContext(), if (ok) "Exported ${pendingExport.length} chars" else "Export failed",
                Toast.LENGTH_SHORT).show()
        }

    private fun exportLogFile(text: String, fileName: String) {
        pendingExport = text
        runCatching { exportLog.launch(fileName) }
            .onFailure { Toast.makeText(requireContext(), "No file picker available", Toast.LENGTH_SHORT).show() }
    }

    @Composable
    override fun Content() {
        val ctx = LocalContext.current
        val actions = remember {
            AboutActions(
                copy = { toast, text ->
                    if (text.isNotEmpty()) (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                        ?.setPrimaryClip(ClipData.newPlainText("logcat", text))
                    Toast.makeText(ctx, toast, Toast.LENGTH_SHORT).show()
                },
                toast = { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() },
            )
        }
        CompositionLocalProvider(LocalAboutActions provides actions) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                ConfigHeader("Logcat")
                AboutLogcatViewer(onExport = ::exportLogFile)
            }
        }
    }

    companion object { fun newInstance() = LogcatFragment() }
}
