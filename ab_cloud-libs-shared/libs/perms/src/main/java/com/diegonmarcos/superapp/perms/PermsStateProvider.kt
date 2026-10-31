package com.diegonmarcos.superapp.perms

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * #1360 this app's own grants, readable by the other fleet apps that rely on it (Cloud Drive and Cloud Notes read
 * cld.termux's All files access here before running git or opening the vault). Read-only, guarded by the fleet's
 * signature permission CONSTELLATION_DATA (libs:core), authority `<applicationId>.perms`. Rows: id, state, reason, one
 * per entry of this app's build.json::perms.needs.
 *
 * Its onCreate (before Application.onCreate) also installs [PermsFirstRun], so every app that embeds libs:perms asks
 * for what is missing on its first launch without a line of its own.
 */
class PermsStateProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        (context?.applicationContext as? Application)?.let { runCatching { PermsFirstRun.install(it) } }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val ctx = context ?: return MatrixCursor(COLUMNS)
        val out = MatrixCursor(COLUMNS)
        for (r in AppPerms.own(ctx)) out.addRow(arrayOf(r.entry.id, r.status.state.name, r.status.reason))
        return out
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.diegonmarcos.perms"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY_SUFFIX = ".perms"
        val COLUMNS = arrayOf("id", "state", "reason")
    }
}
