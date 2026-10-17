package com.diegonmarcos.superapp.browser.download

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/**
 * Where a finished download goes. It downloads into app-specific storage (direct paths, no
 * permission); on completion it is published to the phone's Download/<dir> through MediaStore
 * (Android 10+) or the public directory (8-9), and the private copy is removed. A torrent that
 * still seeds keeps its private files; its published copy is made when seeding ends.
 *
 * [DlSharedStore] is the hook for cloud-shared-store: when a store is installed and the
 * "save to cloud-shared-store" option is on, the file goes there instead.
 */
object DlPublisher {
    /** @return the published content:// or file:// address, or null when the file stays private. */
    fun publish(ctx: Context, j: DlJob): String? {
        val src = File(j.file)
        if (!src.exists()) return null
        if (j.saveToSharedStore) DlSharedStore.current(ctx)?.let { s -> s.save(ctx, src, j.fileName, j.mime, j.dir)?.let { u -> cleanup(src, j); return u } }
        val mime = j.mime.ifBlank { mimeOf(j.fileName) }
        val sub = j.dir.trim('/').let { if (it.isEmpty()) Environment.DIRECTORY_DOWNLOADS else "${Environment.DIRECTORY_DOWNLOADS}/$it" }
        val out: String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cr = ctx.contentResolver
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, j.fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, sub)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return null
            try {
                cr.openOutputStream(uri)?.use { o -> src.inputStream().use { it.copyTo(o, 256 * 1024) } } ?: error("no stream")
                cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                runCatching { cr.delete(uri, null, null) }
                return null
            }
            uri.toString()
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStorageDirectory(), sub).apply { mkdirs() }
            val dst = File(dir, DlFormat.unique(j.fileName) { File(dir, it).exists() })
            runCatching { src.copyTo(dst) }.getOrElse { return null }
            Uri.fromFile(dst).toString()
        }
        cleanup(src, j)
        return out
    }

    private fun cleanup(src: File, j: DlJob) {
        src.delete()
        src.parentFile?.takeIf { it.name == j.id }?.delete()
    }

    fun delete(ctx: Context, uri: String) {
        runCatching {
            val u = Uri.parse(uri)
            if (u.scheme == "file") File(u.path ?: return).delete() else ctx.contentResolver.delete(u, null, null)
        }
    }

    fun mimeOf(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

    /** The address another app can read: the published one, else the private file through [DlFileProvider]. */
    fun shareUri(ctx: Context, j: DlJob): Uri? {
        j.publishedUri?.let { p -> Uri.parse(p).let { if (it.scheme != "file") return it } }
        val f = j.publishedUri?.let { File(Uri.parse(it).path.orEmpty()) }?.takeIf { it.exists() } ?: File(j.file).takeIf { it.isFile }
            ?: return null
        return runCatching { FileProvider.getUriForFile(ctx, DlFileProvider.authority(ctx), f) }.getOrNull()
    }

    fun openIntent(ctx: Context, j: DlJob): Intent? {
        val u = shareUri(ctx, j) ?: return null
        return Intent(Intent.ACTION_VIEW).setDataAndType(u, j.mime.ifBlank { mimeOf(j.fileName) })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun shareIntent(ctx: Context, j: DlJob): Intent? {
        val u = shareUri(ctx, j) ?: return null
        return Intent.createChooser(Intent(Intent.ACTION_SEND).setType(j.mime.ifBlank { mimeOf(j.fileName) })
            .putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/** The private download folder, readable by the app the user opens a file with. Declared by the host app. */
class DlFileProvider : FileProvider() {
    companion object { fun authority(ctx: Context) = ctx.packageName + ".dlfiles" }
}

/**
 * HOOK for cloud-shared-store (built separately). When an implementation is registered here and
 * the "Save to cloud-shared-store" setting is on, a finished download is handed to it instead of
 * MediaStore. Until the store exists, [current] is null and the option is shown as unavailable.
 */
interface DlSharedStore {
    /** @return the stored file's address, or null to fall back to Download/. */
    fun save(ctx: Context, file: File, name: String, mime: String, dir: String): String?

    companion object {
        @Volatile var registered: DlSharedStore? = null
        fun current(ctx: Context): DlSharedStore? = registered
        fun available(ctx: Context): Boolean = current(ctx) != null
    }
}
