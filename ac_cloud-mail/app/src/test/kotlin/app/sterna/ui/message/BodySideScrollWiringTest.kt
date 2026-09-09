package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — it reads `MessageScreen.kt` as text and proves nothing about
 */
class BodySideScrollWiringTest {

    @Test fun `the touch handler hands the decision signed deltas and both travel answers`() {
        assertEquals(
            "MessageScreen.BodyWebView.onTouchEvent must call bodyDragOwner with the SIGNED deltas " +
                "and with the two courses passed through bodyCanTravel — the course behind as " +
                "canScrollBack, the one ahead as canScrollForward, both against the same touchSlop. " +
                "Not because this door rescues a sideways gesture — it is never reached — but " +
                "because one rule must serve both doors: two notions of \"has travel\" in one file " +
                "is how the 3px defect comes back through whichever is looked at less. " +
                "Swapping the two answers, or wrapping a delta in abs(), leaves every test in the " +
                "suite green and puts Codeberg #152 straight back.",
            "bodyDragOwner( dx = event.x - downX, dy = event.y - downY, slop = touchSlop.toFloat(), " +
                "canScrollBack = bodyCanTravel(travelBack, touchSlop), " +
                "canScrollForward = bodyCanTravel(travelForward, touchSlop), )",
            call("bodyDragOwner", code(MESSAGE_SCREEN)),
        )
    }

    @Test fun `the threshold is the view's own touch slop, taken whole`() {
        // THE value behind the whole fix, and the one thing the pins above cannot see: they hold the
        // NAME `touchSlop`, never what it is worth. `ViewConfiguration.get(context).scaledTouchSlop / 8`
        val declarations = code(MESSAGE_SCREEN).lines()
            .map { it.trim() }
            .filter { it.startsWith("private val touchSlop") }
        assertEquals(
            "the drag threshold must be the view's OWN ViewConfiguration.get(context).scaledTouchSlop, " +
                "taken whole — not divided, not scaled, not a literal, and declared exactly once. " +
                "Divide it and minTravel becomes a couple of pixels: the 3px table of 2026-08-12 " +
                "claims the gesture again, swipe-between-messages dies on a body that fits, and the " +
                "#97 axis calibration moves with it. Every test in this suite supplies its own slop, " +
                "so nothing else in the suite would go red.",
            listOf("private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop"),
            declarations,
        )
    }

    @Test fun `both branches measure the two sideways courses the same way`() {
        // The measurement, not the decision: ACTION_DOWN and ACTION_MOVE must each derive the two
        // courses from offset / range - extent - offset. Handing bodyTakesGestureAtDown a raw
        val flat = code(MESSAGE_SCREEN).replace(Regex("""\s+"""), " ")
        assertEquals(
            "ACTION_DOWN and ACTION_MOVE must BOTH read: $COURSES — the forward course is what is " +
                "LEFT ahead (range - extent - offset), never the range, and never the range minus " +
                "the extent alone. The 3px defect of 2026-08-12 is exactly a small number read " +
                "large.",
            2,
            Regex(Regex.escape(COURSES)).findAll(flat).count(),
        )
        assertEquals(
            "no THIRD place may measure a sideways course, and neither of the two may be written " +
                "any other way: a second formula is a second rule, and this file has been through " +
                "two fixes already for measuring the same thing twice.",
            2,
            Regex("""val travelForward""").findAll(flat).count(),
        )
    }

    @Test fun `the ACTION_DOWN claim gets both courses and the touch slop as its threshold`() {
        // Argument by argument, like the call above: the three arguments are of two types, so
        // swapping the courses compiles and reads fine, and passing 0 as minTravel compiles too —
        // and that one puts the 3px defect straight back while every executing test stays green,
        // because the decision function is only ever as good as the threshold it is handed.
        assertEquals(
            "MessageScreen.BodyWebView.onTouchEvent must ask bodyTakesGestureAtDown at ACTION_DOWN " +
                "with the two measured courses and minTravel = touchSlop — the view's own " +
                "ViewConfiguration.scaledTouchSlop, and NOT 0, NOT 1, NOT a constant of this file. " +
                "A 3px course claimed the gesture on 2026-08-12 and killed the swipe on a table " +
                "that fitted; the threshold is the fix.",
            "bodyTakesGestureAtDown( travelBack = travelBack, travelForward = travelForward, " +
                "minTravel = touchSlop, )",
            call("bodyTakesGestureAtDown", code(MESSAGE_SCREEN)),
        )
    }

