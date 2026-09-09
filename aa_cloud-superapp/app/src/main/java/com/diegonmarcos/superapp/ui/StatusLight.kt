package com.diegonmarcos.superapp.ui

/**
 * The one place a three-state reading becomes something on screen.
 *
 * ── Why this is an object and not four literals in a fragment ─────────────
 * A status light makes a promise — this thing is working, or it is not — and
 * the owner starts trusting it and stops checking. Two screens promising that
 * in two slightly different greens is not a style choice, it is one of them
 * being wrong about which green means healthy. So the colours live here, once,
 * and the pages point at them.
 *
 * The values are the ones Configs ▸ Permissions was already using inline for
 * exactly this granted / not-granted / cannot-say question, lifted rather than
 * re-picked: adopting the existing green was the whole point.
 *
 * ── THREE states, because reality has three ───────────────────────────────
 * Red and green are two. The third is NOT YET KNOWN — the check has not run,
 * is running, threw, the platform declined to answer, or its answer has aged
 * out. A two-state light has to draw that as green or red and both are false,
 * so [UNKNOWN] is a state here and never a default to [OFF].
 *
 * ── Colour is never the only channel ──────────────────────────────────────
 * Red and green are the pair colour-blind users most often cannot separate,
 * and both are grey in a screenshot. Every state therefore carries a [glyph]
 * whose SHAPE differs — filled disc, hollow ring, question mark — and a
 * [label] word, so the light reads correctly with no colour at all. All three
 * glyphs are already this app's vocabulary: ● / ○ are what NetworkInfoPopup
 * draws for connected / disconnected, ? is what PermissionsFragment draws for
 * a grant it cannot determine.
 */
object StatusLight {

    enum class State { ON, OFF, UNKNOWN }

    /**
     * A reading as every state source in this app already returns it.
     *
     * null is UNKNOWN and MUST NOT collapse to OFF: "the platform would not
     * say" and "the platform said no" are different facts, and a light that
     * merges them is the lie this type exists to prevent.
     */
    fun of(reading: Boolean?): State = when (reading) {
        true -> State.ON
        false -> State.OFF
        null -> State.UNKNOWN
    }

    /** Shape first — this is what survives greyscale and colour blindness. */
    fun glyph(state: State): String = when (state) {
        State.ON -> "●"        // ● filled
        State.OFF -> "○"       // ○ hollow
        State.UNKNOWN -> "?"
    }

    fun label(state: State): String = when (state) {
        State.ON -> "On"
        State.OFF -> "Off"
        State.UNKNOWN -> "Unknown"
    }

    /** Glyph and word together — what a row actually prints. */
    fun text(state: State): String = glyph(state) + " " + label(state)

    fun colour(state: State): Int = when (state) {
        State.ON -> GREEN
        State.OFF -> RED
        State.UNKNOWN -> GREY
    }

    // The single definition of healthy / failed / cannot-say in this app.
    const val GREEN = 0xFF16A34A.toInt()
    const val RED = 0xFFDC2626.toInt()
    const val GREY = 0xFF6B7280.toInt()
}
