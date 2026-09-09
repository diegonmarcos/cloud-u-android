package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — it reads the sources as text and proves nothing about what
 */
class PagerFlingWiringTest {

    @Test fun `the reader's pager is mounted with the travel observer and the gated fling`() {
        assertEquals(
            "MessageScreen's HorizontalPager must be given BOTH " +
                "modifier = Modifier.fillMaxSize().then(pagerTravel.modifier) AND " +
                "flingBehavior = rememberPageFlingBehavior(pagerState, pagerTravel). Lose the " +
                "flingBehavior and the pager takes the library's own rule, which asks for speed " +
                "and nothing else (MinFlingVelocityDp = 400 dp/s, and above it the snap is " +
                "NextItem whatever the offset) — the brief nudge of the 2026-08-18 report changes " +
                "message again. Lose the .then(pagerTravel.modifier) and nothing ever records a " +
                "finger travel: the guard reads 0 px forever and NO swipe can change message at " +
                "all, which is the failure the bench measured on the first cut of this fix. Both " +
                "leave every executing test green. The other arguments are pinned with them " +
                "because they carry #92 (key by account+id) and the neighbouring page warm-up; if " +
                "this failure is a deliberate rewrite, update the expected text.",
            "HorizontalPager( state = pagerState, " +
                "modifier = Modifier.fillMaxSize().then(pagerTravel.modifier), " +
                "key = { i -> entryAt(i)?.let(::pagerKey) ?: \"page-\$i\" }, " +
                "beyondViewportPageCount = 1, " +
                "flingBehavior = rememberPageFlingBehavior(pagerState, pagerTravel), )",
            call("HorizontalPager", code(MESSAGE_SCREEN), MESSAGE_SCREEN_PATH),
        )
    }

    @Test fun `the fling behaviour is built on the library's defaults, with the floor in pixels`() {
        assertEquals(
            "rememberPageFlingBehavior, whole. Two silent edits live in these few lines:\n" +
                " · the delegate must be PagerDefaults.flingBehavior(state = pagerState) and " +
                "NOTHING else — every default left alone, snapPositionalThreshold included. Pass " +
                "snapPositionalThreshold = 0f and the SLOW path, the one this fix drops every " +
                "sub-floor gesture into, commits the page at any non-zero offset: the 10-20 dp " +
                "nudge of the report is back through the other door, and through the one constant " +
                "this branch was told not to touch.\n" +
                " · the floor must be converted through LocalDensity, here and only here. It is " +
                "the only place the constant becomes pixels and nothing executing can see it — " +
                "every test supplies its own floor already converted. Write 25f, or divide instead " +
                "of multiplying, and the floor shipped on a 2.0 bench is 12.5 dp, under the very " +
                "nudge this is meant to stop, with a green suite behind it.\n" +
                "If this failure is a deliberate rewrite, re-read those two points and update the " +
                "expected text.",
            "internal fun rememberPageFlingBehavior( pagerState: PagerState, " +
                "travel: PagerTouchTravel, ): TargetedFlingBehavior { " +
                "val delegate = PagerDefaults.flingBehavior(state = pagerState) " +
                "val minDistancePx = with(LocalDensity.current) { MIN_FLING_DISTANCE_DP.dp.toPx() } " +
                "return remember(delegate, travel, minDistancePx) { " +
                "DistanceGatedFlingBehavior(delegate, travel, minDistancePx) } }",
            block(
                "internal fun rememberPageFlingBehavior( pagerState: PagerState, " +
                    "travel: PagerTouchTravel, ): TargetedFlingBehavior {",
                code(PAGER_FLING),
                PAGER_FLING_PATH,
            ),
        )
    }

