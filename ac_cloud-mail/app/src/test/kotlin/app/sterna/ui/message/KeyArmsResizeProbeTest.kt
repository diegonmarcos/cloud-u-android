package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The keyboard door's decision, RUN: [BodyReveal.keyArmsResizeProbe].
 *
 * Measured on the bench: folding the quoted history WITH A FINGER gives Reply/Forward back, folding
 * it with SPACE / ENTER / DPAD_CENTER folds the body and the bar never comes back — 24 swipes did
 * not bring it, one touch fold/unfold did. Two things were wrong at once and only one of them can
 * be measured here:
 *
 *  - WHICH EVENT the platform delivers to us at all (the door used to hang off `onKeyUp`, which is
 *    only reached for a key nobody consumed — and Blink consumed it, since the body did fold).
 * NOT COVERED BY ANY TEST IN THIS REPO: nothing in a JVM suite can build a WebView, press a
 *    key and watch which override runs. Its falsifier is a bench pass, and only a bench pass.
 *  - WHEN the door arms, which is what this file pins. The probe has to take its `atGesture`
 *    reading BEFORE the document acts on the key, or [BodyReveal.resizeStep] compares the folded
 *    height with itself and expires in silence — the symptom, restored. Which of DOWN and UP the
 *    document acts on is NOT knowable here and is not the same for every key, so BOTH arm; see
 *    [BodyReveal.keyArmsResizeProbe] for the arbitration and what the spare probe costs.
 *
 * EVERY NUMBER IS WRITTEN OUT — the platform's own constants, and the three key codes actually
 * pressed on the bench. Nothing here re-derives the shipped rule to decide what to expect: drop
 * either instant, add a key to the set, or let a held key through, and this file goes red.
 */
class KeyArmsResizeProbeTest {

    // android.view.KeyEvent, written out. They are `static final int` and frozen API: a test that
    // read them off the class would only be asking the same header the code was compiled against.
    private val actionDown = 0 // ACTION_DOWN
    private val actionUp = 1 // ACTION_UP
    private val actionMultiple = 2 // ACTION_MULTIPLE
    private val space = 62 // KEYCODE_SPACE — bench
    private val enter = 66 // KEYCODE_ENTER — bench
    private val dpadCenter = 23 // KEYCODE_DPAD_CENTER — bench
    private val numpadEnter = 160 // KEYCODE_NUMPAD_ENTER — the fourth activation key

    @Test fun `the three keys pressed on the bench arm the probe on their press`() {
        assertEquals(
            "SPACE (62) going DOWN must arm the probe: if the document folds on the press, this " +
                "is the only reading taken while the range still holds the unfolded height",
            true,
            BodyReveal.keyArmsResizeProbe(actionDown, space, 0),
        )
        assertEquals(
            "ENTER (66) — bench, same fold, same bar lost",
            true,
            BodyReveal.keyArmsResizeProbe(actionDown, enter, 0),
        )
        assertEquals(
            "DPAD_CENTER (23) — bench; a D-pad opens the fold with no touch event at all",
            true,
            BodyReveal.keyArmsResizeProbe(actionDown, dpadCenter, 0),
        )
        assertEquals(
            "NUMPAD_ENTER (160) presses a focused <summary> exactly like ENTER. It was never " +
                "pressed on the bench, which is precisely why it needs a test",
            true,
            BodyReveal.keyArmsResizeProbe(actionDown, numpadEnter, 0),
        )
    }

    @Test fun `the release arms it too, because which instant the document acts on is not known`() {
        // Not symmetry for its own sake, and not caution: it is the arbitration. In Blink, a
        // keydown on SPACE only marks the <summary> active and the synthesised click leaves on the
        // KEYUP, while ENTER and DPAD_CENTER act on the press — that reading cannot be checked from
        // this repo, and if it is right then arming on the press ALONE loses a held SPACE: the
        // twenty ticks (~1s) expire before the fold arrives at the release, and nothing re-arms.
        // Arming on both costs one bounded probe that reads the same height twice and says nothing
        // (resizeStep → Done). Losing the bar until the message is reopened costs the reader.
        assertEquals(
            "SPACE (62) going UP must arm the probe too — if the click leaves at the release, " +
                "this is the reading taken before the fold",
            true,
            BodyReveal.keyArmsResizeProbe(actionUp, space, 0),
        )
        assertEquals(
            "ENTER (66) going UP — same rule, one rule for every activation key",
            true,
            BodyReveal.keyArmsResizeProbe(actionUp, enter, 0),
        )
        assertEquals(
            "DPAD_CENTER (23) going UP — same",
            true,
            BodyReveal.keyArmsResizeProbe(actionUp, dpadCenter, 0),
        )
        assertEquals(
            "NUMPAD_ENTER (160) going UP — same",
            true,
            BodyReveal.keyArmsResizeProbe(actionUp, numpadEnter, 0),
        )
    }

