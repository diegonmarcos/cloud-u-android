// ╔══════════════════════════════════════════════════════════════════════╗
// ║ translate-key-handoff.test — tapping the host field releases the     ║
// ║ keys, and typing in the panel does NOT                               ║
// ╚══════════════════════════════════════════════════════════════════════╝
//
// #355 part 2. While the translate bar is open it holds the keys: LatinIME routes
// keystrokes into the bar's own box instead of into the application's text field
// (#87). The decision this covers is the way back out — tapping into the host
// application's own text field releases the keys to it, because that is what every
// IME panel on the platform does and it needs no new gesture.
//
// BOTH HALVES ARE ASSERTED HERE AND THE SECOND IS THE ONE THAT MATTERS. The only
// signal an IME gets for "the user moved the caret themselves" is onUpdateSelection,
// and it does not say who caused it — the bar's OWN writes into the field raise it
// too. A release on every selection change therefore hands the keys back the instant
// the user types in the panel, and the feature is gone. Case 3 and case 4 are that
// failure, written down.
//
// WHY COMPILED AND RUN, NOT GREPPED. This is a behaviour, so it is executed, against
// the SHIPPED KeyHandoff.kt by path — never a copy. The repo has already paid once
// for assertions anchored on source text (0b2cbc5ee), and today's fleet sweep found
// seven assertions that passed without checking anything. The runner's phase 2 puts
// each half of the defect back in a sandbox copy and demands this file go red; an
// assertion nobody has watched fail is decoration.
//
// KeyHandoff is deliberately free of Android imports so that this is possible at all
// without a device. The view wiring in TranslateBarView is NOT covered here — see the
// report's "what I could not verify".

// Pinned explicitly: this file follows the repo's *.test.* naming, and a JVM class
// name cannot contain those dots, so the compiler would mangle it and the runner
// would be guessing what to invoke.
@file:JvmName("TranslateKeyHandoffTest")

import com.diegonmarcos.superapp.translate.KeyHandoff

private var failures = 0

private fun expect(cond: Boolean, what: String) {
    if (cond) {
        println("  ok    $what")
    } else {
        println("  FAIL  $what")
        failures++
    }
}

fun main() {

    // ── Case 1: the bar opens holding the keys ────────────────────────────
    // #87's capture, unchanged. If this ever goes false the panel is a text box that
    // cannot be typed into.
    run {
        val keys = KeyHandoff()
        expect(keys.heldByBox, "a freshly shown bar holds the keys")
    }

    // ── Case 2: HALF ONE — tapping the host field releases the keys ───────
    // A selection change the bar did not announce is the user moving the caret in the
    // application's own field. This is the whole ticket.
    run {
        val keys = KeyHandoff()
        val fromHost = keys.onSelectionChange()
        expect(fromHost, "an unannounced selection change is reported as host-caused")
        expect(!keys.heldByBox, "tapping into the host field releases the keys to it")
    }

    // ── Case 3: HALF TWO — the bar's own write does NOT release the keys ──
    // The write that matters is the one that looks identical to a host tap from the
    // outside: finishComposingText and commitText both report a composing span of -1.
    // If this case goes red the panel hands the keys back while the user is typing in
    // it, which is the unusable version of this feature.
    run {
        val keys = KeyHandoff()
        keys.onSelfWrite()
        val fromHost = keys.onSelectionChange()
        expect(!fromHost, "the echo of the bar's own write is not reported as host-caused")
        expect(keys.heldByBox, "typing in the panel does NOT release the keys")
    }

    // ── Case 4: half two, as the user actually reaches it ─────────────────
    // Live commit on: every keystroke that empties the box runs pushOutput("") ->
    // releaseOutput -> finishComposingText. Backspacing to blank and typing again is a
    // stream of self-caused -1 updates, and not one of them may release.
    run {
        val keys = KeyHandoff()
        var everReleased = false
        var everCalledHost = false
        repeat(20) {
            keys.onSelfWrite()
            if (keys.onSelectionChange()) everCalledHost = true
            if (!keys.heldByBox) everReleased = true
        }
        expect(!everCalledHost, "no announced write in a 20-keystroke run reads as host-caused")
        expect(!everReleased, "and the keys never leave the panel at any point during it")
    }

    // ── Case 5: the announcement is spent EXACTLY once ────────────────────
    // The guard swallows one echo, not a mode. A real host tap arriving after the
    // bar's own write must still release — otherwise a single unmatched announcement
    // would deafen the bar for the rest of the session.
    run {
        val keys = KeyHandoff()
        keys.onSelfWrite()
        keys.onSelectionChange()                        // spends the announcement
        val fromHost = keys.onSelectionChange()         // the user's tap, right after
        expect(fromHost, "a host tap following the bar's own write is still heard")
        expect(!keys.heldByBox, "and still releases the keys")
    }

    // ── Case 6: tapping the panel box takes the keys back ─────────────────
    // The release would be a trap without this: applying a translation clears the box,
    // so a bar the user then tapped away from could never be typed into again.
    run {
        val keys = KeyHandoff()
        keys.onSelectionChange()
        expect(!keys.heldByBox, "released after a host tap")
        keys.reclaim()
        expect(keys.heldByBox, "tapping the panel's own box takes the keys back")
    }

    // ── Case 7: reopening the bar is a fresh session ──────────────────────
    // onShown calls reset. A bar that remembered it had been released would open dead.
    run {
        val keys = KeyHandoff()
        keys.onSelectionChange()
        keys.onSelfWrite()          // an announcement left unspent by a bar that closed
        keys.reset()
        expect(keys.heldByBox, "reopening the bar holds the keys again")
        expect(keys.onSelectionChange(), "and no stale announcement survives to swallow a tap")
    }

    if (failures > 0) {
        println()
        println("FAILED: $failures assertion(s)")
        kotlin.system.exitProcess(1)
    }
    println()
    println("PASS: the host field takes the keys on a tap, and the panel keeps them while typing")
}
