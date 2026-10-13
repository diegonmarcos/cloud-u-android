package com.diegonmarcos.superapp.appstore

/**
 * Store ▸ Phone: an app's SIZE, as pure rules (no Android) so a plain JVM can test them
 * (ac_cloud-store/test/test-store-phone-sizes.sh compiles this file alone).
 *
 * Two sources, never mixed on one row:
 *  - [Size.full]: StorageStatsManager.queryStatsForPackage — app + data + cache. Needs usage
 *    access (PACKAGE_USAGE_STATS), granted by the user on ACTION_USAGE_ACCESS_SETTINGS.
 *  - [Size.apkOnly]: without that grant, the bytes of base.apk plus its splits
 *    (ApplicationInfo.sourceDir / splitSourceDirs), labelled "APK" so it never reads as the total.
 * No size at all (not measured yet, not installed) is "—", never a zero that would read as empty.
 */
object StoreSizes {

    data class Size(val appBytes: Long, val dataBytes: Long, val cacheBytes: Long, val apkOnly: Boolean) {
        val totalBytes: Long get() = appBytes + dataBytes + cacheBytes

        companion object {
            /** queryStatsForPackage's numbers: its dataBytes INCLUDES the cache, so they are told apart here. */
            fun full(appBytes: Long, dataBytesWithCache: Long, cacheBytes: Long) =
                Size(appBytes, (dataBytesWithCache - cacheBytes).coerceAtLeast(0), cacheBytes, apkOnly = false)

            /** The fallback: the APK files' lengths (base + splits), nothing else. */
            fun apkOnly(fileLengths: List<Long>) = Size(fileLengths.sumOf { it.coerceAtLeast(0) }, 0, 0, apkOnly = true)
        }
    }

    const val NONE = "—"
    const val APK = "APK"

    /** Compact, SI (as Android's own Settings counts): "512 B", "48 KB", "48 MB", "1.2 GB". */
    fun format(bytes: Long): String {
        val b = bytes.coerceAtLeast(0)
        return when {
            b < 1_000L -> "$b B"
            b < 999_500L -> "${(b + 500) / 1_000} KB"
            b < 999_500_000L -> "${(b + 500_000) / 1_000_000} MB"
            else -> {
                val tenths = (b + 50_000_000) / 100_000_000
                "${tenths / 10}.${tenths % 10} GB"
            }
        }
    }

    /** The row's size slot: "48 MB", "48 MB APK", or "—". */
    fun rowLabel(size: Size?): String = when {
        size == null -> NONE
        size.apkOnly -> format(size.totalBytes) + " " + APK
        else -> format(size.totalBytes)
    }

    /** The detail line: the breakdown, or what the APK figure leaves out. */
    fun breakdown(size: Size?): String = when {
        size == null -> NONE
        size.apkOnly -> "$APK ${format(size.appBytes)} · data and cache need usage access"
        else -> "app ${format(size.appBytes)} · data ${format(size.dataBytes)} · cache ${format(size.cacheBytes)} = ${format(size.totalBytes)}"
    }

    /** The one-tap grant row is drawn exactly when usage access is missing. */
    fun needsGrantRow(granted: Boolean): Boolean = !granted

    // ── sort ─────────────────────────────────────────────────────────────────
    /** The Phone list's order. SHELF is the page's original order (shelf, then label). */
    enum class Sort(val id: String) { SHELF("shelf"), SIZE_DESC("size_desc"), SIZE_ASC("size_asc") }

    /** Tapping the Size chip: largest first, then smallest first, back and forth. */
    fun toggleSize(current: Sort): Sort = if (current == Sort.SIZE_DESC) Sort.SIZE_ASC else Sort.SIZE_DESC

    /**
     * [base] (shelf, label) for SHELF; for a size sort the measured total in the chosen direction,
     * [base] breaking ties. A row with no size sorts LAST in both directions — an unmeasured app is
     * not the smallest one.
     */
    fun <T> comparator(sort: Sort, sizeOf: (T) -> Long?, base: Comparator<T>): Comparator<T> = when (sort) {
        Sort.SHELF -> base
        Sort.SIZE_DESC, Sort.SIZE_ASC -> Comparator { a, b ->
            val x = sizeOf(a); val y = sizeOf(b)
            when {
                x == null && y == null -> base.compare(a, b)
                x == null -> 1
                y == null -> -1
                x == y -> base.compare(a, b)
                sort == Sort.SIZE_DESC -> y.compareTo(x)
                else -> x.compareTo(y)
            }
        }
    }

    /** Where the choice is kept: a key/value store (SharedPreferences on the phone, a map in the test). */
    interface Store { fun get(key: String): String?; fun put(key: String, value: String) }

    const val SORT_KEY = "sort"

    fun loadSort(store: Store): Sort = store.get(SORT_KEY)?.let { id -> Sort.values().firstOrNull { it.id == id } } ?: Sort.SHELF
    fun saveSort(store: Store, sort: Sort) = store.put(SORT_KEY, sort.id)
}