    @Test fun `the whole touch handler is what it is meant to be, line for line`() {
        // EQUALITY, not `in`: a substring assertion is blind to every mutation that LENGTHENS the
        // line it pins. `axisDecided = true && owner == BodyDrag.CLAIM` still contains
        assertEquals(
            "MessageScreen.BodyWebView.onTouchEvent, whole, is the wiring no JVM test can execute. " +
                "Everything it holds is silent if edited:\n" +
                " · ACTION_DOWN resets the THREE fields. Drop `downX = event.x` and downX stays 0, " +
                "so dx is the raw abscissa — hundreds of positive px on the first ACTION_MOVE: every " +
                "drag, vertical included, reads as clearly horizontal to the right, and on a body " +
                "with no sideways travel the reader changes message on every scroll (#97 and #152 " +
                "at once). Nothing else in the suite sees it.\n" +
                " · ACTION_DOWN MEASURES the two sideways courses in pixels — offset behind, " +
                "range - extent - offset ahead — instead of asking canScrollHorizontally, whose " +
                "threshold is ONE pixel. A 900px table in a 914 CSS-px viewport reads " +
                "hRange=2403 hExtent=2400: 3px of border, on which the previous version claimed the " +
                "gesture and killed swipe-between-messages both ways (bench, 2026-08-12). Hand a " +
                "raw range as travelForward, or 0 as minTravel, and that defect is back with the " +
                "whole suite green.\n" +
                " · ACTION_DOWN then ASKS bodyTakesGestureAtDown and, only if it says yes, freezes " +
                "the verdict and claims. This is the whole of the second #152 fix: a sideways " +
                "ACTION_MOVE never reaches this view at all — the pager consumes it on the Main " +
                "pass and PointerInteropFilter hands the view an ACTION_CANCEL — so DOWN, the one " +
                "event dispatched on the Initial pass, is the only instant the body can speak. Drop " +
                "the claim and the axis decision below stays correct and unreachable, which is how " +
                "the first fix passed a green suite and changed nothing on two benches. Drop " +
                "`axisDecided = true` and the first MOVE re-arbitrates and can RELEASE what is " +
                "already claimed.\n" +
                " · the debug `down` line sits INSIDE `if (BuildConfig.DEBUG)` and OUTSIDE the " +
                "`if (takesIt)`: a DOWN that claimed nothing is a result, not a silence, and it is " +
                "the reading the previous bench pass could not produce.\n" +
                " · ACTION_POINTER_DOWN claims unconditionally and freezes the verdict. Without that " +
                "branch a second finger changes nothing: event.x is still pointer 0's, two fingers " +
                "spreading sideways make the first MOVE clearly horizontal, and pinching to zoom out " +
                "loses the message — the very gesture that creates the sideways travel #152 gave " +
                "back.\n" +
                " · the deltas stay SIGNED and the two travel answers keep their sides (the course " +
                "BEHIND, i.e. the offset, is canScrollBack). ACTION_MOVE passes them through " +
                "bodyCanTravel with the same touchSlop, NOT canScrollHorizontally — one rule for " +
                "both doors, since a second notion of \"has travel\" in this file is how the 3px " +
                "defect comes back through the door nobody is watching.\n" +
                " · the verdict is taken while !axisDecided and frozen by `axisDecided = true` on " +
                "ANY settled verdict — one verdict per gesture, RELEASE included.\n" +
                " · the claim goes through parent?., because the WebView is NOT the AndroidView's " +
                "root: a FrameLayout sits in between for the fade (Codeberg #10).\n" +
                " · the debug measurement sits INSIDE `if (BuildConfig.DEBUG)`, takes the deltas " +
                "again rather than hoisting them into locals — hoisting would empty the " +
                "argument-by-argument pin of the test above — and is HANDED the two courses the " +
                "verdict was taken on, rather than re-reading them: a log line that measures a " +
                "second time is not a record of the decision. Dropping the guard puts string " +
                "building on the hot touch path of every release build; moving the call below the " +
                "claim, or above `axisDecided = true`, changes what instant is measured.\n" +
                " · ACTION_UP, and ACTION_UP alone, tells the host the reader may have opened or " +
                "closed the <details> that folds a reply's quoted history: the document carries no " +
                "script and the View has no content-size callback, so her gesture ending is the " +
                "ONLY warning that the body's height is about to change. ACTION_CANCEL must NOT " +
                "call it — a cancelled gesture is one the pager took off us (swipe between " +
                "messages) and it activated nothing — and a body already at the top does not " +
                "scroll as it shrinks, so removing this line loses Reply/Forward for the life of " +
                "the page with every executing test green.\n" +
                "If this failure is a deliberate rewrite, re-read those ten points and update the " +
                "expected text.",
            listOf(
                "override fun onTouchEvent(event: MotionEvent): Boolean {",
                "when (event.actionMasked) {",
                "MotionEvent.ACTION_DOWN -> {",
                "downX = event.x; downY = event.y; axisDecided = false",
                COURSES,
                "val takesIt = bodyTakesGestureAtDown(",
                "travelBack = travelBack,",
                "travelForward = travelForward,",
                "minTravel = touchSlop,",
                ")",
                "if (BuildConfig.DEBUG) logDownVerdict(takesIt, travelBack, travelForward)",
                "if (takesIt) {",
                "axisDecided = true",
                "parent?.requestDisallowInterceptTouchEvent(true)",
                "}",
                "}",
                "MotionEvent.ACTION_POINTER_DOWN -> {",
                "axisDecided = true",
                "parent?.requestDisallowInterceptTouchEvent(true)",
                "}",
                "MotionEvent.ACTION_MOVE -> if (!axisDecided) {",
                COURSES,
                "val owner = bodyDragOwner(",
                "dx = event.x - downX,",
                "dy = event.y - downY,",
                "slop = touchSlop.toFloat(),",
                "canScrollBack = bodyCanTravel(travelBack, touchSlop),",
                "canScrollForward = bodyCanTravel(travelForward, touchSlop),",
                ")",
                "if (owner != BodyDrag.PENDING) {",
                "axisDecided = true",
                "if (BuildConfig.DEBUG) {",
                "logDragVerdict(",
                "owner, event.x - downX, event.y - downY, travelBack, travelForward,",
                ")",
                "}",
                "if (owner == BodyDrag.CLAIM) parent?.requestDisallowInterceptTouchEvent(true)",
                "}",
                "}",
                "MotionEvent.ACTION_UP -> onActivated?.invoke()",
                "}",
                "return super.onTouchEvent(event)",
                "}",
            ).joinToString(" "),
            block("override fun onTouchEvent(event: MotionEvent): Boolean {", code(MESSAGE_SCREEN)),
        )
    }

