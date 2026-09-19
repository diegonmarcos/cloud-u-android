package app.affine.pro.plugin

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import java.io.File
import java.io.FileInputStream

/**
 * Emulated-storage file opener (ticket #469 — the ONE feature this clone adds
 * over upstream).
 *
 * Scoped storage mechanism, stated plainly: since Android 11 the OS does not
 * let an app read /storage/emulated/0/... by raw path without a grant, and a
 * file-manager-style path opener needs exactly that granter. This plugin uses
 * MANAGE_EXTERNAL_STORAGE ("All files access", `Environment
 * .isExternalStorageManager()`), the mechanism file managers use, and guides
 * the user to Settings when it is missing. On Android 10 and older it falls
 * back to the READ_EXTERNAL_STORAGE runtime permission. No SAF picker is
 * involved because Diego wants to type a path into his vault, not navigate a
 * system dialog; every failure mode returns its OWN visible message.
 */
@CapacitorPlugin(
    name = "ExternalFile",
    permissions = [Permission(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))]
)
class ExternalFilePlugin : Plugin() {

    companion object {
        private const val MAX_BYTES = 10L * 1024 * 1024 // 10 MiB import cap (markdown vaults)
        private val SUPPORTED = setOf(
            "md", "markdown", "txt", "text", "json", "yaml", "yml", "html", "csv"
        )
    }

    private fun hasExternalStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }

    @PluginMethod
    fun readFile(call: PluginCall) {
        val path = call.getString("path")?.trim().orEmpty()

        if (path.isEmpty()) {
            call.reject("No path was given.", "NO_PATH")
            return
        }
        // Only the owner's visible storage is in scope; anything else is a
        // prompt the app cannot explain, so it fails with its own message.
        if (!path.startsWith("/storage/emulated/0/")) {
            call.reject(
                "Path must start with /storage/emulated/0/ (your phone's visible storage).",
                "OUTSIDE_EMULATED_STORAGE"
            )
            return
        }
        if (!hasExternalStorageAccess()) {
            call.reject(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    "All-files access is not granted. Open Settings → Apps → " +
                        "Cloud AFFiNE → All files access → Allow, then try again."
                } else {
                    "Storage permission is not granted. Open Settings → Apps → " +
                        "Cloud AFFiNE → Permissions → Files and media → Allow, then try again."
                },
                "PERMISSION_DENIED"
            )
            return
        }

        val file = File(path)
        if (!file.exists()) {
            call.reject("File not found at $path", "NOT_FOUND")
            return
        }
        if (file.isDirectory) {
            call.reject("This is a folder, not a file: $path", "IS_DIRECTORY")
            return
        }
        val ext = file.extension.lowercase()
        if (ext !in SUPPORTED) {
            call.reject(
                "Unsupported file type \".$ext\". Supported: " +
                    SUPPORTED.sorted().joinToString(", "),
                "UNSUPPORTED_EXTENSION"
            )
            return
        }
        if (file.length() > MAX_BYTES) {
            call.reject("File is larger than the 10 MiB import limit.", "TOO_LARGE")
            return
        }

        try {
            val bytes = FileInputStream(file).use { it.readBytes() }
            val result = JSObject()
                .put("content", bytes.toString(Charsets.UTF_8))
                .put("name", file.name)
                .put("size", bytes.size)
                .put("path", path)
            call.resolve(result)
        } catch (e: Exception) {
            call.reject(
                "Cannot read $path (${e.message ?: "unknown error"})",
                "READ_ERROR"
            )
        }
    }

    @PluginMethod
    fun openSettings(call: PluginCall) {
        val ctx = activity ?: context
        val intent: Intent =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${ctx.packageName}")
                )
            } else {
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            }
        try {
            ctx.startActivity(intent)
            call.resolve()
        } catch (e: Exception) {
            call.reject(
                "Cannot open the settings screen (${e.message ?: "unknown error"}). " +
                    "Grant the storage permission manually in Android Settings.",
                "SETTINGS_ERROR"
            )
        }
    }
}