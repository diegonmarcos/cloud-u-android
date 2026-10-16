package org.fossify.phone.fleet

import android.content.ContentValues
import android.content.Context
import android.provider.BlockedNumberContract
import android.provider.CallLog
import com.diegonmarcos.superapp.fleetconfig.SetupStores
import org.json.JSONArray
import org.json.JSONObject

/**
 * The `call_history` store of the fleet setup contract (file `history`): the phone's call log
 * (`calls`, one JSON object per call) and its blocked numbers (`blocked`), so a new phone gets both.
 * Apply adds the calls the phone does not have (same number and start time) and the blocked numbers
 * it lacks; it never deletes. Writing needs this app to be the default phone app, which it is meant to be;
 * without the role a write fails and Fleet Setup names it.
 */
object CallHistoryBackup : SetupStores.SetupHandler {
    override fun read(ctx: Context, file: String): Map<String, Any?>? {
        val calls = JSONArray()
        val proj = arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.CACHED_NAME)
        runCatching {
            ctx.contentResolver.query(CallLog.Calls.CONTENT_URI, proj, null, null, "${CallLog.Calls.DATE} ASC")?.use { c ->
                while (c.moveToNext()) calls.put(JSONObject().put("number", c.getString(0).orEmpty()).put("type", c.getInt(1))
                    .put("date", c.getLong(2)).put("duration", c.getLong(3)).put("name", c.getString(4).orEmpty()))
            }
        }
        val blocked = JSONArray()
        runCatching {
            ctx.contentResolver.query(BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                arrayOf(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER), null, null, null)?.use { c ->
                while (c.moveToNext()) blocked.put(c.getString(0).orEmpty())
            }
        }
        if (calls.length() == 0 && blocked.length() == 0) return null
        return mapOf("calls" to calls.toString(), "count" to calls.length().toLong(), "blocked" to blocked.toString())
    }

    override fun write(ctx: Context, file: String, set: Map<String, Any?>, remove: Set<String>): Boolean = runCatching {
        val have = HashSet<String>()
        ctx.contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE), null, null, null)?.use { c ->
            while (c.moveToNext()) have += "${c.getString(0)}|${c.getLong(1)}"
        }
        (set["calls"] as? String)?.let { JSONArray(it) }?.let { a ->
            val rows = (0 until a.length()).map { a.getJSONObject(it) }
                .filter { "${it.optString("number")}|${it.optLong("date")}" !in have }
                .map { o -> ContentValues().apply {
                    put(CallLog.Calls.NUMBER, o.optString("number")); put(CallLog.Calls.TYPE, o.optInt("type"))
                    put(CallLog.Calls.DATE, o.optLong("date")); put(CallLog.Calls.DURATION, o.optLong("duration"))
                    put(CallLog.Calls.CACHED_NAME, o.optString("name")); put(CallLog.Calls.NEW, 0)
                } }
            if (rows.isNotEmpty()) ctx.contentResolver.bulkInsert(CallLog.Calls.CONTENT_URI, rows.toTypedArray())
        }
        (set["blocked"] as? String)?.let { JSONArray(it) }?.let { a ->
            if (BlockedNumberContract.canCurrentUserBlockNumbers(ctx)) for (i in 0 until a.length()) {
                val n = a.getString(i)
                if (n.isNotBlank() && !BlockedNumberContract.isBlocked(ctx, n))
                    ctx.contentResolver.insert(BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                        ContentValues().apply { put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, n) })
            }
        }
        true
    }.getOrDefault(false)
}
