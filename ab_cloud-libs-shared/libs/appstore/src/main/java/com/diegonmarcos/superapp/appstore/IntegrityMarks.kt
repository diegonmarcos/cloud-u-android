package com.diegonmarcos.superapp.appstore

import android.content.Context

/**
 * The phone's OWN integrity findings: packages declared `integrity: unknown`
 * that the user reported "did not run" after this store installed them from
 * Google Play's servers (play-anon). Marked `play` locally, persisted, and
 * exported with the app inventory ([AppInventory.toJson]) so the finding
 * travels with the phone's profile instead of dying with this install.
 */
object IntegrityMarks {
    private const val PREFS = "store_integrity_marks"
    private const val KEY = "play"

    fun marked(ctx: Context): Set<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, emptySet()).orEmpty()

    fun mark(ctx: Context, pkg: String) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().putStringSet(KEY, marked(ctx) + pkg).apply()
    }

    /** The declared integrity, raised to PLAY by a local "did not run" mark. */
    fun effective(ctx: Context, app: SourceResolver.External): SourceResolver.Integrity =
        if (app.integrity == SourceResolver.Integrity.UNKNOWN && app.pkg in marked(ctx)) SourceResolver.Integrity.PLAY
        else app.integrity

    /** Offer "did not run" only where it means something: an UNKNOWN app this store installed. */
    fun canReport(ctx: Context, app: SourceResolver.External): Boolean =
        app.integrity == SourceResolver.Integrity.UNKNOWN && app.pkg !in marked(ctx) &&
            PhoneAppActions.installerOf(ctx, app.pkg) == ctx.packageName
}