    @Test fun `the filtered velocity is the one the delegate is flung with, line for line`() {
        assertEquals(
            "DistanceGatedFlingBehavior.performFling, whole — the body no JVM test can execute:\n" +
                " · it asks flingVelocityForTravel with the FINGER's travel (travel.travelPx, " +
                "recorded by PagerTouchTravel), the converted floor and the velocity it was " +
                "handed. Reading the pager's own offset here instead — " +
                "pagerState.currentPageOffsetFraction × pageSize — is what the bench pass failed: " +
                "that quantity is the finger's travel minus the touch slop and minus whatever the " +
                "body WebView's interop handle kept, 15 to 25 dp short on a 360 dp bench, so a " +
                "deliberate 40 dp flick changed nothing, 0 times out of 4. All the arguments are " +
                "Floats, so 0f as minDistancePx compiles and reads fine and the decision function " +
                "answers perfectly to nonsense.\n" +
                " · it then flings the delegate with `allowed`, and with nothing else. This is the " +
                "line that CARRIES the decision, and it is the cheapest place to make the whole " +
                "fix inert: `performFling(if (allowed == 0f) initialVelocity else allowed, …)` " +
                "keeps allowed computed and used, compiles without a warning, and hands the " +
                "library the raw velocity in exactly the case the floor exists for — the 10-20 dp " +
                "nudge turns the page again, every time.\n" +
                " · onRemainingDistanceUpdated is passed straight through: dropping it would take " +
                "the single-argument overload and the pager would lose the distance callback it " +
                "animates against.\n" +
                "If this failure is a deliberate rewrite, re-read those three points and update " +
                "the expected text.",
            "override suspend fun ScrollScope.performFling( initialVelocity: Float, " +
                "onRemainingDistanceUpdated: (Float) -> Unit, ): Float { " +
                "val allowed = flingVelocityForTravel( travelPx = travel.travelPx, " +
                "minDistancePx = minDistancePx, velocityPxPerSec = initialVelocity, ) " +
                "return with(delegate) { performFling(allowed, onRemainingDistanceUpdated) } }",
            block(
                "override suspend fun ScrollScope.performFling( initialVelocity: Float, " +
                    "onRemainingDistanceUpdated: (Float) -> Unit, ): Float {",
                code(PAGER_FLING),
                PAGER_FLING_PATH,
            ),
        )
    }

    @Test fun `the travel holder is pinned whole, joining lines included`() {
        assertEquals(
            "PagerTouchTravel, WHOLE — advanceTravel is executed by PagerFlingDistanceTest, but " +
                "neither what it is fed nor what is handed back to the guard is reachable by any " +
                "JVM test:\n" +
                " · val travelPx: Float get() = state.travelPx. Write state.downX instead — also " +
                "a Float, no warning, whole suite still green — and the guard receives the " +
                "ABSOLUTE x of the touch down, 0 to 720 px on a 360 dp bench, which clears the " +
                "50 px floor nearly everywhere: every flick changes message and the 2026-08-18 " +
                "report is back entire. This line is the reason the pin is the class and not the " +
                "pointer loop.\n" +
                " · private var state = PointerTravel(), a plain var on purpose: a mutableStateOf " +
                "here would recompose the reader on every pointer move, and the value is read " +
                "exactly once, at fling time.\n" +
                " · change.position.x, never .y. Feed it the vertical and the travel of a " +
                "horizontal swipe is about zero, so no flick ever changes message, while every " +
                "advanceTravel test stays green.\n" +
                " · isPress = event.type == PointerEventType.Press. That is what clears the travel " +
                "at the start of a gesture, and it must come from the EVENT: keyed on our own " +
                "fingerDown flag instead, a gesture cancelled by the shade or the system back " +
                "gesture leaves the flag set and the NEXT gesture is measured from the OLD origin " +
                "— an arbitrarily long travel, so the 10 dp nudge turns the page again.\n" +
                " · event.changes.any { it.pressed }, never its negation and never a constant: it " +
                "is what tells a finger still down from the lift that writes the final travel.\n" +
                " · PointerEventPass.Initial, so the position is read BEFORE the pager's own drag " +
                "handling and no touch slop is missing from it. Main or Final would put us behind " +
                "the very consumption this fix exists to see past.\n" +
                " · NO change.consume(), anywhere in this class. Consuming on the Initial pass " +
                "takes the gesture from the pager, from the body WebView's AndroidView interop " +
                "handle and from everything #152 / #97 / #10 settled about who owns a drag; it " +
                "would compile, and it would break scrolling a message body sideways. The pin is " +
                "an equality precisely so an ADDED line fails it.\n" +
                "If this failure is a deliberate rewrite, re-read those points and update the " +
                "expected text.",
            "internal class PagerTouchTravel { private var state = PointerTravel() " +
                "val travelPx: Float get() = state.travelPx " +
                "val modifier: Modifier = Modifier.pointerInput(Unit) { awaitPointerEventScope { " +
                "while (true) { val event = awaitPointerEvent(PointerEventPass.Initial) " +
                "val change = event.changes.firstOrNull() ?: continue " +
                "state = advanceTravel( previous = state, " +
                "isPress = event.type == PointerEventType.Press, " +
                "anyPressed = event.changes.any { it.pressed }, x = change.position.x, ) } } } }",
            block("internal class PagerTouchTravel {", code(PAGER_FLING), PAGER_FLING_PATH),
        )
    }

