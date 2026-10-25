/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.diegonmarcos.mediacenter.shell

/** Photos with a usable GPS position (they go on the map) and without (listed separately). */
data class GeoSplit<T>(val located: List<T>, val unlocated: List<T>)

object GeoPositions {
    /**
     * A position the map can place. EXIF without a fix is often written as 0,0, and NaN or
     * out-of-range values mean a damaged tag: none of those is a place.
     */
    fun isUsable(latitude: Double?, longitude: Double?): Boolean {
        if (latitude == null || longitude == null) return false
        if (latitude.isNaN() || longitude.isNaN()) return false
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return false
        return !(latitude == 0.0 && longitude == 0.0)
    }

    /** Splits [items] by whether [position] gives them a usable position; order is kept in both lists. */
    fun <T> split(items: List<T>, position: (T) -> Pair<Double?, Double?>): GeoSplit<T> {
        val (with, without) = items.partition { item -> position(item).let { isUsable(it.first, it.second) } }
        return GeoSplit(with, without)
    }
}
