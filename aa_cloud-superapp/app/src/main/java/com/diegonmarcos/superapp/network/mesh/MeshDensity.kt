package com.diegonmarcos.superapp.network.mesh

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * #877 THE density declaration of the Cloud Mesh page - every Mesh* screen, row, light and control
 * reads its sizes from here and from nowhere else (the #869 StoreDensity approach). The mesh page is
 * a data readout, not a launcher: rows are compact, the type ramp is small and monospaced where the
 * value is a key, an address or a counter, and a button is not Material's 48dp box.
 *
 * No other Kotlin file of the mesh page holds a dp or sp literal: spacing is one of the S* steps,
 * type is one of the T_* ramp entries, a fixed box is a named size. test-mesh-page.sh fails on a
 * literal anywhere else in network/mesh/. Change the feel of the whole page by editing this file only.
 *
 * Text sizes are `sp`, so the user's system font scale still applies on top of [TEXT_SCALE].
 */
object MeshDensity {
    /** Every dp value of the page is multiplied by this (1.0 = the launcher-sized look). */
    const val SCALE = 0.8f
    /** Every sp value of the page is multiplied by this. */
    const val TEXT_SCALE = 0.85f

    // Spacing steps (dp, before SCALE): the same six steps the Store collapsed to.
    const val S1 = 1
    const val S2 = 2
    const val S4 = 4
    const val S6 = 6
    const val S8 = 8
    const val S12 = 12
    /** The status light's disc. */
    const val GLYPH = 10
    /** A control's touch height; the tap target stays usable though dense. */
    const val TAP = 36
    /** A QR code's edge. */
    const val QR = 220
    /** The widest a rate / counter column needs. */
    const val COLUMN = 76

    // Type ramp (sp, before TEXT_SCALE).
    const val T_MICRO = 10f * TEXT_SCALE
    const val T_CAPTION = 11f * TEXT_SCALE
    const val T_META = 12f * TEXT_SCALE
    const val T_BODY = 13f * TEXT_SCALE
    const val T_TITLE = 14f * TEXT_SCALE
    const val T_HEAD = 17f * TEXT_SCALE

    /** [step] (one of the S* steps or a named size) as Compose dp, scaled once. */
    fun dp(step: Int): Dp = (step * SCALE).dp

    /** A ramp entry as Compose sp. */
    fun sp(t: Float): TextUnit = t.sp
}
