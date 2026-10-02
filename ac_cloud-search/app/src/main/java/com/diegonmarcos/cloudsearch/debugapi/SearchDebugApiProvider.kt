package com.diegonmarcos.cloudsearch.debugapi

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * Registers [SearchDebugApi]'s routes before Application.onCreate (the CalcDebugApiProvider trick):
 * a ContentProvider's onCreate runs first, so the routes exist even if the app's own startup later
 * throws. Not a real provider and not exported.
 */
class SearchDebugApiProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        // A debug facility must never take the host app down.
        runCatching { SearchDebugApi.register(ctx) }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
