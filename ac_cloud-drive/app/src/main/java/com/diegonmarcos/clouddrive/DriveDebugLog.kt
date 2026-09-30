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

    /**
     * The ONE MediaStore row this process appends to, resolved once and reused.
     *
     * MEASURED DEFECT (2026-09-30, the owner's phone): every write re-resolved the
     * row by DISPLAY_NAME=drive-debug.log — but MediaStore had RENAMED the row to
     * `drive-debug.log.txt` on insert (a text/plain row whose display name lacks a
     * .txt extension gets one appended), so the exact-name query matched nothing,
     * every line inserted a fresh row, and Download's same-name semantics minted
     * `drive-debug.log (1).txt` … `(12).txt` — THIRTEEN FILES OF ONE LINE EACH.
     * Two fixes, both required: the resolved Uri is CACHED for the process
     * lifetime (one insert, ever), and [findRow] matches the name MediaStore
     * actually stored (exact OR with the .txt it appends), so a restarted process
     * finds yesterday's row instead of minting a duplicate.
     */
    @Volatile
    private var cachedRow: Uri? = null

    /** Android 10+: append to our own MediaStore row in Download/<dir>/, rolling past the cap. */
    private fun appendViaMediaStore(ctx: Context, payload: String) {
        // With All-Files-Access (this app IS a file manager — MANAGE_EXTERNAL_STORAGE
        // is its normal state) a plain path append is legal on 11+ and genuinely
        // appends: ONE file, many lines, no MediaStore naming semantics at all.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            appendLegacy(payload)
            return
        }
        val cr = ctx.contentResolver
        var uri: Uri? = cachedRow
        var size = -1L
        val cached = uri
        if (cached != null) {
            size = runCatching { cr.openFileDescriptor(cached, "r")?.use { it.statSize } ?: -1L }.getOrDefault(-1L)
            if (size < 0) { cachedRow = null; uri = null } // row deleted under us
        }
        if (uri == null) {
            findRow(ctx, FILE_NAME)?.let { uri = it.first; size = it.second }
        }
        val full = uri
        if (full != null && size > MAX_BYTES) {
            // Roll: the previous generation becomes .1 (any older .1 is dropped first).
            findRow(ctx, "$FILE_NAME.1")?.let { cr.delete(it.first, null, null) }
            runCatching {
                cr.update(full, ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, "$FILE_NAME.1") }, null, null)
            }
            cachedRow = null
            uri = null
        }
        val target = uri ?: cr.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            },
        ) ?: return
        cachedRow = target
        cr.openOutputStream(target, "wa")?.use { it.write(payload.toByteArray()) }
    }

    /**
     * Our row for [name] under Download/<dir>/, with its size — or null before the
     * first write. Matches [name] exactly OR as `<name>.txt`, because MediaStore
     * appends `.txt` to a text/plain display name whose extension it does not
     * recognise (`.log` is not in its map) — matching only the name WE asked for
     * is exactly what turned this log into thirteen one-line files.
     */
    private fun findRow(ctx: Context, name: String): Pair<Uri, Long>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.SIZE)
        ctx.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            "(${MediaStore.Downloads.DISPLAY_NAME}=? OR ${MediaStore.Downloads.DISPLAY_NAME}=?) AND ${MediaStore.Downloads.RELATIVE_PATH}=?",
            arrayOf(name, "$name.txt", "$relativePath/"),
            null,
        )?.use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(0)
                return Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString()) to c.getLong(1)
            }
        }
        return null
    }

    /**
     * #669 the tail of this log, for the loopback debug API (/api/log/tail) — the same
     * bytes a file manager reads from Download/<dir>/, served in-process so an agent on
     * the phone needs no storage grant. Reads our own MediaStore row (or the legacy
     * file) whole — the rotation caps it at [MAX_BYTES] — and keeps the last [lines].
     * The log carries no secret by the rule at the top of this file, so tailing it
     * cannot either.
     */
    fun tail(ctx: Context, lines: Int): String = runCatching {
        val direct = File(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), BuildConfig.DEBUG_LOG_DIR), FILE_NAME)
        val text = if (direct.exists()) {
            // The All-Files-Access append path — the same file, read the same way.
            direct.readText()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val row = findRow(ctx, FILE_NAME) ?: return@runCatching "no debug log yet\n"
            ctx.contentResolver.openInputStream(row.first)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        } else {
            val f = File(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), BuildConfig.DEBUG_LOG_DIR), FILE_NAME)
            if (!f.exists()) return@runCatching "no debug log yet\n"
            f.readText()
        }
        text.lines().takeLast(lines.coerceAtLeast(1)).joinToString("\n")
    }.getOrElse { "debug log read failed: $it\n" }

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
