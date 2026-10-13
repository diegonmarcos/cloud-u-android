package com.diegonmarcos.cloudagenda.agents

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * [ScreenshotFeed] over Cloud Drive's Screenshot agent, contract
 * "cloud-drive.agent.screenshots" v1 (ac_cloud-drive/data/agent-screenshots-contract.json).
 *
 * Read through Cloud Drive's read-only content provider rather than by walking
 * shared storage, so this app needs no all-files access: the provider is
 * guarded by a signature permission this app holds by sharing the fleet key.
 *
 * The provider is read incrementally with the contract's own cursor
 * (processed_at_ms); records already read stay in memory for the life of the
 * process. Only records with OCR text taken within [WINDOW_MS] are kept: an
 * event the parser could find has to be written down somewhere.
 */
class DriveScreenshotFeed(context: Context) : ScreenshotFeed {

    private val ctx = context.applicationContext
    private val cache = LinkedHashMap<String, JSONObject>()
    private var cursor = 0L

    override fun unavailableReason(): String? {
        if (ctx.packageManager.resolveContentProvider(AUTHORITY, 0) == null)
            return "Cloud Drive's Screenshot agent is not installed - update Cloud Drive from Store"
        if (ctx.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED)
            return "Calendar was installed before Cloud Drive's Screenshot agent - update or reinstall Calendar to read it"
        return null
    }

    @Synchronized
    override fun records(): JSONArray {
        if (unavailableReason() != null) return JSONArray()
        runCatching { pull() }
        val oldest = System.currentTimeMillis() - WINDOW_MS
        val out = JSONArray()
        cache.values.filter { it.optLong("takenAt") >= oldest }.forEach { out.put(it) }
        return out
    }

    /** Pages through records?since=<cursor> until the provider runs dry. */
    private fun pull() {
        while (true) {
            val uri = Uri.parse("content://$AUTHORITY/records?since=$cursor&limit=$PAGE")
            var rows = 0
            var maxProcessed = cursor
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val iId = c.getColumnIndex("id")
                val iPath = c.getColumnIndex("source_path")
                val iTaken = c.getColumnIndex("taken_at_ms")
                val iProcessed = c.getColumnIndex("processed_at_ms")
                val iHasText = c.getColumnIndex("has_text")
                val iText = c.getColumnIndex("text")
                val iJson = c.getColumnIndex("json")
                while (c.moveToNext()) {
                    rows++
                    val processed = c.getLong(iProcessed)
                    if (processed > maxProcessed) maxProcessed = processed
                    val id = c.getString(iId) ?: continue
                    if (c.getInt(iHasText) == 0) { cache.remove(id); continue }
                    val record = if (iJson >= 0) runCatching { JSONObject(c.getString(iJson) ?: "{}") }.getOrNull() else null
                    cache[id] = toFeedRecord(id, c.getString(iPath).orEmpty(), c.getLong(iTaken), c.getString(iText).orEmpty(), record)
                }
            }
            // Stop when the page was short or the cursor cannot advance any further.
            if (rows < PAGE || maxProcessed <= cursor) { if (maxProcessed > cursor) cursor = maxProcessed; return }
            cursor = maxProcessed
        }
    }

    /** One contract record in the shape the page's parser reads (see [ScreenshotFeed]). */
    private fun toFeedRecord(id: String, path: String, taken: Long, text: String, record: JSONObject?): JSONObject {
        var place = ""
        val entities = JSONArray()
        record?.optJSONArray("entities")?.let { arr ->
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val type = e.optString("type")
                if (type == "address" && place.isEmpty()) place = e.optString("text")
                if (type == "date" || type == "time" || type == "address")
                    entities.put(JSONObject().put("type", type).put("text", e.optString("text")))
            }
        }
        return JSONObject()
            .put("id", id)
            .put("text", text)
            .put("takenAt", taken)
            .put("place", place)
            .put("source", path.substringAfterLast('/'))
            .put("sourceApp", record?.optString("source_app").takeUnless { it.isNullOrEmpty() || it == "null" } ?: "")
            .put("entities", entities)
    }

    private companion object {
        const val DRIVE = "com.diegonmarcos.clouddrive"
        const val AUTHORITY = "$DRIVE.agents.screenshots"
        const val PERMISSION = "$DRIVE.permission.READ_AGENT_RESULTS"
        const val PAGE = 200
        const val WINDOW_MS = 180L * 24 * 3600 * 1000
    }
}
