package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How far — or how FAST — a list row must be dragged before letting go runs its action
 */
class SwipeCommitTest {

    /** 72 dp at a density of ~2.625 (Pixel 7). */
    private val distancePx = 189f

    /** 120 dp/s at the same density: the speed a flick has to reach. */
    private val escapePx = 315f

    /** The platform's touch slop, 8 dp, at the same density. */
    private val slopPx = 21f

    /** A phone row: wide enough that 40% of it is far beyond the absolute distance. */
    private val wideRow = 1080f

    /** A narrow row (a split-screen or freeform window): 40% of it is SHORT of the distance. */
    private val narrowRow = 400f

    private fun threshold(rowWidthPx: Float) = swipeCommitThresholdPx(rowWidthPx, distancePx)

    /**
     * The decision under test, with the gesture at rest by default: no speed at all, and a drag that
     */
    private fun direction(
        offsetPx: Float,
        thresholdPx: Float = threshold(wideRow),
        vx: Float = 0f,
        vy: Float = 0f,
        escape: Float = escapePx,
        slop: Float = slopPx,
        completed: Boolean = true,
    ): Int = swipeCommitDirection(
        offsetPx = offsetPx,
        thresholdPx = thresholdPx,
        velocityPxPerSec = vx,
        crossVelocityPxPerSec = vy,
        escapeVelocityPxPerSec = escape,
        slopPx = slop,
        dragCompleted = completed,
    )

    // -- the numbers the report is about ---------------------------------------------------------

    @Test fun `the committing distance is K9 and Thunderbird's 72 dp`() {
        // Pinned as a number because nothing else can see it: the functions below take px, and the
        // dp→px conversion happens in the composable. 72 dp is `messageListSwipeThreshold` in
        // Thunderbird for Android, unchanged from THUNDERBIRD_21_1 to main, and the value #125 asks
        // to be matched. Raising it back towards the old ~165 dp is the report, re-filed.
        assertEquals(72, SWIPE_COMMIT_DISTANCE_DP)
    }

    @Test fun `the escape velocity is androidx's own 120 dp per second`() {
        // K-9's MessageListSwipeCallback overrides neither getSwipeEscapeVelocity nor
        // getSwipeVelocityThreshold, so ItemTouchHelper's default applies:
        // item_touch_helper_swipe_escape_velocity = 120 dp/s. Raising it is the report re-filed in
        // the other axis — a flick that has to be violent is a flick that does not work.
        assertEquals(120, SWIPE_ESCAPE_VELOCITY_DP_PER_SEC)
    }

    // -- the threshold: absolute, capped, never zero ---------------------------------------------

    @Test fun `a normal row commits at the absolute distance, not at a fraction of its width`() {
        // The whole fix in one assertion: on a 1080 px row the old rule wanted 432 px of travel.
        assertEquals(distancePx, threshold(wideRow), 0f)
    }

    @Test fun `a row too narrow for the distance falls back to the old fraction`() {
        // 40% of 400 is 160, short of 189. Without this cap the threshold would sit beyond the
        // row's own width, `offsetX` is clamped to ±rowWidth by the drag handler, so |offset| could
        // never reach it: the row would follow the finger, spring back, and do nothing, for ever,
        // silently. K-9 does not cap and has exactly that dead gesture; this is where we part.
        assertEquals(160f, threshold(narrowRow), 0f)
    }

    @Test fun `at the crossover width the two rules agree`() {
        // 189 / 0.4 = 472.5: the exact width where the cap takes over. Just above, the absolute
        // distance; just below, the fraction. Both boundaries spelled out so a `<` slipping to `>`
        // in the cap has somewhere to fail.
        assertEquals(distancePx, threshold(473f), 0f)
        assertEquals(0.4f * 472f, threshold(472f), 0.001f)
    }

    @Test fun `the gesture can only ever get shorter than the old rule, never longer`() {
        // The cap's second merit, and the reason it is a cap and not a floor: for every MEASURED
        // width, the new threshold is at most what the old 40% rule asked, and at most 72 dp. (An
        // unmeasured row, width 0, is deliberately outside this and has its own test below.)
        for (width in 1..2000 step 7) {
            val w = width.toFloat()
            val t = threshold(w)
            assertTrue("width $w gave $t, longer than the old 40% rule", t <= 0.4f * w)
            assertTrue("width $w gave $t, longer than the absolute distance", t <= distancePx)
        }
    }

