package com.diegonmarcos.clouddrive.agents

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The Screenshot agent's contract, SERVED: the same records the files under
 * <shared_root>/.agents/screenshots/v1 hold, for a fleet app that would rather not walk shared
 * storage (Cloud Calendar's event agent). Read-only, and guarded by a SIGNATURE permission
 * (${applicationId}.permission.READ_AGENT_RESULTS): only an app signed with the fleet key reads it.
 *
 *  content://<applicationId>.agents.screenshots/records[?since=<processed_at_ms>&limit=<n>]
 *      one row per live record, oldest processed first (index.jsonl order, a later line wins);
 *      columns id, source_path, taken_at_ms, processed_at_ms, has_text, text, labels, json
 *  content://<applicationId>.agents.screenshots/records/<id>    the same row for one id
 *  content://<applicationId>.agents.screenshots/state          state.json as one row
 *  openFile(records/<id>) the record's JSON file itself, read-only
 *
 * Also the agent's start hook: like DriveDebugApiProvider, onCreate runs before
 * Application.onCreate, so it posts the schedule to the main thread instead of touching
 * WorkManager before WorkManager has initialised.
 */
class ScreenshotAgentProvider : ContentProvider() {

    private val matcher by lazy {
        val auth = context!!.packageName + AUTHORITY_SUFFIX
        UriMatcher(UriMatcher.NO_MATCH).apply { addURI(auth, "records", RECORDS); addURI(auth, "records/*", RECORD); addURI(auth, "state", STATE) }
    }

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        Handler(Looper.getMainLooper()).post { runCatching { ScreenshotAgentWorker.schedule(ctx) }.onFailure { Log.w(TAG, "schedule: ${it.message}") } }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val store = ScreenshotAgent.store()
        return when (matcher.match(uri)) {
            RECORDS -> {
                val since = uri.getQueryParameter("since")?.toLongOrNull() ?: 0L
                val limit = uri.getQueryParameter("limit")?.toIntOrNull()?.coerceAtLeast(1) ?: Int.MAX_VALUE
                val c = MatrixCursor(COLUMNS)
                liveIds(store, since).take(limit).forEach { id -> store.read(id)?.let { row(c, it) } }
                c
            }
            RECORD -> MatrixCursor(COLUMNS).also { c -> store.read(uri.lastPathSegment.orEmpty())?.let { row(c, it) } }
            STATE -> {
                val s = store.state()
                MatrixCursor(STATE_COLUMNS).apply { addRow(arrayOf<Any?>(1, s.lastScanMs, s.processed, s.failed, s.cursorMtimeMs, s.total, s.pending)) }
            }
            else -> null
        }
    }

    /** index.jsonl replayed: ids whose latest line is live and was processed after [since], in processing order. */
    private fun liveIds(store: ScreenshotStore, since: Long): List<String> {
        val latest = LinkedHashMap<String, Long>()
        runCatching {
            store.index.forEachLine { line ->
                val o = runCatching { Json.parseToJsonElement(line) as JsonObject }.getOrNull() ?: return@forEachLine
                val id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: return@forEachLine
                latest.remove(id)
                if ((o["deleted"] as? JsonPrimitive)?.booleanOrNull != true) latest[id] = (o["processed_at_ms"] as? JsonPrimitive)?.longOrNull ?: 0L
            }
        }
        return latest.filter { (id, at) -> at > since && store.has(id) }.keys.toList()
    }

    private fun row(c: MatrixCursor, r: ScreenshotRecord) {
        c.addRow(arrayOf<Any?>(r.id, r.sourcePath, r.takenAtMs, r.processedAtMs, if (r.text.isNotBlank()) 1 else 0, r.text,
            r.labels.joinToString(",") { it.label }, r.toJson().toString()))
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (matcher.match(uri) != RECORD || mode != "r") throw SecurityException("read-only: records/<id>")
        val id = uri.lastPathSegment.orEmpty()
        require(id.matches(Regex("[0-9a-f]{16}"))) { "not a record id" }
        return ParcelFileDescriptor.open(ScreenshotAgent.store().recordFile(id), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? = when (matcher.match(uri)) {
        RECORDS -> "vnd.android.cursor.dir/vnd.clouddrive.screenshot"
        RECORD -> "vnd.android.cursor.item/vnd.clouddrive.screenshot"
        STATE -> "vnd.android.cursor.item/vnd.clouddrive.screenshot-state"
        else -> null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("read-only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("read-only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("read-only")

    companion object {
        private const val TAG = "ScreenshotAgentProvider"
        const val AUTHORITY_SUFFIX = ".agents.screenshots"
        private const val RECORDS = 1
        private const val RECORD = 2
        private const val STATE = 3
        val COLUMNS = arrayOf("id", "source_path", "taken_at_ms", "processed_at_ms", "has_text", "text", "labels", "json")
        val STATE_COLUMNS = arrayOf("version", "last_scan_ms", "processed", "failed", "cursor_mtime_ms", "total", "pending")
    }
}
