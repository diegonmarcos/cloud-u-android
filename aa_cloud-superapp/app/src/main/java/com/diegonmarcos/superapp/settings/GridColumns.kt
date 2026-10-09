package com.diegonmarcos.superapp.settings

import android.content.Context
import android.content.SharedPreferences
import com.diegonmarcos.superapp.BuildConfig

/**
 * Icons per row for the Cloud grids and the Phone grids: one place that answers
 * "how many columns", and the sizing rule that keeps that many columns legible.
 *
 * STORAGE. Two keys in the `launcher_settings` prefs ([Kind.key]); the store is
 * already declared in fleet-config.json, which lists these keys. A key is
 * written ONLY when the owner moves the stepper. Until then it is absent and the
 * answer is the shipped default (build.json ui.tile_columns for Cloud, ui
 * .phone_grid_columns for Phone), which is the whole migration: the Phone
 * default went 6 -> 7 and every phone that never chose a value follows it,
 * while a phone that chose one (even 6) has a key and keeps it. Nothing is
 * copied into the store on upgrade, because a copy of the old default would
 * freeze exactly the users who should move.
 */
object GridColumns {
    const val MIN = 4
    const val MAX = 8
    const val STORE = "launcher_settings"

    enum class Kind(val key: String) {
        CLOUD("grid_cols_cloud"),
        PHONE("grid_cols_phone"),
    }

    fun clamp(v: Int) = v.coerceIn(MIN, MAX)

    /** The shipped default for [kind], from build.json via BuildConfig. */
    fun default(kind: Kind): Int = clamp(
        when (kind) {
            Kind.CLOUD -> BuildConfig.UI_TILE_COLUMNS
            Kind.PHONE -> BuildConfig.UI_PHONE_GRID_COLUMNS
        })

    /** Stored value if the owner set one (clamped: a hand-edited or imported
     *  out-of-range number must not break the grid), else [default]. */
    fun read(sp: SharedPreferences, kind: Kind, fallback: Int = default(kind)): Int =
        if (sp.contains(kind.key)) clamp(sp.getInt(kind.key, fallback)) else clamp(fallback)

    internal fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    fun get(ctx: Context, kind: Kind): Int = read(sp(ctx), kind)
    fun cloud(ctx: Context): Int = get(ctx, Kind.CLOUD)
    fun phone(ctx: Context): Int = get(ctx, Kind.PHONE)
    fun set(ctx: Context, kind: Kind, v: Int) { sp(ctx).edit().putInt(kind.key, clamp(v)).apply() }

    // ── Sizing: the same arithmetic the grids use and the 360 dp test asserts ──

    /** Width of one column when [cols] share [availablePx]. */
    fun cellPx(availablePx: Int, cols: Int): Int = availablePx / cols.coerceAtLeast(1)

    /** The icon edge for a cell: the wanted size, but never more than what is
     *  left of the cell after its [chromePx] (padding + margins), and never
     *  below [floorPx]. */
    fun iconPx(wantPx: Int, cellPx: Int, chromePx: Int, floorPx: Int): Int =
        minOf(wantPx, cellPx - chromePx).coerceAtLeast(floorPx)

    /** Label size: 11 sp up to 6 columns, 10 sp from 7 on. Labels are always
     *  single/double line and END-ellipsized, never left to wrap past the cell. */
    fun labelSp(cols: Int): Float = if (cols >= 7) 10f else 11f

    /** Padding inside a cell: tighter from 7 columns on, so the icon keeps room. */
    fun cellPadDp(cols: Int): Int = if (cols >= 7) 4 else 8
}
