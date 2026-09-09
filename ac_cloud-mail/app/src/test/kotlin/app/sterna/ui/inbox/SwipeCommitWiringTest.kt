package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and the same disclaimer as
 */
class SwipeCommitWiringTest {

    @Test fun `the row asks the shared rule for its threshold at both sites, and nowhere else`() {
        val calls = callArguments(row(), "swipeCommitThresholdPx")
        assertEquals(
            "SwipeableEmailRow must call swipeCommitThresholdPx exactly twice — once in the drag " +
                "handler (what commits) and once in composition (what arms the reveal). Found " +
                "${calls.size}: ${calls.joinToString(" | ")}",
            2, calls.size,
        )
        for (call in calls) {
            assertTrue(
                "each call must pass the row's CURRENT measured width and the absolute distance " +
                    "SWIPE_COMMIT_DISTANCE_DP.dp.toPx(), and nothing else. A hard-coded px value " +
                    "here, or a width captured elsewhere, is the fraction rule coming back under a " +
                    "new name. Call was:\n$call",
                THRESHOLD_ARGS.matches(call.trim()),
            )
        }
    }

    @Test fun `the row hands commitSwipe the action it decided, in the direction it decided it`() {
        val calls = callArguments(row(), "commitSwipe")
        assertEquals(
            "SwipeableEmailRow must call commitSwipe exactly once, from the branch that has a " +
                "decision. Found ${calls.size}: ${calls.joinToString(" | ")}",
            1, calls.size,
        )
        assertTrue(
            "commitSwipe must receive the action the decision just chose and the direction it " +
                "was decided in. Passing rightAction/leftAction, or a literal, makes a swipe one " +
                "way run the other way's action — silent, and destructive when that action is " +
                "delete. Call was:\n${calls.first()}",
            COMMIT_SWIPE_ARGS.matches(flat(calls.first())),
        )
    }

    @Test fun `both sites take the decision with the same call, on the live offset and speed`() {
        val calls = callArguments(row(), "swipeCommitDirection").map { flat(it) }
        assertEquals(
            "SwipeableEmailRow must call swipeCommitDirection exactly twice — the commit and the " +
                "reveal. Found ${calls.size}: ${calls.joinToString(" | ")}",
            2, calls.size,
        )
        // Whole-match, argument by argument, and per site: the two now pass DIFFERENT expressions
        // for the same quantities (the handler has the release velocity and a PointerInputScope
        assertEquals(
            "exactly one call must be the DRAG HANDLER's: the release velocity read from the " +
                "tracker, the pointer scope's own slop, and the drag's completion flag. Calls " +
                "were:\n${calls.joinToString("\n\n")}",
            1, calls.count { DECISION_AT_RELEASE.matches(it) },
        )
        assertEquals(
            "exactly one call must be the REVEAL's: the published live velocity, the composition " +
                "slop, and dragCompleted = true (nothing is cancelled while the finger is still " +
                "down). A reveal that passed 0 for the speed would go back to lighting at the " +
                "distance while a flick fires the action off a neutral background. Calls " +
                "were:\n${calls.joinToString("\n\n")}",
            1, calls.count { DECISION_WHILE_DRAGGING.matches(it) },
        )
    }

    @Test fun `the reveal is armed by the decision itself, not by a second copy of it`() {
        val armed = flat(statement("armed"))
        assertTrue(
            "`armed` must be the shared decision itself, `!= 0`, and nothing more. Matched whole " +
                "— declaration to closing paren to comparison — not by presence: a rule that " +
                "merely LOOKED for the call would be blind to an extra clause tacked on the end, " +
                "and an extra clause is precisely how the reveal and the commit drifted apart the " +
                "first time. Statement was:\n$armed",
            ARMED.matches(armed),
        )
    }

    // -- the flick: measuring the speed, and forgetting it (Codeberg #125, second half) -----------

