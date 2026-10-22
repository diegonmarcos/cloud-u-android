// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.emoji

import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode

/**
 * #1103 The emoji search, declared once so the panel, the results box and the tests agree.
 *
 *  - Tapping the panel's search field must give the user the letter keys back: the panel itself has none, so the tap
 *    first returns to the alphabet pad ([TAP_CODES] starts with ALPHA) and then opens the results box above it.
 *  - The results box shows the matching emoji (then stickers and GIFs) in ONE row under the typed text, at half the
 *    height the three stacked rows made it: [LEGACY_BOX_HEIGHT_DP] was 6 (top) + 32 (typed line) + 3 x 48 (emoji,
 *    sticker and GIF rows) + 8 (bottom).
 */
object EmojiSearchUi {
    /** What a tap on the panel's search field sends, in this order. */
    val TAP_CODES = listOf(KeyCode.ALPHA, KeyCode.EMOJI_SEARCH)

    const val TOP_DP = 6
    const val TYPED_LINE_DP = 32
    const val RESULT_ROW_DP = 48 // CELL_DP 40 + 8
    const val BOTTOM_DP = 8

    const val LEGACY_BOX_HEIGHT_DP = TOP_DP + TYPED_LINE_DP + 3 * RESULT_ROW_DP + BOTTOM_DP
    /** Half of the legacy height. The content (top + typed line + one result row + bottom) is 94 and fits. */
    const val BOX_HEIGHT_DP = LEGACY_BOX_HEIGHT_DP / 2
    const val CONTENT_HEIGHT_DP = TOP_DP + TYPED_LINE_DP + RESULT_ROW_DP + BOTTOM_DP
}
