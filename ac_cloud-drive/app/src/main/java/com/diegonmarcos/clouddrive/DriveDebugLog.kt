package com.diegonmarcos.clouddrive

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The app's own on-device debug log, readable OFF the phone with no adb — the same
 * self-written log the superapp keeps under Download/superapp-logs/ (CrashLogger /
 * Trace). A multi-day Sync ▸ Git failure was invisible until someone read the
 * filesystem by hand, because this app logged only to logcat; every decision point
 * of the git flow (clone start/result, sync outcomes, seed outcomes) now also lands
 * in Download/<BuildConfig.DEBUG_LOG_DIR>/drive-debug.log, which any file manager or
 * Termux (~/storage/Download) can read.
 *
 * TIMESTAMPS ARE UTC WITH AN EXPLICIT Z. The fleet's device logs have been misread
 * as stale because a local-time log (GMT+2/+3) was compared against a UTC shell; a
 * timestamp that names its own offset cannot be misread that way.
 *
 * ROTATION: the file rolls to drive-debug.log.1 past [MAX_BYTES], same as the
 * superapp's Trace (256KB, previous generation kept).
 *
 * NEVER A SECRET: callers hand this object composed SENTENCES — a URL's host, a
 * destination folder, a header NAME, a status code, an engine summary. No token,
 * cookie or Authorization VALUE may reach a call site; test/test-drive-debug-log.sh
 * pins that on every call site, mutation-proven.
 *
 * Every write is wrapped: a logging failure is logcat-only, never a crash.
 */
object DriveDebugLog {
    private const val TAG = "DriveDebugLog"
    const val FILE_NAME = "drive-debug.log"
    const val MAX_BYTES = 256 * 1024L

    fun i(ctx: Context, tag: String, msg: String) { Log.i(tag, msg); write(ctx, "I", tag, msg, null) }
    fun w(ctx: Context, tag: String, msg: String, t: Throwable? = null) { Log.w(tag, msg, t); write(ctx, "W", tag, msg, t) }
    fun e(ctx: Context, tag: String, msg: String, t: Throwable? = null) { Log.e(tag, msg, t); write(ctx, "E", tag, msg, t) }

    /** The one line shape, pure so the JVM can hold it: UTC, explicit Z offset. */
    fun line(epochMillis: Long, level: String, tag: String, msg: String): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return "${fmt.format(Date(epochMillis))} $level/$tag: $msg"
    }

    @Synchronized
    private fun write(ctx: Context, level: String, tag: String, msg: String, t: Throwable?) {
        try {
            var payload = line(System.currentTimeMillis(), level, tag, msg) + "\n"
            if (t != null) {
                val sw = StringWriter()
                PrintWriter(sw).use { pw -> t.printStackTrace(pw) }
                payload += sw.toString()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) appendViaMediaStore(ctx, payload)
            else appendLegacy(payload)
        } catch (ignored: Throwable) { /* logging failures are non-fatal */ }
    }

    private val relativePath: String
        get() = "${Environment.DIRECTORY_DOWNLOADS}/${BuildConfig.DEBUG_LOG_DIR}"

    /** Android 10+: append to our own MediaStore row in Download/<dir>/, rolling past the cap. */
    private fun appendViaMediaStore(ctx: Context, payload: String) {
        val cr = ctx.contentResolver
        var row = findRow(ctx, FILE_NAME)
        if (row != null && row.second > MAX_BYTES) {
            // Roll: the previous generation becomes .1 (any older .1 is dropped first).
            findRow(ctx, "$FILE_NAME.1")?.let { cr.delete(it.first, null, null) }
            runCatching {
                cr.update(row.first, ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, "$FILE_NAME.1") }, null, null)
            }
            row = null
        }
        val uri = row?.first ?: cr.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            },
        ) ?: return
        cr.openOutputStream(uri, "wa")?.use { it.write(payload.toByteArray()) }
    }

    /** Our row for [name] under Download/<dir>/, with its size — or null before the first write. */
    private fun findRow(ctx: Context, name: String): Pair<Uri, Long>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.SIZE)
        ctx.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?",
            arrayOf(name, "$relativePath/"),
            null,
        )?.use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(0)
                return Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString()) to c.getLong(1)
            }
        }
        return null
    }

    /** Android 9 and below: a plain file in the public Download/<dir>/, same rotation. */
    private fun appendLegacy(payload: String) {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), BuildConfig.DEBUG_LOG_DIR)
        dir.mkdirs()
        val f = File(dir, FILE_NAME)
        if (f.length() > MAX_BYTES) {
            val backup = File(dir, "$FILE_NAME.1")
            if (backup.exists()) backup.delete()
            f.renameTo(backup)
        }
        f.appendText(payload)
    }
}
