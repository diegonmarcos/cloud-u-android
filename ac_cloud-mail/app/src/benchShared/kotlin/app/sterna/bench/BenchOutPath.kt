package app.sterna.bench

/**
 * WHERE A BENCH REPORT MAY LAND — the decision, on its own, in pure Kotlin.
 *
 * `BenchProvisionReceiver` is exported and carries NO permission (it cannot be otherwise: a
 * non-exported receiver refuses the shell's explicit broadcast). It takes the report's destination
 * off an `--es out` extra and writes it with a whole-file write, which **truncates**. So
 * until this predicate existed, any app on the device could name `/data/data/app.sterna.test/files/…`
 * — or any other file the test uid can open — and have it replaced by the report, inside a receiver
 * whose own KDoc promises it "REMOVES NOTHING, ever".
 *
 * The bench already only ever writes under `/data/local/tmp/`: that is what the driving script
 * pushes and pre-creates world-writable, because `/data/local/tmp` is `shell:shell` and this app's
 * uid may traverse it but not create files in it. The documentation said so; the code did not.
 * This says it in code.
 *
 * ⛔ WHY THIS FILE IS NOT IN `src/testApp/kotlin` WITH ITS CALLER. That source set is registered
 * only under `-PtestApp` (see `app/build.gradle.kts`) and the unit-test suite does not pass the
 * property, so no test can execute a single line of the receiver. A decision nobody can run is a
 * decision nobody can test — hence `src/benchShared/kotlin`, wired to the `test` source set when
 * the property is absent and to the bench variants when it is present, one copy either way.
 * ⛔ It still does not belong in `src/main`: without `-PtestApp` the published APK must stay
 * bit-for-bit what it was, and nothing here is ever compiled into it.
 *
 * ⚠ Pure string judgement, deliberately. Two things it therefore does NOT see, and both are
 * accepted knowingly:
 *  - a SYMLINK planted inside `/data/local/tmp` pointing anywhere else. Resolving that needs the
 *    filesystem (`canonicalPath` / `O_NOFOLLOW`), which no pure predicate can do — and planting it
 *    needs the `shell` uid, which already outranks the app.
 *  - it NORMALISES NOTHING. `..`, `.`, an empty segment (`//`) and a trailing `/` are REFUSED, not
 *    resolved. A path that means something legitimate the long way round is rejected; the bench
 *    writes plain paths, and refusing is the cheap half of the choice.
 *
 * ⚠ A TRAILING SPACE IS ACCEPTED: `/data/local/tmp/bench.out ` names a different, real file — and
 * it names it INSIDE the allowed directory, so nothing outside can be truncated by it. Trimming
 * would be worse: it would write to a path the caller did not name. Stated here rather than pinned
 * in the tests, because it is a choice, not an oversight.
 *
 * ⚠ This file is READ AS TEXT by `BenchProvisionSourceTest`, which walks both bench source
 * directories and does not skip comments (a rule that skipped them could be dodged behind a `//`).
 * So the forbidden file verbs are described in words here, never spelled as calls.
 */

/** The one directory a bench report may be written into — with its trailing separator. */
const val BENCH_OUT_DIR = "/data/local/tmp/"

/**
 * True when [path] names a plain file under [BENCH_OUT_DIR], and nothing else.
 *
 * `null`, empty and blank are false: no `--es out` at all is not an error (logcat and the ordered
 * broadcast still carry the verdict), it just means there is nothing to write.
 *
 * A bare `startsWith` is NOT enough and that is the whole point of the segment walk below:
 * `/data/local/tmp/../../data/data/app.sterna.test/x` starts with the allowed prefix and lands in
 * the app's own private data.
 */
fun isBenchOutPathAllowed(path: String?): Boolean {
    if (path == null || path.isBlank()) return false
    if (!path.startsWith(BENCH_OUT_DIR)) return false
    val relative = path.removePrefix(BENCH_OUT_DIR)
    // Every segment must be an ordinary name: no "..", no ".", no empty one (which is a trailing
    // slash, i.e. a directory, or a doubled separator). Nothing is resolved — see the file KDoc.
    // The directory itself lands here too: "" splits to [""], one empty segment, refused below.
    return relative.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
}
