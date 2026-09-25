package com.diegonmarcos.clouddrive.ui

import android.content.Context
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.diegonmarcos.clouddrive.R

/**
 * #579 the fleet's StatusLight idiom, in Compose. This is the superapp's
 * `ui/StatusLight.kt` (#570/#573) reproduced state for state, because that file is
 * application code no library can export, and the IDIOM is what has to match: the
 * owner learns one green, one red and one honest grey once, and every card in
 * every fleet app keeps that promise.
 *
 * FOUR states, THREE appearances. [State.UNKNOWN] and [State.UNVERIFIABLE] draw
 * the same — same glyph, same colour — and differ only in the word: "nobody can
 * currently say" versus "nobody can ever say from here" (a preference this app
 * stored is what was ASKED FOR, not what is true). Colour is never the only
 * channel: every state carries a glyph whose SHAPE differs (filled disc, hollow
 * ring, question mark) and a word from the string table, so the light reads with
 * no colour at all. The colours are resources with the superapp's names and values
 * (colors.xml); test-drive-shell.sh compares them against the superapp's.
 */
object StatusLight {

    enum class State { ON, OFF, UNKNOWN, UNVERIFIABLE }

    /**
     * @param reading as every state source returns it. null is UNKNOWN and MUST NOT
     *   collapse to OFF: "would not say" and "said no" are different facts.
     * @param observed false ⇒ the reading came from a stored preference, not from
     *   the mechanism it describes, so it can support neither a green nor a red.
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
        State.ON -> "●"
        State.OFF -> "○"
        State.UNKNOWN -> "?"
        State.UNVERIFIABLE -> "?"
    }

    fun labelRes(state: State): Int = when (state) {
        State.ON -> R.string.status_light_on
        State.OFF -> R.string.status_light_off
        State.UNKNOWN -> R.string.status_light_unknown
        State.UNVERIFIABLE -> R.string.status_light_unverifiable
    }

    fun colourRes(state: State): Int = when (state) {
        State.ON -> R.color.status_light_on
        State.OFF -> R.color.status_light_off
        State.UNKNOWN -> R.color.status_light_unknown
        State.UNVERIFIABLE -> R.color.status_light_unknown
    }

    fun label(ctx: Context, state: State): String = ctx.getString(labelRes(state))

    /** Glyph and word together — what a row actually prints. */
    fun text(ctx: Context, state: State): String = glyph(state) + " " + label(ctx, state)

    /** What a screen reader says: the row's own name and the word, never the glyph ("black circle"). */
    fun description(ctx: Context, rowLabel: String, state: State): String =
        ctx.getString(R.string.status_light_description, rowLabel, label(ctx, state))
}

/** The light as a card prints it: glyph + word in the state's colour, described for TalkBack. */
@Composable
fun StatusLightRow(state: StatusLight.State, rowLabel: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val colour = colorResource(StatusLight.colourRes(state))
    val description = StatusLight.description(ctx, rowLabel, state)
    Row(
        modifier.testTag(DriveTags.STATUS_LIGHT).semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            StatusLight.glyph(state) + " " + stringResource(StatusLight.labelRes(state)),
            color = colour,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}
