package com.diegonmarcos.cloudc3.cloud

import android.content.Context
import androidx.core.content.ContextCompat
import com.diegonmarcos.cloudc3.R

/**
 * The one place a reading about whether something is WORKING becomes something on screen —
 * MOVED from the SuperApp's ui package with the container sheet that resolves through it
 * (#648), body unchanged; same colour names, same values (colors.xml), same four words.
 *
 * FOUR states, because reality has four: red and green are two, the third is NOT YET
 * KNOWN, the fourth is NOT KNOWABLE — a preference is what was ASKED FOR, never what is
 * true. [UNKNOWN] and [UNVERIFIABLE] draw the same because to the owner they are the same
 * fact: nobody can currently say. Colour is never the only channel: every state carries a
 * glyph whose SHAPE differs, and a word from the string table.
 */
object StatusLight {

    enum class State { ON, OFF, UNKNOWN, UNVERIFIABLE }

    /**
     * A reading, and whether it was a look at the thing itself.
     *
     * @param reading null is UNKNOWN and MUST NOT collapse to OFF: "the platform would not
     *   say" and "the platform said no" are different facts, and a light that merges them
     *   is the lie this type exists to prevent.
     * @param observed false ⇒ [reading] came from a preference this app stored, not from
     *   the mechanism it describes, so it cannot support a green OR a red.
     */
    fun of(reading: Boolean?, observed: Boolean = true): State =
        if (!observed) State.UNVERIFIABLE
        else when (reading) {
            true -> State.ON
            false -> State.OFF
            null -> State.UNKNOWN
        }

    /** Shape first — this is what survives greyscale and colour blindness. */
    fun glyph(state: State): String = when (state) {
        State.ON -> "●"             // ● filled
        State.OFF -> "○"            // ○ hollow
        State.UNKNOWN -> "?"
        State.UNVERIFIABLE -> "?"   // same appearance, different word
    }

    fun labelRes(state: State): Int = when (state) {
        State.ON -> R.string.status_light_on
        State.OFF -> R.string.status_light_off
        State.UNKNOWN -> R.string.status_light_unknown
        State.UNVERIFIABLE -> R.string.status_light_unverifiable
    }

    fun label(ctx: Context, state: State): String = ctx.getString(labelRes(state))

    /** Glyph and word together — what a row actually prints. */
    fun text(ctx: Context, state: State): String = glyph(state) + " " + label(ctx, state)

    fun colourRes(state: State): Int = when (state) {
        State.ON -> R.color.status_light_on
        State.OFF -> R.color.status_light_off
        State.UNKNOWN -> R.color.status_light_unknown
        State.UNVERIFIABLE -> R.color.status_light_unknown
    }

    fun colour(ctx: Context, state: State): Int = ContextCompat.getColor(ctx, colourRes(state))

    /** What a screen reader says for one row's light. The glyph is deliberately absent:
     *  TalkBack pronounces "●" as "black circle", a description of the decoration. */
    fun description(ctx: Context, rowLabel: String, state: State): String =
        ctx.getString(R.string.status_light_description, rowLabel, label(ctx, state))
}
