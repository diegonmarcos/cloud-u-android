package com.diegonmarcos.superapp.browser

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.URLUtil
import org.json.JSONArray
import org.json.JSONObject

/** One download this browser started: DownloadManager's id plus what we asked for. */
data class BrowserDownload(val id: Long, val url: String, val file: String, val mime: String, val ts: Long)

/** #802 the download index's (de)serialisation, pure and JVM-tested. */
object BrowserDownloadIndex {
    fun toJson(list: List<BrowserDownload>): JSONArray = JSONArray().also { arr ->
        list.forEach { arr.put(JSONObject().put("id", it.id).put("url", it.url).put("file", it.file).put("mime", it.mime).put("ts", it.ts)) }
    }

    fun fromJson(raw: String?): List<BrowserDownload> {
        val arr = runCatching { JSONArray(raw ?: "[]") }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            if (!o.has("id")) null
            else BrowserDownload(o.optLong("id"), o.optString("url"), o.optString("file"), o.optString("mime"), o.optLong("ts"))
        }
    }

    /** ponytail: a flat cap; older entries drop off the index (the files stay in Downloads). */
    const val CAP = 200

    fun add(list: List<BrowserDownload>, d: BrowserDownload) = (listOf(d) + list.filterNot { it.id == d.id }).take(CAP)

    /** DownloadManager.STATUS_* in words. */
    fun status(code: Int): String = when (code) {
        1 -> "pending"; 2 -> "running"; 4 -> "paused"; 8 -> "successful"; 16 -> "failed"; else -> "unknown"
    }
}

/**
 * #802 downloads through Android's DownloadManager (it owns the progress
 * notification, and tapping that notification opens the file), with the page's
 * cookies and user agent so a signed-in download works. The index of what we
 * started lives in the `browser_downloads` prefs (declared, class device: it names
 * files on THIS phone).
 */
class BrowserDownloads(context: Context) {
    private val app = context.applicationContext
    private val sp = app.getSharedPreferences("browser_downloads", Context.MODE_PRIVATE)
    private val dm get() = app.getSystemService(DownloadManager::class.java)

    fun all(): List<BrowserDownload> = BrowserDownloadIndex.fromJson(sp.getString(KEY, "[]"))

    /** Queue [url]; [dir] is a sub-folder of the public Downloads ("" = Downloads itself). */
    fun enqueue(url: String, userAgent: String?, contentDisposition: String?, mime: String?, dir: String,
                cookieHeader: (String) -> String?): BrowserDownload {
        val name = URLUtil.guessFileName(url, contentDisposition, mime)
        val rel = BrowserBookmarkOps.normFolder(dir).let { if (it.isEmpty()) name else "$it/$name" }
        val req = DownloadManager.Request(Uri.parse(url))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, rel)
            .setTitle(name)
        cookieHeader(url)?.let { req.addRequestHeader("Cookie", it) }
        userAgent?.let { req.addRequestHeader("User-Agent", it) }
        mime?.takeIf { it.isNotBlank() }?.let { req.setMimeType(it) }
        val id = dm.enqueue(req)
        val d = BrowserDownload(id, url, rel, mime.orEmpty(), System.currentTimeMillis())
        sp.edit().putString(KEY, BrowserDownloadIndex.toJson(BrowserDownloadIndex.add(all(), d)).toString()).apply()
        return d
    }

    /** DownloadManager's live status for [id], in words. */
    fun status(id: Long): String = runCatching {
        dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) "gone"
            else BrowserDownloadIndex.status(c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)))
        }
    }.getOrDefault("unknown")

    /** Forget the index (the files stay in Downloads). */
    fun clear() = sp.edit().putString(KEY, "[]").apply()

    /** #887 bytes of the finished downloads this browser started (DownloadManager's own sizes). */
    fun bytes(): Long = all().sumOf { d ->
        runCatching {
            dm.query(DownloadManager.Query().setFilterById(d.id)).use { c ->
                if (c.moveToFirst() && c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL)
                    c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).coerceAtLeast(0) else 0L
            }
        }.getOrDefault(0L)
    }

    /** #887 delete the files of every download this browser started (DownloadManager removes them), then the index. */
    fun removeAllFiles(): Int {
        val list = all()
        list.forEach { runCatching { dm.remove(it.id) } }
        clear()
        return list.size
    }

    private companion object { const val KEY = "downloads_json" }
}