    @Test fun `a held key repeats without re-arming, and its release arms once`() {
        // A key held down produces DOWN after DOWN with a growing repeat count. Arming on the
        // repeats would hang a fresh twenty-tick probe off the UI thread several times a second
        // for as long as the key is held: a permanent size watcher through the back door, which is
        // the bar-blink of Codeberg #63. The final release still carries repeatCount 0, so the
        // state the reader ends on IS measured, once.
        assertEquals(
            "the first press (repeat 0) arms",
            true,
            BodyReveal.keyArmsResizeProbe(actionDown, space, 0),
        )
        assertEquals(
            "the first auto-repeat must not arm anything",
            false,
            BodyReveal.keyArmsResizeProbe(actionDown, space, 1),
        )
        assertEquals(
            "nor must a key held long enough to repeat seven times",
            false,
            BodyReveal.keyArmsResizeProbe(actionDown, enter, 7),
        )
        assertEquals(
            "nor a repeated D-pad centre",
            false,
            BodyReveal.keyArmsResizeProbe(actionDown, dpadCenter, 3),
        )
        assertEquals(
            "but the RELEASE that ends the burst carries repeatCount 0 and must arm: it is the " +
                "one reading taken at the state she stopped on",
            true,
            BodyReveal.keyArmsResizeProbe(actionUp, space, 0),
        )
    }

    @Test fun `a batched ACTION_MULTIPLE arms nothing`() {
        assertEquals(
            "ACTION_MULTIPLE (2) is a batch of repeats delivered as one event — same reasoning as " +
                "the auto-repeat above, and it is neither the press nor the release",
            false,
            BodyReveal.keyArmsResizeProbe(actionMultiple, space, 0),
        )
        assertEquals(
            "ACTION_MULTIPLE on ENTER either",
            false,
            BodyReveal.keyArmsResizeProbe(actionMultiple, enter, 0),
        )
    }

    @Test fun `exactly four key codes arm the probe, out of every code the platform has`() {
        // The WHOLE key space, not three chosen counter-examples: a key added to the set — say
        // KEYCODE_DPAD_UP (19), which only moves focus — would hang a twenty-tick probe off every
        // press of it, and three negative examples would never notice. 400 covers KEYCODE_MAX on
        // every API this app runs on, with room over it: an unassigned code must not arm either.
        val codes = 0..400
        assertEquals(
            "exactly DPAD_CENTER (23), SPACE (62), ENTER (66) and NUMPAD_ENTER (160) may arm the " +
                "probe on a press — these are the keys that PRESS a focused <summary>. Anything " +
                "else here is a key that types, scrolls or moves focus: it changes no height, and " +
                "arming on it polls the UI thread for a second every time she uses it.",
            listOf(23, 62, 66, 160),
            codes.filter { BodyReveal.keyArmsResizeProbe(actionDown, it, 0) },
        )
        assertEquals(
            "and exactly the same four on a release — the two instants must not drift apart, or " +
                "one key ends up measured at one instant only",
            listOf(23, 62, 66, 160),
            codes.filter { BodyReveal.keyArmsResizeProbe(actionUp, it, 0) },
        )
        assertEquals(
            "no key at all may arm on a repeat",
            emptyList<Int>(),
            codes.filter { BodyReveal.keyArmsResizeProbe(actionDown, it, 1) },
        )
        assertEquals(
            "and no key on ACTION_MULTIPLE",
            emptyList<Int>(),
            codes.filter { BodyReveal.keyArmsResizeProbe(actionMultiple, it, 0) },
        )
    }
}