    @Test fun `the tracker is fed in both loops, by addPointerInputChange, and never by hand`() {
        val body = row()
        val feeds = Regex("""tracker\.addPointerInputChange\(""").findAll(body)
            .map { it.range.first }.toList()
        val lockStart = body.indexOf("val horizontal = awaitPointerEventScope")
        val dragStart = body.indexOf("horizontalDrag(")
        assertTrue("the row must still have a direction-lock phase and a horizontalDrag phase",
            lockStart in 0 until dragStart)
        assertEquals(
            "the tracker must be fed exactly three times: at the down, inside the DIRECTION-LOCK " +
                "loop, and inside horizontalDrag. Feeding only horizontalDrag is the whole trap of " +
                "this fix — the lock phase's travel is discarded, so a short flick is almost " +
                "entirely consumed by it and the tracker would see one or two decelerating " +
                "samples. Found ${feeds.size}.",
            3, feeds.size,
        )
        assertTrue("the first feed must be the down, before the direction-lock loop", feeds[0] < lockStart)
        assertTrue("the second feed must sit inside the direction-lock loop",
            feeds[1] in lockStart..dragStart)
        assertTrue("the third feed must sit inside horizontalDrag", feeds[2] > dragStart)
        assertTrue(
            "positions must never be added by hand: addPointerInputChange is what unfolds the " +
                "sub-frame `historical` samples and accumulates DELTAS, which is what makes the " +
                "measurement blind to the row moving under the finger and to the change being " +
                "consumed. addPosition( found in SwipeableEmailRow.",
            "addPosition(" !in body,
        )
    }

    @Test fun `forgetting the gesture clears the tracker AND the published speed`() {
        val body = row()
        val at = Regex("""fun forgetVelocity\(\)""").find(body)
        assertTrue("SwipeableEmailRow must declare forgetVelocity().", at != null)
        assertEquals(
            "forgetVelocity() must reset the tracker and zero BOTH published components, and do " +
                "nothing else. Reset only the tracker and the reveal keeps arming off a stale " +
                "speed; zero only the state and the previous gesture's samples leak into the next " +
                "one through the shared while(true) scope.",
            "tracker.resetTracking() swipeVelocityX = 0f swipeVelocityY = 0f",
            flat(balanced(body, at!!.range.last, '{', '}')),
        )
        assertEquals(
            "swipeVelocityX must be written in exactly two places: forgetVelocity() and the drag " +
                "loop. A third writer is a second copy of the rule about when the reveal may read " +
                "a speed.",
            2, Regex("""swipeVelocityX\s*=""").findAll(body).count(),
        )
        assertEquals(
            "same for swipeVelocityY.",
            2, Regex("""swipeVelocityY\s*=""").findAll(body).count(),
        )
    }

    @Test fun `every gesture starts from a tracker that remembers nothing`() {
        assertTrue(
            "the down must be followed by forgetVelocity() and only then fed to the tracker. The " +
                "pointerInput block's while(true) scope outlives every gesture: a new finger that " +
                "inherits the last one's samples — or the last one's published speed — arms the " +
                "reveal before it has moved, and can commit on a flick nobody made.",
            FRESH_AT_DOWN.containsMatchIn(flat(row())),
        )
        assertEquals(
            "forgetVelocity() must be called exactly four times: at the down, at each of the two " +
                "`continue`s, and at the release. A missing one is a gesture that inherits.",
            4, Regex("""(?<!fun )forgetVelocity\(\)""").findAll(row()).count(),
        )
    }

    @Test fun `every abandoned gesture forgets its speed before looping round`() {
        val body = row()
        assertEquals(
            "SwipeableEmailRow must have exactly two `continue`s — the drawer's edge strip (#30) " +
                "and the abandon-to-scroll of the direction lock (#97).",
            2, Regex("""\bcontinue\b""").findAll(body).count(),
        )
        assertEquals(
            "each `continue` must be immediately preceded by forgetVelocity(). The while(true) " +
                "scope is shared by every gesture the row ever sees: a loop taken without " +
                "forgetting carries the abandoned gesture's samples into the next swipe, which is " +
                "the classic defect of this construction.",
            2, Regex("""forgetVelocity\(\)\s*continue\b""").findAll(body).count(),
        )
    }

    @Test fun `the release speed is read once and forgotten before the row is animated`() {
        assertTrue(
            "the drag must end with `val release = tracker.calculateVelocity()` immediately " +
                "followed by forgetVelocity(). Order and adjacency both matter: the decision needs " +
                "the speed, and the reveal must be dark before the spring-back starts — the row " +
                "spends the next frames at a NON-ZERO offset with nobody touching it, and a stale " +
                "speed lights the reveal on the way home, an armed flash for an action that is not " +
                "going to happen.",
            RELEASE_THEN_FORGET.containsMatchIn(row()),
        )
    }

    @Test fun `the drag loop publishes the live speed to composition`() {
        assertTrue(
            "inside horizontalDrag the tracker must be fed, then calculateVelocity()'s result " +
                "published to swipeVelocityX/Y. Without it the reveal reads 0 for the whole " +
                "gesture and arms at the distance only, while a flick fires the action — the row " +
                "archived off a neutral background, invisible under the OLED-black theme.",
            PUBLISHES_LIVE_SPEED.containsMatchIn(flat(row())),
        )
    }

