package com.diegonmarcos.ide

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * Dev Control — logcat viewer, copy/export, and a local dev API toggle.
 * Mirrors ConfigsActivity UI style (Ui composeView / Header / AppCard).
 *
 * 2026-10-16 owner decision: all apps must be Compose. The page is a ComposeView under
 * CloudKitTheme; the logcat text, the dev-API switch and its status line are Compose state.
 */
class DevControlActivity : AppCompatActivity() {

    private var logText: String = ""

    /** What the viewer shows: "Loading…" until a capture lands. */
    private var logShown by mutableStateOf("Loading…")
    private var apiEnabled by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        apiEnabled = IdePrefs.devApiEnabled(this)
        if (apiEnabled) DevApiServer.start()

        setContent { Page() }

        // Kick off initial logcat capture
        captureLogcat()
    }

    @Composable
    private fun Page() {
        val p = LocalKitPalette.current
        val scroll = rememberScrollState()
        val logScroll = rememberScrollState()
        // Follow the tail when a capture lands (the old viewer scrolled to the bottom).
        LaunchedEffect(logShown) { logScroll.scrollTo(logScroll.maxValue) }
        Column(Modifier.fillMaxSize()) {
            // ── Header ────────────────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Dev Control", color = p.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = { finish() }) { Text("✕ Close", color = p.textSecondary, fontSize = 13.sp) }
            }
            // ── Body (scrollable) ─────────────────────────────────────────
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(start = 20.dp, top = 4.dp, end = 20.dp, bottom = 24.dp)) {
                Text("Logcat  (last 2000 lines)", color = p.textSecondary, fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 6.dp))
                // Logcat viewer — monospace, selectable, dark bg
                SelectionContainer {
                    Text(
                        logShown, color = p.textPrimary, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth().height(300.dp).background(p.surface)
                            .verticalScroll(logScroll).padding(8.dp),
                    )
                }
                // ── Action buttons row ────────────────────────────────────
                Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { captureLogcat() }) { Text("Refresh", fontSize = 13.sp) }
                    Button(onClick = { copyLogcat() }) { Text("Copy", fontSize = 13.sp) }
                    Button(onClick = { exportLogcat() }) { Text("Export", fontSize = 13.sp) }
                }
                // ── Dev API row ───────────────────────────────────────────
                Text("Dev API", color = p.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = apiEnabled, onCheckedChange = { setDevApi(it) })
                    Text("Enable local dev API (127.0.0.1:${DevApiServer.PORT})", color = p.textPrimary, fontSize = 13.sp)
                }
                Text(
                    if (apiEnabled) "http://127.0.0.1:${DevApiServer.PORT}" else "Off",
                    color = colorResource(R.color.ide_accent_soft), fontSize = 12.sp,
                )
            }
        }
    }

    private fun setDevApi(checked: Boolean) {
        apiEnabled = checked
        IdePrefs.setDevApiEnabled(this, checked)
        if (checked) DevApiServer.start() else DevApiServer.stop()
    }

    private fun captureLogcat() {
        logShown = "Loading…"
        Thread({
            val text = runCatching {
                Runtime.getRuntime()
                    .exec(arrayOf("logcat", "-d", "-v", "threadtime", "-t", "2000"))
                    .inputStream.bufferedReader().readText()
            }.getOrElse { "logcat read failed: $it\n" }
            runOnUiThread {
                logText = text
                logShown = text
            }
        }).also { it.isDaemon = true }.start()
    }

    private fun copyLogcat() {
        if (logText.isEmpty()) { Toast.makeText(this, "Nothing to copy yet", Toast.LENGTH_SHORT).show(); return }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("cloud-myterminal-logcat", logText))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun exportLogcat() {
        if (logText.isEmpty()) { Toast.makeText(this, "Nothing to export yet", Toast.LENGTH_SHORT).show(); return }
        val filename = "cloud-myterminal-logcat-${System.currentTimeMillis()}.txt"
        val saved = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return@runCatching null
                resolver.openOutputStream(uri)?.use { it.write(logText.toByteArray()) }
                values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                filename
            } else {
                @Suppress("DEPRECATION")
                val dir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS)
                java.io.File(dir, filename).apply { writeText(logText) }; filename
            }
        }.getOrNull()
        if (saved != null) Toast.makeText(this, "Saved: $saved", Toast.LENGTH_LONG).show()
        else Toast.makeText(this, "Export failed", Toast.LENGTH_SHORT).show()
    }

}