    @Test fun `nothing in this screen ever hands a claimed gesture back`() {
        val handBacks = Regex("""requestDisallowInterceptTouchEvent\((?!true\))[^)]*\)""")
            .findAll(code(MESSAGE_SCREEN)).map { it.value }.toList()
        assertEquals(
            "a claim in this file is FOREVER: nothing may call requestDisallowInterceptTouchEvent " +
                "with anything but true. A hand-back can sit far from the touch handler and undo it " +
                "from a distance — one line in onScrollChanged is enough, and a pinch or a sideways " +
                "scroll fires that callback by definition. The pager becomes free to intercept " +
                "MID-gesture, on both halves of this fix at once: the wide body of #152 is snatched " +
                "away while it is being dragged, the zoom of the two-finger branch likewise, and " +
                "since axisDecided is already true no re-arbitration can save it. Nothing else in " +
                "the suite would move.",
            emptyList<String>(),
            handBacks,
        )
    }

    // SWIPE_HORIZONTAL_DOMINANCE is deliberately NOT pinned here: BodyDragOwnerTest exercises the
    // 3:1 ratio itself (dx=30/dy=10 → CLAIM, dx=31/dy=10 → RELEASE), so lowering the constant turns
    // that test red on execution. A source assertion on it would add a blind sub-string and cover
    // nothing new.

    /** The `header { … }` block in [source], braces balanced, whitespace runs collapsed. Comments
     *  are already gone (see [code]), so no brace can hide in one. */
    private fun block(header: String, source: String): String {
        val start = source.indexOf(header)
        assertTrue("block header not found in MessageScreen.kt: $header", start >= 0)
        var depth = 0
        var i = start + header.length - 1 // on the header's own opening brace
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return source.substring(start, i + 1).replace(Regex("""\s+"""), " ")
                    }
                }
            }
            i++
        }
        error("unbalanced braces after: $header")
    }

    /** The whole `name(...)` CALL in [source] — the declaration `fun name(` is skipped — with the
     *  parentheses balanced and whitespace runs collapsed. */
    private fun call(name: String, source: String): String {
        val sites = Regex("""(?<!fun )\b${Regex.escape(name)}\(""").findAll(source).map { it.range.first }.toList()
        assertEquals(
            "expected exactly one call site for $name( in MessageScreen.kt (the declaration aside), " +
                "found ${sites.size} — this lint pins THE call, so a second one must be looked at",
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
        error("unbalanced parentheses after $name( in MessageScreen.kt")
    }

    /** [file]'s code as one string, comments cut — the comments here name the very expressions
     *  these rules are about. */
    private fun code(file: File): String = file.readLines().mapNotNull { line ->
        val trimmed = line.trimStart()
        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) null
        else withoutTrailingComment(line).takeIf { it.isNotBlank() }
    }.joinToString("\n")

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
        /** The one way the two sideways courses may be measured, in either branch of the handler:
         *  what is behind (the scroll offset) and what is left ahead (range - extent - offset). */
        private const val COURSES =
            "val travelBack = computeHorizontalScrollOffset() val travelForward = " +
                "computeHorizontalScrollRange() - computeHorizontalScrollExtent() - travelBack"

        private const val MESSAGE_SCREEN_PATH =
            "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt"

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
    }
}