    @Test fun `the threshold is strictly positive at every width`() {
        // Contract, and the reason it is a contract: `>=` against a threshold of 0 answers TRUE for
        // an offset of 0, i.e. every release commits, including a plain tap that never moved.
        for (width in 0..2000 step 1) {
            val t = threshold(width.toFloat())
            assertTrue("width $width gave a threshold of $t", t > 0f)
            assertTrue("width $width gave a NaN threshold", !t.isNaN())
        }
    }

    // -- an unmeasured row ----------------------------------------------------------------------

    @Test fun `a row that has not been measured commits nothing, whatever the offset`() {
        // rowWidth is 0 until the first layout pass. No bench run will ever produce this — a frame
        // is laid out before it can be touched — which is exactly why it has to be held here. The
        // cap alone would make the threshold 0 and hand `0 >= 0` to every release.
        val t = threshold(0f)
        assertTrue("an unmeasured row must not offer a reachable threshold, got $t", t > 0f)
        val offsets = listOf(
            0f, 1f, -1f, 0.4f, -0.4f, 100f, -100f, 10_000f, -10_000f,
            Float.MAX_VALUE, -Float.MAX_VALUE,
        )
        for (offset in offsets) {
            assertEquals(
                "an unmeasured row committed at offset $offset (threshold $t)",
                0, direction(offset, t),
            )
        }
    }

    @Test fun `an unmeasured row is not committed by a flick either, however violent`() {
        // The distance path is shut on an unmeasured row by an unreachable (infinite) threshold.
        // The velocity path does not look at the threshold at all, so it would walk straight around
        val t = threshold(0f)
        for (offset in listOf(30f, -30f, 400f, -400f, Float.MAX_VALUE, -Float.MAX_VALUE)) {
            for (vx in listOf(400f, -400f, 100_000f, -100_000f)) {
                assertEquals(
                    "an unmeasured row committed at offset $offset, vx $vx",
                    0, direction(offset, t, vx = vx),
                )
            }
        }
    }

    // -- the decision: which way, or neither ------------------------------------------------------

    @Test fun `a swipe right commits at the threshold and not one pixel before`() {
        val t = threshold(wideRow)
        assertEquals(0, direction(t - 1f, t))
        assertEquals(1, direction(t, t))
        assertEquals(1, direction(t + 1f, t))
    }

    @Test fun `a swipe left commits at the same threshold, mirrored`() {
        val t = threshold(wideRow)
        assertEquals(0, direction(-(t - 1f), t))
        assertEquals(-1, direction(-t, t))
        assertEquals(-1, direction(-(t + 1f), t))
    }

    @Test fun `the threshold is the same in both directions`() {
        // The old code compared a SIGNED fraction twice, once negated; this asserts the symmetry
        // that was maintained by hand. A direction-dependent threshold is the kind of thing only a
        // left-handed user reports, two releases later.
        val t = threshold(wideRow)
        for (offset in -1200..1200 step 3) {
            val x = offset.toFloat()
            assertEquals("offset $x and its mirror disagree", -direction(x, t), direction(-x, t))
        }
    }

    @Test fun `no offset ever commits both ways`() {
        val t = threshold(wideRow)
        for (offset in -1200..1200 step 3) {
            assertTrue(direction(offset.toFloat(), t) in -1..1)
        }
    }

    // -- monotonicity, and the band where the reveal must stay neutral ---------------------------

