package com.diegonmarcos.superapp.fleetconfigs

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/**
 * Configs ▸ General and Configs ▸ About as launcher shortcuts (long-press the app's icon), published for EVERY app that
 * links libs:ui-nav-bars by [FleetConfigsInitProvider] at process start, so no app writes this. For an app with no native
 * menu to extend (React Native, Tauri, Cordova, a generated office host) this is how the user reaches the two pages
 * (configs-pages.json::shortcut_reach); every other app also has them in its own menu. Shortcuts are added by id, never
 * replacing an app's own; a launcher without shortcut support simply shows none.
 */
public object FleetConfigsShortcuts {
    public const val ID_GENERAL: String = "fleetcfg_general"
    public const val ID_ABOUT: String = "fleetcfg_about"

    @JvmStatic
    public fun publish(context: Context) {
        val ctx = context.applicationContext
        runCatching {
            val list = listOf(FleetConfigsPage.General to ID_GENERAL, FleetConfigsPage.About to ID_ABOUT).map { (page, id) ->
                ShortcutInfoCompat.Builder(ctx, id)
                    .setShortLabel(page.label)
                    .setLongLabel(fleetConfigsEntryLabel(page))
                    .setIcon(IconCompat.createWithResource(ctx, if (page == FleetConfigsPage.General) android.R.drawable.ic_menu_preferences else android.R.drawable.ic_menu_info_details))
                    .setIntent(FleetConfigsActivity.intent(ctx, page).setAction(Intent.ACTION_VIEW).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    .build()
            }
            val have = ShortcutManagerCompat.getDynamicShortcuts(ctx).map { it.id }.toSet()
            val room = ShortcutManagerCompat.getMaxShortcutCountPerActivity(ctx) - (have - setOf(ID_GENERAL, ID_ABOUT)).size
            if (room >= list.size) ShortcutManagerCompat.addDynamicShortcuts(ctx, list)
        }
    }
}

/** Publishes [FleetConfigsShortcuts] once per process, off the main thread (merged from this lib's manifest; not exported). */
public class FleetConfigsInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val ctx = context ?: return true
        Thread({ FleetConfigsShortcuts.publish(ctx) }, "fleetcfg-shortcuts").start()
        return true
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
