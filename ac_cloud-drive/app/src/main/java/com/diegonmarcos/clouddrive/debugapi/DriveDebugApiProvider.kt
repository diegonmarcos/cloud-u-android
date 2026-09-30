package com.diegonmarcos.clouddrive.debugapi

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * Registers [DriveDebugApi]'s route groups with ZERO code in an Application class —
 * the same trick libs:devtools' DebugInitProvider uses to start [AppDebugServer]
 * itself: a ContentProvider's onCreate runs before Application.onCreate, so the
 * git debug routes exist even if the app's own startup later throws.
 *
 * Not a real provider — every data method is a no-op, and it is not exported.
 * Registration order relative to DebugInitProvider does not matter:
 * AppDebugServer.route only fills the table the running server reads.
 */
class DriveDebugApiProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        // A debug facility must never take the host app down.
        runCatching { DriveDebugApi.register(ctx) }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