    @Test fun `the travel holder survives recomposition`() {
        assertEquals(
            "MessageScreen must hold the PagerTouchTravel in a remember. Drop it — " +
                "val pagerTravel = PagerTouchTravel() — and every recomposition of the reader " +
                "hands out a NEW holder: the observer keeps writing into the one the previous " +
                "composition installed, the guard reads 0 px from the fresh one for the life of " +
                "the screen, and NO swipe changes message at all. That is the failure the bench " +
                "measured on the first cut of this fix, and it compiles, reads fine and leaves " +
                "every executing test green. Pinned as a whole line, not searched for: a " +
                "`contains` would not see a key added to the remember either.",
            "val pagerTravel = remember { PagerTouchTravel() }",
            line("PagerTouchTravel()", code(MESSAGE_SCREEN), MESSAGE_SCREEN_PATH),
        )
    }

    /** The `header { … }` block of [source], braces balanced, whitespace runs collapsed — in
     *  [source] as well as in [header], so a header spread over several lines can be named here on
     *  one. Comments are already gone (see [code]), so no brace can hide in one. */
    private fun block(header: String, source: String, path: String): String {
        val flat = source.replace(Regex("""\s+"""), " ")
        val start = flat.indexOf(header)
        assertTrue("block header not found in $path: $header", start >= 0)
        var depth = 0
        var i = start + header.length - 1 // on the header's own opening brace
        while (i < flat.length) {
            when (flat[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return flat.substring(start, i + 1)
                }
            }
            i++
        }
        error("unbalanced braces after: $header")
    }

    /** The whole `name(...)` CALL in [source] — the declaration `fun name(` is skipped — with the
     *  parentheses balanced and whitespace runs collapsed. */
    private fun call(name: String, source: String, path: String): String {
        val sites = Regex("""(?<!fun )\b${Regex.escape(name)}\(""").findAll(source).map { it.range.first }.toList()
        assertEquals(
            "expected exactly one call site for $name( in $path (the declaration aside), found " +
                "${sites.size} — this lint pins THE call, so a second one must be looked at",
            1,
            sites.size,
        )
        val start = sites.single()
        var depth = 0
        var i = start + name.length
        while (i < source.length) {
            when (source[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) {
                        return source.substring(start, i + 1).replace(Regex("""\s+"""), " ")
                    }
                }
            }
            i++
        }
        error("unbalanced parentheses after $name( in $path")
    }

    /** The one whole LINE of [source] that contains [marker], whitespace runs collapsed. Asserts
     *  there is exactly one, the way [call] asserts a single call site: a second occurrence means
     *  this pin no longer names what it thinks it names. */
    private fun line(marker: String, source: String, path: String): String {
        val hits = source.lines().filter { it.contains(marker) }
        assertEquals(
            "expected exactly one line containing \"$marker\" in $path, found ${hits.size} — this " +
                "lint pins THE line, so a second one must be looked at",
            1,
            hits.size,
        )
        return hits.single().replace(Regex("""\s+"""), " ").trim()
    }

    /** [file]'s code as one string, comments cut — the comments here name the very expressions
     *  these rules are about. */
    private fun code(file: File): String {
        assertTrue("source not found where this lint expects it: $file", file.isFile)
        return file.readLines().mapNotNull { line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) null
            else withoutTrailingComment(line).takeIf { it.isNotBlank() }
        }.joinToString("\n")
    }

    /** [line] up to its first `//` outside a double-quoted string; `\` escapes the next character. */
    private fun withoutTrailingComment(line: String): String {
        var inString = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inString && c == '\\' -> i++
                c == '"' -> inString = !inString
                !inString && c == '/' && line.getOrNull(i + 1) == '/' -> return line.substring(0, i).trimEnd()
            }
            i++
        }
        return line.trimEnd()
    }

    companion object {
        private const val MESSAGE_SCREEN_PATH =
            "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt"

        private const val PAGER_FLING_PATH =
            "app/src/main/kotlin/app/sterna/ui/message/PagerFlingDistance.kt"

        /** Repo root, walked up from the module's working directory. */
        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, MESSAGE_SCREEN_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        private val MESSAGE_SCREEN: File by lazy { File(root, MESSAGE_SCREEN_PATH) }
        private val PAGER_FLING: File by lazy { File(root, PAGER_FLING_PATH) }
    }
}
