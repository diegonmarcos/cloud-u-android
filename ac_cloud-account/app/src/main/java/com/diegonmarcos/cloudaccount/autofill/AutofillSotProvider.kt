package com.diegonmarcos.cloudaccount.autofill

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.UriMatcher
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import com.diegonmarcos.superapp.autofill.AutofillSot

/**
 * `content://com.diegonmarcos.cloudaccount.autofill/{profiles|rules|snippets}[/<id>]` — the
 * non-secret autofill Source of Truth as the fleet reads it (a0_docs/eng-specs/autofill-3-tier.md).
 *
 * WHO   exported behind AUTOFILL_PROFILE_READ (query) / AUTOFILL_PROFILE_WRITE (insert), both
 *       protectionLevel=signature (libs:autofill's manifest), and every entry point re-checks
 *       its permission here too: call() is never permission-checked by the framework, and a
 *       provider that trusts its manifest alone is one edit away from open.
 * WHAT  readers get every row (they need values to fill); external writers may only INSERT
 *       (the browser's user-confirmed "save this address"). Update and delete are the
 *       owner's: Cloud Account's own editor, same uid.
 * NEVER a log line with a value: rows are personal data.
 */
class AutofillSotProvider : ContentProvider() {

    private val store by lazy { AutofillStore(requireNotNull(context)) }

    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        val ctx = context ?: return null
        enforce(ctx, AutofillSot.PERMISSION_READ)
        val (path, id) = match(uri) ?: return null
        val all = AutofillSot.columns(path)
        val cols = projection?.filter { it in all }?.takeIf { it.isNotEmpty() } ?: all
        val c = MatrixCursor(cols.toTypedArray())
        val rows = if (id != null) listOfNotNull(store.row(path, id)) else store.rows(path)
        rows.forEach { r -> c.addRow(cols.map { r[it].orEmpty() }) }
        c.setNotificationUri(ctx.contentResolver, AutofillSot.uri(path))
        return c
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val ctx = context ?: return null
        enforce(ctx, AutofillSot.PERMISSION_WRITE)
        val (path, id) = match(uri) ?: return null
        if (id != null) return null
        val newId = store.insert(path, values.toMap()) ?: return null
        changed(ctx, path)
        return ContentUris.withAppendedId(AutofillSot.uri(path), newId)
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int {
        val ctx = context ?: return 0
        enforceOwner(ctx)
        val (path, id) = match(uri) ?: return 0
        val n = store.update(path, id ?: return 0, values.toMap())
        if (n > 0) changed(ctx, path)
        return n
    }

    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int {
        val ctx = context ?: return 0
        enforceOwner(ctx)
        val (path, id) = match(uri) ?: return 0
        val n = store.delete(path, id ?: return 0)
        if (n > 0) changed(ctx, path)
        return n
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        enforce(ctx, AutofillSot.PERMISSION_READ)
        return when (method) {
            AutofillSot.METHOD_VERSION -> Bundle().apply { putInt("version", AutofillSot.VERSION) }
            else -> null
        }
    }

    override fun getType(uri: Uri): String? = match(uri)?.let { (path, id) ->
        (if (id == null) "vnd.android.cursor.dir/" else "vnd.android.cursor.item/") + "vnd.${AutofillSot.AUTHORITY}.$path"
    }

    private fun changed(ctx: Context, path: String) = ctx.contentResolver.notifyChange(AutofillSot.uri(path), null)

    private fun ContentValues?.toMap(): Map<String, String?> =
        this?.keySet()?.associateWith { k -> getAsString(k) } ?: emptyMap()

    companion object {
        private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            AutofillSot.PATHS.forEachIndexed { i, p ->
                addURI(AutofillSot.AUTHORITY, p, i * 2)
                addURI(AutofillSot.AUTHORITY, "$p/#", i * 2 + 1)
            }
        }

        /** (table, id or null) for a SOT uri, null for anything else. */
        fun match(uri: Uri): Pair<String, Long?>? {
            val code = matcher.match(uri).takeIf { it >= 0 } ?: return null
            val path = AutofillSot.PATHS[code / 2]
            return path to (if (code % 2 == 1) ContentUris.parseId(uri) else null)
        }

        /** The caller check. A JVM test swaps it (Robolectric cannot fake a foreign Binder uid). */
        @Volatile var access: (Context, String) -> Boolean = { ctx, perm ->
            Binder.getCallingUid() == Process.myUid() ||
                ctx.checkCallingPermission(perm) == PackageManager.PERMISSION_GRANTED
        }

        /** Same-uid only: Cloud Account's own editor. Swappable for tests like [access]. */
        @Volatile var isOwner: () -> Boolean = { Binder.getCallingUid() == Process.myUid() }

        fun enforce(ctx: Context, perm: String) {
            if (!access(ctx, perm)) throw SecurityException("autofill SOT: caller lacks $perm")
        }

        fun enforceOwner(ctx: Context) {
            if (!isOwner()) throw SecurityException("autofill SOT: only Cloud Account edits or deletes rows")
        }
    }
}
