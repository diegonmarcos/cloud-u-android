// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Persistent debug log takeout for the keyboard's own in-memory [Log] buffer. Writes the
 * current log lines to a single, predictably-named SHARED file at the root of Downloads:
 *   Download/cloud-keyboard-log.log
 * so it can be pulled from Termux (~/storage/Download) or any file manager without
 * adb/logcat/root, and so it survives an IME process restart (unlike logcat, which is
 * lost on process death). Always overwritten -> exactly one current file.
 *
 * Rewrites the one copy it owns in place (MediaStore, legacy fallback, private-storage
 * fallback) and dumps the running [Log] buffer instead of a crash stack trace; triggered
 * manually or on IME teardown.
 */
object LogTakeout {
    private const val TAG = "LogTakeout"

    fun dump(context: Context, fileName: String = "cloud-keyboard-log.log") {
        runCatching {
            val ctx = context.applicationContext
            val body = buildString {
                append("=== Cloud-Keyboard log ===\n")
                append("when:    ").append(java.util.Date()).append('\n')
                append("device:  ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
                append("android: ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n\n")
                append(Log.getLog().joinToString("\n"))
            }
            writeLatest(ctx, fileName, body)
        }
    }

    /**
     * Our own copies of [fileName] in Downloads: the name itself, or the "base (N).ext" that
     * MediaStore renames an insert to when the name is taken. Matching only the exact name
     * was the #776 bug: once a copy we could not delete held the plain name (a previous
     * install, the SuperApp's embedded keyboard), every dump deleted nothing and inserted
     * "(1)", "(2)"... until MediaStore refused at 32 with "Failed to build unique file" and
     * every later dump fell back to a private file the owner never sees.
     */
    internal fun isOwnCopy(name: String, fileName: String): Boolean {
        val base = fileName.substringBeforeLast('.')
        val ext = if ('.' in fileName) "." + fileName.substringAfterLast('.') else ""
        return name == fileName || Regex("${Regex.escape(base)} \\(\\d+\\)${Regex.escape(ext)}").matches(name)
    }

    private fun writeLatest(ctx: Context, fileName: String, body: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cr = ctx.contentResolver
                val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                // Our rows only, the ones we may rewrite or delete: scoped storage hides the rest
                // anyway unless storage-read is granted, and OWNER_PACKAGE_NAME covers that case.
                // The plain name first, then the newest.
                val own = ArrayList<Triple<Long, String, Long>>()
                cr.query(
                    collection,
                    arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.DATE_MODIFIED),
                    "${MediaStore.Downloads.DISPLAY_NAME} LIKE ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
                    arrayOf(fileName.substringBeforeLast('.') + "%", ctx.packageName),
                    null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        val name = c.getString(1)
                        if (name != null && isOwnCopy(name, fileName)) own.add(Triple(c.getLong(0), name, c.getLong(2)))
                    }
                }
                own.sortWith(compareByDescending<Triple<Long, String, Long>> { it.second == fileName }.thenByDescending { it.third })
                val keep = own.firstOrNull()
                // Every other copy is ours too: the duplicates the old insert-every-time left.
                own.drop(1).forEach { runCatching { cr.delete(ContentUris.withAppendedId(collection, it.first), null, null) } }
                val uri = if (keep != null) ContentUris.withAppendedId(collection, keep.first)
                else cr.insert(collection, ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }) ?: return
                // "wt": rewrite in place. Plain "w" does not truncate, so a shorter log would
                // keep the tail of the previous one.
                cr.openOutputStream(uri, "wt")?.use { it.write(body.toByteArray()) }
                Log.i(TAG, "wrote Download/${keep?.second ?: fileName} via MediaStore " +
                    "(${if (keep == null) "created" else "updated"}, ${(own.size - 1).coerceAtLeast(0)} own duplicate(s) removed)")
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                File(dir, fileName).writeText(body)
                Log.i(TAG, "wrote legacy Download/$fileName")
            }
        } catch (t: Throwable) {
            // Last-ditch fallback: app-private external dir (visible in file manager). Logged
            // at ERROR: the owner reads Download/, so landing here means they see no new log.
            runCatching { File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, fileName).writeText(body) }
            Log.e(TAG, "MediaStore write of Download/$fileName failed; wrote the private fallback only", t)
        }
    }
}
