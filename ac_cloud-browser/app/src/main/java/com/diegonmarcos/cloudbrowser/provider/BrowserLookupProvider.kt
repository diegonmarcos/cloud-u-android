package com.diegonmarcos.cloudbrowser.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Process
import com.diegonmarcos.cloudbrowser.BuildConfig
import com.diegonmarcos.superapp.browser.BrowserBookmarks
import com.diegonmarcos.superapp.browser.BrowserConfig
import com.diegonmarcos.superapp.browser.BrowserHistory
import com.diegonmarcos.superapp.browser.BrowserSettings
import com.diegonmarcos.superapp.browser.BrowserTabPrefs

/**
 * The read-only lookup the SuperApp's search runs against this browser's favourites, history and
 * address-bar decision: [BrowserLookup] is the contract and every rule; this file only reads the
 * stores and writes the cursor. Exported behind the signature permission, re-checked here (a
 * second line, as Cloud Account's accountdata provider does). No insert, update or delete.
 */
class BrowserLookupProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val ctx = context ?: return null
        val granted = ctx.checkCallingPermission(BrowserLookup.PERMISSION) == PackageManager.PERMISSION_GRANTED
        if (!BrowserLookup.allowed(Binder.getCallingUid(), Process.myUid(), granted)) {
            throw SecurityException("${BrowserLookup.PERMISSION} required")
        }
        val q = BrowserLookup.query(uri.getQueryParameter(BrowserLookup.PARAM_QUERY))
        val limit = BrowserLookup.limit(uri.getQueryParameter(BrowserLookup.PARAM_LIMIT))
        // The stores are this app's own; read them as this app, not as the caller.
        val token = Binder.clearCallingIdentity()
        val rows = try {
            when (uri.lastPathSegment) {
                BrowserLookup.PATH_FAVOURITES -> BrowserLookup.favourites(BrowserBookmarks(ctx).all(), q, limit)
                BrowserLookup.PATH_HISTORY -> BrowserLookup.history(
                    BrowserHistory(ctx).all(), BrowserTabPrefs(ctx).all(), q, limit, System.currentTimeMillis())
                BrowserLookup.PATH_WEB -> {
                    val config = BrowserConfig.parseBase64(BuildConfig.UI_BROWSER_CONFIG_B64)
                    val engine = config.engine(BrowserSettings(ctx, config.settings).searchEngineId())
                    listOfNotNull(BrowserLookup.web(q, engine))
                }
                else -> emptyList()
            }
        } finally {
            Binder.restoreCallingIdentity(token)
        }
        return MatrixCursor(BrowserLookup.COLUMNS, rows.size).apply {
            rows.forEach { addRow(arrayOf<Any>(it.kind, it.title, it.url, it.time)) }
        }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
