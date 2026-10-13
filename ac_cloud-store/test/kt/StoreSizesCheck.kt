// Behavioural check of libs:appstore StoreSizes (pure Kotlin), compiled WITH it by
// test-store-phone-sizes.sh. Prints PASS/FAIL lines; exit code = number of failures.
import com.diegonmarcos.superapp.appstore.StoreSizes
import com.diegonmarcos.superapp.appstore.StoreSizes.Size
import com.diegonmarcos.superapp.appstore.StoreSizes.Sort
import kotlin.system.exitProcess

var fails = 0
fun check(name: String, ok: Boolean, got: Any? = null) {
    if (ok) println("  PASS: $name") else { println("  FAIL: $name (got: $got)"); fails++ }
}
fun eq(name: String, want: Any?, got: Any?) = check("$name = $want", want == got, got)

fun main() {
    // size formatting
    eq("format 0", "0 B", StoreSizes.format(0))
    eq("format 512", "512 B", StoreSizes.format(512))
    eq("format 48 KB", "48 KB", StoreSizes.format(48_000))
    eq("format 48 MB", "48 MB", StoreSizes.format(48_000_000))
    eq("format 47.6 MB rounds", "48 MB", StoreSizes.format(47_600_000))
    eq("format 999.6 KB carries to MB", "1 MB", StoreSizes.format(999_600))
    eq("format 999.6 MB carries to GB", "1.0 GB", StoreSizes.format(999_600_000))
    eq("format 1.2 GB", "1.2 GB", StoreSizes.format(1_234_000_000))
    eq("format negative clamps", "0 B", StoreSizes.format(-5))
    // full size: app + data + cache, data reported without the cache it includes
    val full = Size.full(appBytes = 40_000_000, dataBytesWithCache = 8_000_000, cacheBytes = 2_000_000)
    eq("full total = app + data + cache", 48_000_000L, full.totalBytes)
    eq("full row label", "48 MB", StoreSizes.rowLabel(full))
    eq("full breakdown", "app 40 MB · data 6 MB · cache 2 MB = 48 MB", StoreSizes.breakdown(full))
    // the fallback: APK files only, labelled APK
    val apk = Size.apkOnly(listOf(30_000_000, 12_000_000, 6_000_000))
    eq("apk fallback sums base + splits", 48_000_000L, apk.totalBytes)
    check("apk fallback is flagged apkOnly", apk.apkOnly)
    eq("apk fallback row label", "48 MB APK", StoreSizes.rowLabel(apk))
    check("apk fallback breakdown names APK", StoreSizes.breakdown(apk).startsWith("APK 48 MB"), StoreSizes.breakdown(apk))
    // the no-permission / not-measured state
    eq("no size reads as a dash", "—", StoreSizes.rowLabel(null))
    check("grant row shown without usage access", StoreSizes.needsGrantRow(granted = false))
    check("grant row hidden with usage access", !StoreSizes.needsGrantRow(granted = true))
    // the sort comparator
    data class R(val name: String, val bytes: Long?)
    val rows = listOf(R("b", 10), R("a", null), R("c", 300), R("d", 10), R("e", 50))
    val base = compareBy<R> { it.name }
    fun names(s: Sort) = rows.sortedWith(StoreSizes.comparator(s, { r: R -> r.bytes }, base)).joinToString("") { it.name }
    eq("shelf sort is the base order", "abcde", names(Sort.SHELF))
    eq("size desc: largest first, ties by base, unmeasured last", "cebda", names(Sort.SIZE_DESC))
    eq("size asc: smallest first, ties by base, unmeasured last", "bdeca", names(Sort.SIZE_ASC))
    eq("toggle desc -> asc", Sort.SIZE_ASC, StoreSizes.toggleSize(Sort.SIZE_DESC))
    eq("toggle asc -> desc", Sort.SIZE_DESC, StoreSizes.toggleSize(Sort.SIZE_ASC))
    eq("toggle shelf -> desc", Sort.SIZE_DESC, StoreSizes.toggleSize(Sort.SHELF))
    // persistence
    val map = HashMap<String, String>()
    val store = object : StoreSizes.Store {
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String) { map[key] = value }
    }
    eq("nothing saved -> shelf", Sort.SHELF, StoreSizes.loadSort(store))
    StoreSizes.saveSort(store, Sort.SIZE_ASC)
    eq("saved size asc reloads", Sort.SIZE_ASC, StoreSizes.loadSort(store))
    StoreSizes.saveSort(store, Sort.SIZE_DESC)
    eq("saved size desc reloads", Sort.SIZE_DESC, StoreSizes.loadSort(store))
    map[StoreSizes.SORT_KEY] = "garbage"
    eq("unknown saved value -> shelf", Sort.SHELF, StoreSizes.loadSort(store))
    exitProcess(fails)
}