    @Test fun `a cancelled drag is a value, not something dropped on the floor`() {
        assertTrue(
            "horizontalDrag's Boolean must be bound (`val dragCompleted = ...`) and handed to the " +
                "decision. It answers false when another node took the pointer rather than the " +
                "finger lifting; dropped, a snatched gesture is judged as a release, carrying the " +
                "speed of the instant it was snatched.",
            HONOURS_CANCELLATION.containsMatchIn(flat(row())),
        )
    }

    @Test fun `the committed direction picks the action for that side, and no other`() {
        val mapping = whenBlock("committed")
        assertEquals(
            "the direction the rule returns must select the action of THAT side: +1 (rightwards) " +
                "the right action, -1 the left one, 0 none. Swapping the two arms delete where the " +
                "user swiped for archive, with the whole suite green — which is why the mapping is " +
                "pinned here whole. Block was:\n$mapping",
            "1 -> rightAction -1 -> leftAction else -> SwipeAction.NONE",
            mapping.trim().replace(Regex("\\s+"), " "),
        )
    }

    @Test fun `a release that commits nothing springs the row back and dispatches nothing`() {
        // Found as a surviving mutation: drop this guard and a plain short drag calls the swipe
        // handler with SwipeAction.NONE on every release, while the row stays where the finger
        // left it. The behaviour tests cannot see it — they answer "which direction", and the
        // answer there is a correct 0.
        assertTrue(
            "a committed direction of 0 must spring the row back and call nothing at all. Row " +
                "body did not match the expected shape.",
            SPRING_BACK.containsMatchIn(row()),
        )
    }

    @Test fun `the reveal's threshold is recomputed whenever the row is remeasured`() {
        val keys = Regex("""val commitThresholdPx = remember\(([^)]*)\)""").find(row())
        assertTrue(
            "in composition the threshold must be remembered, not recomputed every frame — but " +
                "remembered AGAINST the row's width. Declaration not found in SwipeableEmailRow.",
            keys != null,
        )
        assertEquals(
            "the keys must be the row's width and the density. Remembered against neither (or " +
                "against the density alone), the reveal keeps the threshold of the first " +
                "measurement for the row's whole life: resize the window and the colour arms at " +
                "one distance while the action fires at another — the exact drift this fix removes.",
            "rowWidth, density", keys!!.groupValues[1].trim(),
        )
    }

    @Test fun `the fraction rule is gone from the row`() {
        assertTrue(
            "SWIPE_COMMIT_FRACTION must no longer exist in InboxScreen: the commit distance is " +
                "absolute now, and a surviving fraction constant is either dead code or a second " +
                "copy of the rule waiting to be used. (PendingImportAccounts keeps its own, " +
                "deliberately out of scope.)",
            "SWIPE_COMMIT_FRACTION" !in code(INBOX_SCREEN),
        )
    }

    @Test fun `the drag block did not gain a key`() {
        val keys = callArguments(row(), "pointerInput")
        assertEquals(
            "SwipeableEmailRow must host exactly one pointerInput. Found ${keys.size}.",
            1, keys.size,
        )
        assertEquals(
            "the key list must stay these five. The threshold is deliberately computed INSIDE the " +
                "block: read from composition it would freeze at the block's first run — the dead " +
                "re-swipe of 6f4f1cc3, documented right above this block — and adding it as a key " +
                "instead restarts the gesture in progress every time the row is remeasured. " +
                "drawerCanOpen is the fifth since #103 volet 6 and is a LAYOUT CLASS, not a " +
                "measurement: it moves only when the window crosses 1 200 dp, so it restarts no " +
                "gesture anybody is in the middle of — and dropped from the list the block keeps " +
                "the value it was composed with, so a window dragged past 1 200 dp still hands " +
                "drags to a drawer that no longer opens. Keys " +
                "were:\n${keys.single()}",
            "gesturesEnabled, rightAction, leftAction, drawerBandPx, drawerCanOpen",
            keys.single().trim().replace(Regex("\\s+"), " "),
        )
    }

    @Test fun `the guard that hands a dead direction to the drawer is still there`() {
        // Codeberg #30, and the invariant that is invisible from any test but this one: the drawer
        // does not open because the row does something, it opens because the row does NOT consume.
        // "Simplifying" this into consuming and then doing nothing breaks the drawer without
        // breaking a single test.
        assertTrue(
            "the direction lock must still hand an actionless direction back unconsumed via " +
                "rowKeepsDrag(dx, rightAction, leftAction, drawerCanOpen) — the fourth argument " +
                "included, or the row hands drags to a permanent drawer that cannot take them " +
                "(#103 volet 6). DrawerGestureTest EXECUTES the rule; this pins that it is asked.",
            ROW_KEEPS_DRAG.containsMatchIn(row()),
        )
        assertTrue(
            "a drag starting in the drawer's edge strip must still be left alone (#30).",
            STARTS_IN_BAND.containsMatchIn(row()),
        )
    }