    @Test fun `pulling further only ever commits more, and the verdict flips exactly twice`() {
        // Sweeps the whole travel of a phone row and asserts the verdict changes sides ONCE per
        // direction, at the threshold. Catches a reversed comparison, a threshold written against
        // the wrong quantity, and any rule that is not monotonic in how far the row was pulled —
        // none of which a handful of spot values would necessarily catch. At rest: a still finger.
        val t = threshold(wideRow).toInt()
        val verdicts = (-1080..1080).map { direction(it.toFloat(), threshold(wideRow)) }
        assertEquals(1081 - t, verdicts.count { it == -1 })
        assertEquals(1081 - t, verdicts.count { it == 1 })
        assertEquals(2 * t - 1, verdicts.count { it == 0 })
        assertEquals(2, verdicts.zipWithNext().count { (a, b) -> a != b })
        // ...and never backwards: the verdict, read as a number, only rises.
        assertTrue(verdicts.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test fun `the reveal stays neutral exactly where releasing would do nothing`() {
        // This is the WYSIWYG promise in InboxScreen's own words: the reveal arms "the moment
        // releasing would trigger the action". Both sites now ask this one function, so the two can
        // no longer drift — but the band still has to be the right one. Stated here for a finger at
        // rest; with a flick the band moves, and the reveal moves with it, which is the point.
        val t = threshold(wideRow)
        for (offset in -1080..1080) {
            val x = offset.toFloat()
            val committed = direction(x, t) != 0
            val armed = kotlin.math.abs(x) >= t
            assertEquals("offset $x: committed=$committed but armed=$armed", armed, committed)
        }
    }

    // == the flick ================================================================================

    @Test fun `a short quick flick commits where a short slow drag does not`() {
        // #125's second half, in one pair of assertions. 60 px is a third of the 189 px distance —
        // and, at 400 px/s, a deliberate flick. Before this, only the first line's verdict existed
        // and it was 0: no gesture, however sharp, ever committed without travelling the distance.
        val t = threshold(wideRow)
        assertEquals("a 60 px flick at 400 px/s must commit rightwards", 1, direction(60f, t, vx = 400f))
        assertEquals("the same 60 px, finger at rest, must not", 0, direction(60f, t))
        assertEquals("and mirrored, leftwards", -1, direction(-60f, t, vx = -400f))
        assertEquals("the same -60 px at rest, must not", 0, direction(-60f, t))
    }

    @Test fun `a slow long drag still commits — the distance rule is untouched`() {
        // The flick is an OR, not a replacement. A finger that crawls the full distance and stops
        // dead before lifting has velocity ~0 and must still fire; making the velocity a
        // REQUIREMENT rather than an alternative breaks every careful swipe there is.
        val t = threshold(wideRow)
        assertEquals(1, direction(t, t, vx = 0f))
        assertEquals(1, direction(t + 50f, t, vx = 3f))
        assertEquals(-1, direction(-t, t, vx = 0f))
        assertEquals(-1, direction(-(t + 50f), t, vx = -3f))
    }

    @Test fun `a flick back the other way commits nothing — the doubling-back case`() {
        // Archive right, delete left. The finger goes right (the swipe arms), pulls 30 px, then
        // whips back LEFT and lifts. The row's offset is still slightly positive — or pinned at 0
        val t = threshold(wideRow)
        assertEquals("pulled right, flicked left", 0, direction(30f, t, vx = -2000f))
        assertEquals("pulled left, flicked right", 0, direction(-30f, t, vx = 2000f))
        // ...at every speed, and everywhere short of the distance, not just at one spot value.
        for (offset in listOf(21f, 30f, 100f, 188f)) {
            for (vx in listOf(-316f, -1000f, -100_000f)) {
                assertEquals("offset $offset with vx $vx", 0, direction(offset, t, vx = vx))
                assertEquals("offset ${-offset} with vx ${-vx}", 0, direction(-offset, t, vx = -vx))
            }
        }
    }

    @Test fun `a flick that is more vertical than horizontal commits nothing`() {
        // ItemTouchHelper's fourth condition, |vx| > |vy|, and it is not decoration here. The
        // direction lock upstream judged on distances ACCUMULATED SINCE TOUCH-DOWN, never on the
        val t = threshold(wideRow)
        assertEquals("equal parts: not a horizontal flick", 0, direction(60f, t, vx = 900f, vy = 900f))
        assertEquals("mostly vertical", 0, direction(60f, t, vx = 900f, vy = 1200f))
        assertEquals("the sign of vy is irrelevant", 0, direction(60f, t, vx = 900f, vy = -1200f))
        assertEquals("just horizontal enough", 1, direction(60f, t, vx = 900f, vy = 899f))
        assertEquals("and mirrored", -1, direction(-60f, t, vx = -900f, vy = 899f))
        assertEquals("and mirrored, vetoed", 0, direction(-60f, t, vx = -900f, vy = -901f))
    }

    @Test fun `a flick that has barely moved the row commits nothing`() {
        // The floor, and the reason it has to be written here rather than inherited: the travel the
        // finger makes while the direction lock is still deciding is THROWN AWAY — offsetX only
        val t = threshold(wideRow)
        assertEquals("under slop, however fast", 0, direction(slopPx - 1f, t, vx = 5000f))
        assertEquals("under slop, mirrored", 0, direction(-(slopPx - 1f), t, vx = -5000f))
        assertEquals("at slop exactly, it counts", 1, direction(slopPx, t, vx = 5000f))
        assertEquals("at slop exactly, mirrored", -1, direction(-slopPx, t, vx = -5000f))
        for (offset in 0..20) {
            assertEquals(
                "offset $offset is under the slop and must not commit",
                0, direction(offset.toFloat(), t, vx = 100_000f),
            )
        }
    }

    @Test fun `the escape velocity decides, one pixel per second either side of it`() {
        val t = threshold(wideRow)
        assertEquals("just under", 0, direction(60f, t, vx = escapePx - 1f))
        assertEquals("exactly at it", 1, direction(60f, t, vx = escapePx))
        assertEquals("just over", 1, direction(60f, t, vx = escapePx + 1f))
        assertEquals("just under, mirrored", 0, direction(-60f, t, vx = -(escapePx - 1f)))
        assertEquals("exactly at it, mirrored", -1, direction(-60f, t, vx = -escapePx))
    }

    @Test fun `a row that has not moved at all commits nothing, at any speed`() {
        // Offset exactly 0: there is no direction to commit IN. Reachable — the offset bound is 0
        // on a side with no action assigned, so a flick that way leaves the row at exactly 0 while
        // the tracker still reports the finger's full speed.
        val t = threshold(wideRow)
        for (vx in listOf(316f, -316f, 5000f, -5000f, Float.MAX_VALUE, -Float.MAX_VALUE)) {
            assertEquals("offset 0 with vx $vx", 0, direction(0f, t, vx = vx))
            // ...and independently of the slop floor, which would otherwise be the only thing
            // answering here and would hide the fact that "no side" is a case of its own. A
            // platform is free to report a touch slop of 0; the rule must not depend on it not to.
            assertEquals(
                "offset 0 with vx $vx and no slop at all", 0, direction(0f, t, vx = vx, slop = 0f),
            )
        }
    }

    @Test fun `a cancelled drag commits nothing, by either path`() {
        // horizontalDrag returns false when the pointer was taken away rather than lifted. The
        // value used to be dropped, harmlessly: the row was where a considered finger had left it.
        // With a flick in the rule it is a way in — the gesture carries the speed of the instant it
        // was snatched, and nobody decided anything.
        val t = threshold(wideRow)
        assertEquals("cancelled past the distance", 0, direction(t + 200f, t, completed = false))
        assertEquals("cancelled past the distance, mirrored", 0, direction(-(t + 200f), t, completed = false))
        assertEquals("cancelled mid-flick", 0, direction(60f, t, vx = 5000f, completed = false))
        assertEquals("cancelled mid-flick, mirrored", 0, direction(-60f, t, vx = -5000f, completed = false))
        // ...and the same inputs, merely completed, DO commit — otherwise this test would pass
        // against a rule that commits nothing at all.
        assertEquals(1, direction(t + 200f, t, completed = true))
        assertEquals(1, direction(60f, t, vx = 5000f, completed = true))
    }

    @Test fun `the flick is mirror-symmetric, offset and velocity together`() {
        // Sweeps the plane rather than spot-checking it: for every (offset, vx, vy) the mirrored
        // gesture must give the mirrored verdict. A guard written with one `>` where the other side
        // has `>=`, or a sign test that only looks at the velocity, shows up here and nowhere else.
        val t = threshold(wideRow)
        for (offset in -400..400 step 7) {
            for (vx in -1200..1200 step 37) {
                for (vy in listOf(0f, 400f, 1500f)) {
                    val here = direction(offset.toFloat(), t, vx = vx.toFloat(), vy = vy)
                    val mirrored = direction(-offset.toFloat(), t, vx = -vx.toFloat(), vy = vy)
                    assertEquals(
                        "offset $offset vx $vx vy $vy: $here, mirrored gave $mirrored",
                        -here, mirrored,
                    )
                }
            }
        }
    }

    @Test fun `a flick never commits in a direction the row was not pulled`() {
        // The invariant behind the doubling-back case, stated over the whole plane: whatever
        // commits, commits the way the row actually moved. Nothing else can be honest — the reveal
        // the user is looking at is drawn on the side the offset points to.
        val t = threshold(wideRow)
        for (offset in -400..400 step 3) {
            for (vx in -3000..3000 step 97) {
                val verdict = direction(offset.toFloat(), t, vx = vx.toFloat())
                if (verdict != 0) {
                    assertTrue(
                        "offset $offset with vx $vx committed $verdict",
                        verdict.toFloat() * offset > 0f,
                    )
                }
            }
        }
    }

    @Test fun `no velocity can shorten the distance rule below the slop floor`() {
        // Belt and braces over the two paths together: whatever the speed, a row that has moved
        // less than the touch slop never commits. This is the assertion that would catch a future
        // "simplification" that drops the floor because "the escape velocity is enough".
        val t = threshold(wideRow)
        for (offset in -20..20) {
            for (vx in listOf(0f, 316f, -316f, 50_000f, -50_000f)) {
                for (vy in listOf(0f, 100f)) {
                    assertEquals(
                        "offset $offset vx $vx vy $vy",
                        0, direction(offset.toFloat(), t, vx = vx, vy = vy),
                    )
                }
            }
        }
    }
}
