package com.diegonmarcos.superapp.browser

import android.content.Context
import android.os.Bundle
import android.os.Parcel
import android.webkit.WebView
import java.io.File

/**
 * #886 a tab's WebView state (its back/forward list and the page it is on) kept in a file per tab id, so
 * that closing the app, or leaving the tab for the grid (which destroys the WebView), does not lose
 * where the tab was. The tab's URL alone ([BrowserTabStore.commit]) is the floor that always works;
 * this file adds the back stack on top where WebView can restore it.
 *
 * A file rather than a SharedPreferences value: a Bundle can be hundreds of KB, and prefs are read
 * whole at startup. Written to a temp name and renamed, so a process killed mid-write leaves the old
 * state, never half of a new one. Private tabs never get one.
 */
object BrowserWebState {
    /** A state bigger than this is skipped (the url still persists): a runaway history must not fill the disk. */
    const val MAX_BYTES = 768 * 1024

    private fun dir(ctx: Context) = File(ctx.filesDir, "tabstate").apply { mkdirs() }

    fun file(ctx: Context, tabKey: String) = File(dir(ctx), tabKey.filter { it.isLetterOrDigit() || it == '-' || it == '_' } + ".bin")

    /** Save [wv]'s state for [tabKey]. @return true when a state was written. */
    fun save(ctx: Context, wv: WebView, tabKey: String): Boolean = runCatching {
        val b = Bundle()
        val list = wv.saveState(b)
        if (list == null || list.size == 0) return false
        val p = Parcel.obtain()
        val bytes = try { p.writeBundle(b); p.marshall() } finally { p.recycle() }
        if (bytes.size > MAX_BYTES) return false
        val f = file(ctx, tabKey)
        val tmp = File(f.path + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        true
    }.getOrDefault(false)

    /** Restore [tabKey]'s state into [wv]. @return true when WebView took it (it is loading that page). */
    fun restore(ctx: Context, wv: WebView, tabKey: String): Boolean = runCatching {
        val f = file(ctx, tabKey)
        if (!f.isFile || f.length() == 0L) return false
        val bytes = f.readBytes()
        val p = Parcel.obtain()
        try {
            p.unmarshall(bytes, 0, bytes.size); p.setDataPosition(0)
            val b = p.readBundle(WebView::class.java.classLoader) ?: return false
            val list = wv.restoreState(b) ?: return false
            // #893 a state whose current entry is a POST result would replay it from the cache (ERR_CACHE_MISS): refuse it.
            !BrowserNavPolicy.isFormPostResult(list.currentItem?.url)
        } finally { p.recycle() }
    }.getOrDefault(false)

    fun delete(ctx: Context, tabKey: String) { runCatching { file(ctx, tabKey).delete() } }

    /** Bytes held by every saved tab state (the storage breakdown). */
    fun bytes(ctx: Context): Long = dir(ctx).listFiles()?.sumOf { it.length() } ?: 0L

    fun clearAll(ctx: Context) { runCatching { dir(ctx).deleteRecursively() } }

    /** Drop the states of tabs that no longer exist. */
    fun prune(ctx: Context, liveKeys: Set<String>) {
        val keep = liveKeys.map { file(ctx, it).name }.toSet()
        dir(ctx).listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
    }
}