    @Test fun `a direction with no action still cannot move the row`() {
        // What makes the missing `!= NONE` guard in `armed` harmless: on a side with no action the
        // offset bound is 0, so the row does not move that way at all and the reveal never lights.
        // Undo this coupling and the reveal arms for an action that will never fire.
        assertTrue(
            "minOffset must stay 0 when leftAction is NONE. Row body was searched for it.",
            MIN_OFFSET.containsMatchIn(row()),
        )
        assertTrue(
            "maxOffset must stay 0 when rightAction is NONE.",
            MAX_OFFSET.containsMatchIn(row()),
        )
    }

    // -- reading the source ------------------------------------------------------------------------

    /** The body of `SwipeableEmailRow`, comments stripped. */
    private fun row(): String = body(INBOX_SCREEN, "SwipeableEmailRow")

    /** [text] with every run of whitespace squeezed to one space, so a wrapped call reads as one. */
    private fun flat(text: String): String = text.trim().replace(Regex("\\s+"), " ")

    /**
     * The whole statement declaring `val [name]` inside the row: from its line to the line where
     */
    private fun statement(name: String): String {
        val lines = row().lines()
        val declaration = Regex("""\bval\s+$name\b""")
        val starts = lines.withIndex().filter { declaration.containsMatchIn(it.value) }
        assertEquals(
            "SwipeableEmailRow must declare exactly one `val $name`. Found ${starts.size}.",
            1, starts.size,
        )
        val start = starts.single().index
        val out = mutableListOf<String>()
        var depth = 0
        for (i in start until lines.size) {
            val line = lines[i]
            out += line
            depth += line.count { it == '(' } - line.count { it == ')' }
            if (depth == 0) break
        }
        return out.joinToString("\n")
    }

    /** The arms of `when ([subject])` inside the row, braces balanced. */
    private fun whenBlock(subject: String): String {
        val text = row()
        val at = Regex("""\bwhen\s*\(\s*$subject\s*\)""").find(text)
        assertTrue("SwipeableEmailRow must dispatch on `when ($subject)`.", at != null)
        return balanced(text, at!!.range.last, '{', '}')
    }

    /**
     * The lines of [file] that are code, with their comments taken off — a line whose first
     */
    private fun codeLines(file: File): List<String> = file.readLines().mapNotNull { line ->
        val code = line.trimStart()
        if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) null
        else withoutTrailingComment(line).takeIf { it.isNotBlank() }
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

    /** [file]'s code as one string, so a call the formatter wraps over four lines reads as one. */
    private fun code(file: File): String = codeLines(file).joinToString("\n")

    /**
     * The declaration of `fun`/`val` [name] in [file] and its body, as text: everything up to the
     */
    private fun body(file: File, name: String): String {
        val lines = codeLines(file)
        val declaration = Regex("""\b(fun|val|var)\s+$name\b""")
        val start = lines.indexOfFirst { declaration.containsMatchIn(it) }
        check(start >= 0) { "${file.name} declares no '$name' — did it get renamed?" }
        val indent = lines[start].indentWidth()
        val out = mutableListOf<String>()
        var closed = false
        var depth = 0
        for (i in start until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            if (closed && i > start && line.indentWidth() <= indent) break
            out += line
            depth += line.count { it == '(' || it == '{' } - line.count { it == ')' || it == '}' }
            closed = depth == 0
        }
        return out.joinToString("\n")
    }

    private fun String.indentWidth() = length - trimStart().length

    /** The argument text of every call to [name] in [text], parentheses balanced. */
    private fun callArguments(text: String, name: String): List<String> =
        Regex("""\b${Regex.escape(name)}\(""").findAll(text)
            .map { balanced(text, it.range.last, '(', ')') }
            .toList()

    /** [text] from the first [open] at or after [from], up to the [close] that balances it. */
    private fun balanced(text: String, from: Int, open: Char, close: Char): String {
        val start = text.indexOf(open, from).let { if (it < 0) from else it + 1 }
        var depth = 1
        var i = start
        while (i < text.length && depth > 0) {
            when (text[i]) {
                open -> depth++
                close -> depth--
            }
            i++
        }
        return text.substring(start, (i - 1).coerceAtLeast(start)).trim()
    }

