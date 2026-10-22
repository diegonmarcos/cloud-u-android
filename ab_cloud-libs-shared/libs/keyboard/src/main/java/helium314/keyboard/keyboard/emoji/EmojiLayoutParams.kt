/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */
package helium314.keyboard.keyboard.emoji

import android.content.res.Resources
import helium314.keyboard.keyboard.internal.KeyboardParams
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ResourceUtils

internal class EmojiLayoutParams(res: Resources) {
    private val emojiListBottomMargin: Int
    val emojiKeyboardHeight: Int
    private val emojiCategoryPageIdViewHeight: Int
    val bottomRowKeyboardHeight: Int

    init {
        val sv = Settings.getValues()
        val defaultKeyboardHeight = ResourceUtils.getSecondaryKeyboardHeight(res, sv)

        val keyVerticalGap = (res.getFraction(R.fraction.config_key_vertical_gap_holo,
            defaultKeyboardHeight, defaultKeyboardHeight) * sv.mKeyGapScale).toInt()
        val bottomPadding = (res.getFraction(R.fraction.config_keyboard_bottom_padding_holo,
            defaultKeyboardHeight, defaultKeyboardHeight) * sv.mBottomPaddingScale).toInt()
        val topPadding = res.getFraction(R.fraction.config_keyboard_top_padding_holo,
            defaultKeyboardHeight, defaultKeyboardHeight).toInt()

        val rowCount = KeyboardParams.DEFAULT_KEYBOARD_ROWS + if (sv.mShowsNumberRow) 1 else 0
        bottomRowKeyboardHeight = (defaultKeyboardHeight - bottomPadding - topPadding) / rowCount - keyVerticalGap / 2

        val pageIdHeight = res.getDimension(R.dimen.config_emoji_category_page_id_height)
        emojiCategoryPageIdViewHeight = pageIdHeight.toInt()
        val offset = 1.25f * res.displayMetrics.density * sv.mKeyboardHeightScale // like PanelLayoutParams
        val emojiListHeight = defaultKeyboardHeight - bottomRowKeyboardHeight - bottomPadding + (offset.toInt())
        emojiListBottomMargin = 0
        emojiKeyboardHeight = emojiListHeight - emojiCategoryPageIdViewHeight - emojiListBottomMargin
    }

    /** The category page indicator's height (px). */
    val categoryPageIdViewHeight get() = emojiCategoryPageIdViewHeight
}
