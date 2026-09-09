package app.sterna.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * The one decision behind the centred loading ring (#63), RUN — plus a source lint holding the two
 */
class LoadingRingTest {

    // -- 1. the decision, executed ---------------------------------------------------------------

    @Test fun `with animations on, Material 3 keeps its own indeterminate arc`() {
        assertNull(
            "with motion allowed the ring must be left indeterminate (null = Material 3 animates " +
                "it). Answering a fixed turn here freezes every loading screen in the app into a " +
                "static circle that can no longer be told apart from a stuck one.",
            loadingRingProgress(motionOn = true),
        )
    }

    @Test fun `with animations off, the ring rests at a full turn`() {
        assertEquals(
            "with motion off the ring must be drawn determinate at 1f — a WHOLE circle. This is " +
                "the defect itself: with the system animations off, Material 3's indeterminate " +
                "arc is left on screen as a few pixels of stroke on the circumference and no " +
                "longer reads as a loading indicator (#63, filmed by the reporter). Any value " +
                "below 1f is a fragment, and 0f draws nothing at all — the screen would stop " +
                "saying it loads.",
            1f,
            loadingRingProgress(motionOn = false),
        )
    }

    // -- 2. the composable asks the decision, and animates in ONE of its two branches -------------

    /**
     * SOURCE LINT. `LoadingRing` cannot be rendered here — no Robolectric and no
     */
    @Test fun `the composable routes through the decision, and animates only on null`() {
        assertBlock(
            LOADING_RING,
            "fun LoadingRing(",
            listOf(
                "fun LoadingRing(",
                "modifier: Modifier = Modifier,",
                "strokeWidth: Dp = ProgressIndicatorDefaults.CircularStrokeWidth,",
                ") {",
                "val rest = loadingRingProgress(rememberMotionEnabled())",
                "if (rest == null) {",
                "CircularProgressIndicator(modifier = modifier, strokeWidth = strokeWidth)",
                "} else {",
                "Box(",
                "modifier.clearAndSetSemantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate },",
                "contentAlignment = Alignment.Center,",
                ") {",
                "CircularProgressIndicator(",
                "progress = { rest },",
                "strokeWidth = strokeWidth,",
                "trackColor = Color.Transparent,",
                "strokeCap = StrokeCap.Butt,",
                "gapSize = 0.dp,",
                ")",
                "}",
            ),
            "LoadingRing changed shape. Five things are pinned here, and the first two hurt " +
                "silently — nothing crashes, the ring is just wrong on all fifteen screens:\n" +
                "  (a) the SIGNATURE and its default values. `strokeWidth` defaults to " +
                "ProgressIndicatorDefaults.CircularStrokeWidth, which is what the fourteen callers " +
                "that pass no stroke of their own get, in BOTH branches, animations on or off. " +
                "Lower it (say to 1.dp) and every one of those rings thins from a 4 dp ring to a " +
                "hairline, on every device;\n" +
                "  (b) it reads the setting and routes through loadingRingProgress;\n" +
                "  (c) Material 3's animated indicator is composed on the null answer ALONE;\n" +
                "  (d) the RESTING ring sits inside a Box that carries the caller's modifier and " +
                "clearAndSetSemantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }. " +
                "Without it, the determinate overload publishes its own ProgressBarRangeInfo(1f, " +
                "0f..1f) and a screen reader announces \"100 %\" over a load that has not finished " +
                "— on all fifteen screens, and only to the people who turned animations off, which " +
                "is an accessibility recommendation, TalkBack included. The indicator must stay a " +
                "DESCENDANT of that Box and must not be handed a modifier of its own: which of a " +
                "sibling semantics{} and Material 3's internal progressSemantics() wins cannot be " +
                "run, nor proven, in this module;\n" +
                "  (e) the resting ring keeps no track, no 4 dp gap and no round caps, or it is a " +
                "circle with a notch bitten out of it.",
        )
    }

    // -- 3. the reader's TWO indicators ------------------------------------------------------------

    /**
     * One tap on a message shows these two in turn: the whole-screen one while the message is
     */
    @Test fun `the reader's fetch indicator is the app's ring`() {
        assertBlock(
            MESSAGE_SCREEN,
            "is MessageState.Loading ->",
            listOf(
                "is MessageState.Loading -> LoadingRing(Modifier.align(Alignment.Center))",
                "is MessageState.Error -> Column(",
            ),
            "the reader's fetch spinner is not LoadingRing any more: with the system animations " +
                "off it is back to a fragment of arc that no longer reads as loading (#63).",
        )
    }

    @Test fun `the reader's body-layout indicator is the app's ring, at its own size`() {
        assertBlock(
            MESSAGE_SCREEN,
            "if (full != null && !bodyReady && spinnerDue) {",
            listOf(
                "if (full != null && !bodyReady && spinnerDue) {",
                "Box(",
                "Modifier.fillMaxWidth().heightIn(min = 80.dp).padding(24.dp).align(Alignment.Center),",
                "contentAlignment = Alignment.Center,",
                ") {",
                "LoadingRing(Modifier.size(24.dp), strokeWidth = 2.dp)",
                "}",
            ),
            "the body-layout spinner changed. It must be LoadingRing at 24 dp with a 2 dp stroke " +
                "(its size and stroke are its own, not the default 40 dp), and it must still be " +
                "gated on `full != null && !bodyReady && spinnerDue`: that 500 ms delay is what " +
                "keeps cached mail from flashing a spinner it does not need.",
        )
    }

    // -- 4. the fourteen other converted sites -------------------------------------------------------

    /**
     * SOURCE LINT, and the widest one: twenty sites now draw a [LoadingRing] instead of a raw
     */
    @Test fun `every converted loading indicator is still the app's ring`() {
        val damaged = RING_SITES.mapNotNull { site ->
            when (val n = codeLines(site.path).count { it == site.line }) {
                1 -> null
                0 -> "${site.what} — the line is GONE from ${site.path}: `${site.line}`"
                else -> "${site.what} — the line appears $n times in ${site.path}, so this entry no " +
                    "longer identifies one site: `${site.line}`"
            }
        }
        assertEquals(
            "a converted loading indicator no longer reads as LoadingRing. Each entry below names " +
                "the screen that went back to Material 3's raw indeterminate indicator: with the " +
                "system animations off, that indicator is left as a few pixels of arc on the " +
                "circumference and stops reading as \"loading\" at all — the very thing the " +
                "reporter filmed on the inbox refresh and the search screen (#63). Fixing the " +
                "screen is what closes this, not editing the expected line.",
            emptyList<String>(),
            damaged,
        )
    }

    // -- 5. the guard-rail: no full-size Material 3 indicator outside LoadingRing ---------------------

    /**
     * The four tests below EXECUTE [fullSizeIndicatorSites] on synthetic sources. The table in §4
     */
    @Test fun `a bare indicator is a full-size site`() {
        assertEquals(
            "an indicator with no size constraint at all draws Material 3's default 40 dp ring, " +
                "which is a full-size loading indicator and must go through LoadingRing.",
            listOf(2),
            fullSizeIndicatorSites(
                """
                Box(Modifier.fillMaxSize()) {
                    CircularProgressIndicator()
                }
                """.trimIndent(),
            ),
        )
    }

    @Test fun `a small indicator written on one line passes`() {
        assertEquals(
            "a 20 dp indicator sits in a button or a list row; those keep Material 3's arc on " +
                "purpose and the lint must not touch them.",
            emptyList<Int>(),
            fullSizeIndicatorSites(
                """
                Row {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    CircularProgressIndicator(Modifier.height(20.dp).width(20.dp))
                }
                """.trimIndent(),
            ),
        )
    }

    @Test fun `a small indicator spread over several lines passes, 24 dp included`() {
        assertEquals(
            "the size constraint is read from the WHOLE argument list, balanced from the opening " +
                "parenthesis — most of the small indicators in the app are written over four or " +
                "five lines, and reading only the line the call starts on would flag every one of " +
                "them (the size below sits on none of them). 24 dp is the boundary and is small.",
            emptyList<Int>(),
            fullSizeIndicatorSites(
                """
                item {
                    CircularProgressIndicator(
                        Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                """.trimIndent(),
            ),
        )
    }

    @Test fun `an indicator at the old 28 dp boundary is a full-size site now`() {
        assertEquals(
            "28 dp used to pass, because the inbox's paging-append footer was raw at that size. " +
                "That footer draws a LoadingRing now and the ceiling came down to 24 with it: a " +
                "28 dp indicator written by hand today is a full-size site and must be flagged. " +
                "If this one is green, the ceiling never moved and the footer could go back to a " +
                "raw Material 3 arc unnoticed.",
            listOf(2),
            fullSizeIndicatorSites(
                """
                item {
                    CircularProgressIndicator(
                        Modifier.size(28.dp),
                        strokeWidth = 2.dp,
                    )
                }
                """.trimIndent(),
            ),
        )
    }

    @Test fun `an indicator above the boundary is a full-size site`() {
        assertEquals(
            "40 dp is a centred, full-size loading ring however it is written; the size being " +
                "spelled out does not make it a small one.",
            listOf(2),
            fullSizeIndicatorSites(
                """
                Box(Modifier.fillMaxSize()) {
                    CircularProgressIndicator(
                        Modifier.size(40.dp),
                        strokeWidth = 4.dp,
                    )
                }
                """.trimIndent(),
            ),
        )
    }

    /**
     * SOURCE LINT, run over the whole of `app/src/main/kotlin/`. §4 pins the sites we know; this
     */
    @Test fun `no screen draws a full-size Material 3 indicator of its own`() {
        val root = repoRoot()
        val offenders = File(root, MAIN_SOURCES).walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .map { it to it.relativeTo(root).path.replace(File.separatorChar, '/') }
            .filter { (_, rel) -> rel != LOADING_RING }
            .sortedBy { (_, rel) -> rel }
            .flatMap { (file, rel) ->
                fullSizeIndicatorSites(file.readText()).map { line ->
                    "$rel:$line — full-size CircularProgressIndicator; draw it with LoadingRing()"
                }
            }
            .toList()
        assertEquals(
            "a full-size Material 3 indicator is drawn outside LoadingRing. Each line below is a " +
                "screen that, with the system animations off, shows a few pixels of arc on the " +
                "circumference instead of anything that reads as \"loading\" (#63). Replace the " +
                "call with LoadingRing(...), keeping the modifier as it is — LoadingRing carries " +
                "it on its Box. If the indicator really is a small one inside a button or a list " +
                "row, give it its size literally (Modifier.size(N.dp), .width(N.dp) or " +
                ".height(N.dp), N ≤ $MAX_SMALL_DP) and it stops being a full-size site. Only " +
                "$LOADING_RING may hold a bare one.",
            emptyList<String>(),
            offenders,
        )
    }

    // -- helpers ------------------------------------------------------------------------------------

    /**
     * Compares [expected] against the same number of consecutive CODE lines of [path], starting at
     */
    private fun assertBlock(path: String, prefix: String, expected: List<String>, why: String) {
        val code = codeLines(path)
        val starts = code.indices.filter { code[it].startsWith(prefix) }
        assertEquals("$why\n(expected exactly one code line of $path to start with `$prefix`)", 1, starts.size)
        val found = code.subList(starts[0], minOf(starts[0] + expected.size, code.size))
        assertEquals(why, expected, found)
    }

    /** The file's lines, trimmed, without comments or blanks — a rule must not be met by prose. */
    private fun codeLines(path: String): List<String> =
        File(repoRoot(), path)
            // Not a bare readLines(): a pinned screen that gets renamed or deleted would die on a
            // FileNotFoundException naming neither the site nor what to do about it, and the whole
            // point of the lints below is the sentence they fail with.
            .also { require(it.isFile) { "$path is not in the tree any more. A screen was renamed " +
                "or removed: point the entry in this file at its new path, or drop it if the " +
                "loading indicator went away with the screen." } }
            .readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }

    private fun repoRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, LOADING_RING).isFile }
            ?: error("cannot locate the repo root from ${File("").absolutePath}")

    /** One converted spinner: the screen it is on, and the whole source line that draws it. */
    private class RingSite(val path: String, val what: String, val line: String)

    private companion object {
        const val MAIN_SOURCES = "app/src/main/kotlin"
        const val LOADING_RING = "app/src/main/kotlin/app/sterna/ui/components/LoadingRing.kt"
        const val MESSAGE_SCREEN = "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt"
        const val INBOX_SCREEN = "app/src/main/kotlin/app/sterna/ui/inbox/InboxScreen.kt"
        const val SEARCH_SCREEN = "app/src/main/kotlin/app/sterna/ui/search/SearchScreen.kt"
        const val FILTERS_SCREEN = "app/src/main/kotlin/app/sterna/ui/settings/FiltersScreen.kt"
        const val SETTINGS_SCREEN = "app/src/main/kotlin/app/sterna/ui/settings/SettingsScreen.kt"
        const val CONNECT_SCREEN = "app/src/main/kotlin/app/sterna/ui/connect/ConnectScreen.kt"
        const val COMPOSE_SCREEN = "app/src/main/kotlin/app/sterna/ui/compose/ComposeScreen.kt"

        /**
         * Where the line is drawn between the centred ring the reporter filmed and the small
         */
        const val MAX_SMALL_DP = 24.0

        private const val CALL = "CircularProgressIndicator("

        /** `Modifier.size(20.dp)`, `.width(20.dp)`, `.height(20.dp)` — a size written out in dp. */
        private val SIZE_CONSTRAINT = Regex("""\.(?:size|width|height)\(\s*(\d+(?:\.\d+)?)\s*\.dp\s*\)""")

        /**
         * THE RULE, as a pure function: the 1-based line numbers of every full-size
         */
        fun fullSizeIndicatorSites(source: String): List<Int> {
            val code = codeMask(source)
            val sites = mutableListOf<Int>()
            var at = code.indexOf(CALL)
            while (at >= 0) {
                val before = if (at == 0) ' ' else code[at - 1]
                if (!before.isLetterOrDigit() && before != '_') {
                    val sizes = SIZE_CONSTRAINT
                        .findAll(argumentsAt(code, at + CALL.length - 1))
                        .map { it.groupValues[1].toDouble() }
                        .toList()
                    if (sizes.isEmpty() || sizes.any { it > MAX_SMALL_DP }) {
                        sites += code.take(at).count { it == '\n' } + 1
                    }
                }
                at = code.indexOf(CALL, at + 1)
            }
            return sites
        }

        /** The text between the `(` at [open] and its matching `)`. */
        private fun argumentsAt(code: String, open: Int): String {
            var depth = 0
            for (i in open until code.length) {
                when (code[i]) {
                    '(' -> depth++
                    ')' -> if (--depth == 0) return code.substring(open + 1, i)
                }
            }
            return code.substring(open + 1)
        }

        /**
         * [source] with comments, strings and char literals replaced by spaces, line breaks kept so
         * line numbers still hold.
         */
        private fun codeMask(source: String): String {
            val out = StringBuilder(source)
            val n = source.length
            var i = 0
            while (i < n) {
                val opener: Int
                val closer: String
                when {
                    source.startsWith("//", i) -> { opener = 2; closer = "\n" }
                    source.startsWith("/*", i) -> { opener = 2; closer = "*/" }
                    source.startsWith("\"\"\"", i) -> { opener = 3; closer = "\"\"\"" }
                    source[i] == '"' -> { opener = 1; closer = "\"" }
                    source[i] == '\'' -> { opener = 1; closer = "'" }
                    else -> { i++; continue }
                }
                var j = i + opener
                while (j < n) {
                    if (opener == 1 && source[j] == '\\') { j += 2; continue }
                    if (source.startsWith(closer, j)) break
                    j++
                }
                val end = minOf(n, if (j >= n) n else j + if (closer == "\n") 0 else closer.length)
                while (i < end) {
                    if (source[i] != '\n') out[i] = ' '
                    i++
                }
            }
            return out.toString()
        }

        /**
         * The fourteen converted sites nothing else holds. The six left out are held elsewhere and
         */
        val RING_SITES = listOf(
            RingSite(
                INBOX_SCREEN,
                "the inbox's in-list search, while the query runs",
                "SearchDisplay.SPINNER -> LoadingRing(Modifier.align(Alignment.Center))",
            ),
            RingSite(
                INBOX_SCREEN,
                "the inbox's centred refresh indicator (a paging refresh, the one on the " +
                    "reporter's screenshot)",
                // The condition gained `|| staleRows` (the stale-rows guard, RowsGuard.kt): the same
                // ring, at the same place, now also REACHED when Paging is still presenting the
                "refreshLoading || staleRows -> if (ringShowing) LoadingRing(Modifier.align(Alignment.Center)) else Unit",
            ),
            RingSite(
                INBOX_SCREEN,
                "the inbox's paging-append footer, while the server page loads",
                "LoadingRing(Modifier.size(28.dp), strokeWidth = 2.dp)",
            ),
            RingSite(
                SEARCH_SCREEN,
                "the search screen, while the search runs",
                "is SearchState.Searching -> LoadingRing(Modifier.align(Alignment.Center))",
            ),
            RingSite(
                SEARCH_SCREEN,
                "the search screen's SPINNER result display",
                "SearchDisplay.SPINNER -> LoadingRing(Modifier.align(Alignment.Center))",
            ),
            RingSite(
                FILTERS_SCREEN,
                "the server-filters screen, while the rules load",
                "state.loading -> LoadingRing(Modifier.align(Alignment.Center))",
            ),
            RingSite(
                SETTINGS_SCREEN,
                "the vacation-responder screen, while the setting loads",
                "state.loading -> LoadingRing(Modifier.align(Alignment.Center))",
            ),
            RingSite(
                MESSAGE_SCREEN,
                "the reader's pager, on a page whose message is not paged in yet",
                "Box(Modifier.fillMaxSize(), Alignment.Center) { LoadingRing() }",
            ),
            RingSite(
                MESSAGE_SCREEN,
                "the reader's whole-screen placeholder (message not resolved yet)",
                "LoadingRing()",
            ),
            RingSite(
                MESSAGE_SCREEN,
                "the reader's \"all headers\" sheet, while the headers load",
                ") { LoadingRing() }",
            ),
            RingSite(
                CONNECT_SCREEN,
                "the credentials step, while the browser hand-over is running",
                "CredentialsPane.WAITING -> LoadingRing()",
            ),
            RingSite(
                CONNECT_SCREEN,
                "the connect walk's status line, while the server is being probed",
                "StatusLine.Working -> LoadingRing()",
            ),
            RingSite(
                COMPOSE_SCREEN,
                "the composer, while a reopened draft is being read back",
                "DraftReopenView.Waiting -> LoadingRing(Modifier.align(Alignment.Center))",
            ),
            RingSite(
                COMPOSE_SCREEN,
                "the composer, while the message is being sent",
                "if (sending) LoadingRing(Modifier.padding(horizontal = 16.dp))",
            ),
        )
    }
}