    companion object {
        /**
         * Both call sites, whole: the current width and the absolute distance, nothing else.
         */
        private val THRESHOLD_ARGS = Regex(
            """rowWidthPx\s*=\s*rowWidth\.toFloat\(\)\s*,\s*""" +
                """distancePx\s*=\s*(?:""" +
                """SWIPE_COMMIT_DISTANCE_DP\.dp\.toPx\(\)""" +
                """|with\(density\)\s*\{\s*SWIPE_COMMIT_DISTANCE_DP\.dp\.toPx\(\)\s*\}""" +
                """)\s*,?""",
            RegexOption.DOT_MATCHES_ALL,
        )

        /**
         * The dispatch: the action the decision just chose, in the direction it was decided in.
         */
        private val COMMIT_SWIPE_ARGS = Regex(
            """action, committed, width, motionOn, offsetX, lift, \{ currentOnSwipe\(it\) \},?""",
        )

        /** The drag handler, when the finger lifts: the tracker's release velocity. */
        private const val AT_RELEASE_ARGS =
            """offsetPx = offsetX\.value, """ +
                """thresholdPx = commitThresholdPx, """ +
                """velocityPxPerSec = release\.x, """ +
                """crossVelocityPxPerSec = release\.y, """ +
                """escapeVelocityPxPerSec = SWIPE_ESCAPE_VELOCITY_DP_PER_SEC\.dp\.toPx\(\), """ +
                """slopPx = slop, """ +
                """dragCompleted = dragCompleted,?"""

        /** Composition, while the finger is still down: the velocity published by the drag loop. */
        private const val WHILE_DRAGGING_ARGS =
            """offsetPx = offsetX\.value, """ +
                """thresholdPx = commitThresholdPx, """ +
                """velocityPxPerSec = swipeVelocityX, """ +
                """crossVelocityPxPerSec = swipeVelocityY, """ +
                """escapeVelocityPxPerSec = with\(density\) \{ """ +
                """SWIPE_ESCAPE_VELOCITY_DP_PER_SEC\.dp\.toPx\(\) \}, """ +
                """slopPx = viewConfiguration\.touchSlop, """ +
                """dragCompleted = true,?"""

        private val DECISION_AT_RELEASE = Regex(AT_RELEASE_ARGS)
        private val DECISION_WHILE_DRAGGING = Regex(WHILE_DRAGGING_ARGS)

        private val ARMED = Regex(
            """val armed = swipeCommitDirection\( $WHILE_DRAGGING_ARGS \) != 0""",
        )

        private val RELEASE_THEN_FORGET = Regex(
            """val release = tracker\.calculateVelocity\(\)\s*forgetVelocity\(\)""",
        )

        private val PUBLISHES_LIVE_SPEED = Regex(
            """tracker\.addPointerInputChange\(change\) """ +
                """val moving = tracker\.calculateVelocity\(\) """ +
                """swipeVelocityX = moving\.x """ +
                """swipeVelocityY = moving\.y""",
        )

        private val FRESH_AT_DOWN = Regex(
            """val pointerId = down\.id forgetVelocity\(\) """ +
                """tracker\.addPointerInputChange\(down\)""",
        )

        private val HONOURS_CANCELLATION = Regex(
            """val dragCompleted = awaitPointerEventScope \{ horizontalDrag\(pointerId\) \{""",
        )

        private val SPRING_BACK = Regex(
            """if \(action == SwipeAction\.NONE\) \{\s*launch \{ offsetX\.animateTo\(0f\) \}\s*\} else \{""",
        )

        private val ROW_KEEPS_DRAG = Regex(
            """rowKeepsDrag\(\s*dx\s*,\s*rightAction\s*,\s*leftAction\s*,\s*drawerCanOpen\s*,?\s*\)""",
        )

        private val STARTS_IN_BAND = Regex(
            """startsInDrawerBand\(\s*rowLeftPx \+ down\.position\.x\s*,\s*drawerBandPx\s*,?\s*\)""",
        )

        private val MIN_OFFSET = Regex(
            """val minOffset = if \(leftAction == SwipeAction\.NONE\) 0f else -rowWidth\.toFloat\(\)""",
        )

        private val MAX_OFFSET = Regex(
            """val maxOffset = if \(rightAction == SwipeAction\.NONE\) 0f else rowWidth\.toFloat\(\)""",
        )

        private const val INBOX_SCREEN_PATH =
            "app/src/main/kotlin/app/sterna/ui/inbox/InboxScreen.kt"

        /** Repo root, walked up from the module's working directory. */
        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, INBOX_SCREEN_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the source as text and needs a working directory inside the checkout",
                )
        }

        private val INBOX_SCREEN: File by lazy { File(root, INBOX_SCREEN_PATH) }
    }
}
