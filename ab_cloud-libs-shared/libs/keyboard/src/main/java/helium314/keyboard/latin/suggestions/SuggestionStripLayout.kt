/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */
package helium314.keyboard.latin.suggestions

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import helium314.keyboard.latin.PunctuationSuggestions
import helium314.keyboard.latin.R
import helium314.keyboard.latin.SuggestedWords
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ResourceUtils
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What the suggestion row shows for one [SuggestedWords], decided apart from any drawing: which
 * word sits in which slot, in what colour and style, and whether the row collapses to its single
 * most relevant word. This is the layout rule of the old SuggestionStripLayoutHelper, kept
 * word for word; the Compose strip only draws what it returns.
 */
object SuggestionStripLayout {
    const val DEFAULT_SUGGESTIONS_COUNT_IN_STRIP = 3
    const val DEFAULT_CENTER_SUGGESTION_PERCENTILE = 0.40f
    const val DEFAULT_MAX_MORE_SUGGESTIONS_ROW = 2
    const val PUNCTUATIONS_IN_STRIP = 5
    const val MIN_TEXT_XSCALE = 0.70f
    const val MORE_SUGGESTIONS_HINT = "…"

    // flag values of R.styleable.SuggestionStripView_suggestionStripOptions
    const val AUTO_CORRECT_BOLD = 0x01
    const val AUTO_CORRECT_UNDERLINE = 0x02
    const val VALID_TYPED_WORD_BOLD = 0x04

    /** One occupied slot of the row. [bold] / [underline] are the autocorrect / valid-word highlight. */
    class WordSlot(
        val indexInSuggestedWords: Int,
        val label: String,
        val color: Int,
        val bold: Boolean,
        val underline: Boolean,
        val debugInfo: String?,
    )

    /**
     * [slots] is indexed by position in the strip (null = nothing there), increasing towards the
     * right for LTR scripts and the left for RTL ones. [singleCenter] means only the centre slot is
     * drawn, over the whole row.
     */
    class Layout(
        val slots: List<WordSlot?>,
        val punctuation: Boolean,
        val singleCenter: Boolean,
        val moreAvailable: Boolean,
        val startIndexOfMoreSuggestions: Int,
    ) {
        companion object {
            val EMPTY = Layout(emptyList(), false, false, false, 0)
        }
    }

    @JvmStatic
    fun shouldOmitTypedWord(inputStyle: Int, gestureFloatingPreviewTextEnabled: Boolean, shouldShowUiToAcceptTypedWord: Boolean): Boolean {
        val omitTypedWord = inputStyle == SuggestedWords.INPUT_STYLE_TYPING
            || inputStyle == SuggestedWords.INPUT_STYLE_TAIL_BATCH
            || (inputStyle == SuggestedWords.INPUT_STYLE_UPDATE_BATCH && gestureFloatingPreviewTextEnabled)
        return shouldShowUiToAcceptTypedWord && omitTypedWord
    }

    /**
     * Position in the strip of the word at [indexInSuggestedWords]; negative when that word is not
     * shown in the strip.
     */
    @JvmStatic
    fun getPositionInSuggestionStrip(
        indexInSuggestedWords: Int, willAutoCorrect: Boolean, omitTypedWord: Boolean,
        centerPositionInStrip: Int, typedWordPositionWhenAutoCorrect: Int,
    ): Int {
        if (omitTypedWord) {
            if (indexInSuggestedWords == SuggestedWords.INDEX_OF_TYPED_WORD) return -1 // ignore
            if (indexInSuggestedWords == SuggestedWords.INDEX_OF_AUTO_CORRECTION) return centerPositionInStrip
            // left of the centre first, then right of it, out to both edges: center-1, center+1, center-2, ...
            val offsetFromCenter = if (indexInSuggestedWords % 2 == 0) -(indexInSuggestedWords / 2) else indexInSuggestedWords / 2
            return centerPositionInStrip + offsetFromCenter
        }
        val mostImportant: Int
        val secondMostImportant: Int
        if (willAutoCorrect) {
            mostImportant = SuggestedWords.INDEX_OF_AUTO_CORRECTION
            secondMostImportant = SuggestedWords.INDEX_OF_TYPED_WORD
        } else {
            mostImportant = SuggestedWords.INDEX_OF_TYPED_WORD
            secondMostImportant = SuggestedWords.INDEX_OF_AUTO_CORRECTION
        }
        if (indexInSuggestedWords == mostImportant) return centerPositionInStrip
        if (indexInSuggestedWords == secondMostImportant) return typedWordPositionWhenAutoCorrect
        // right of the centre first, then left of it: center+1, center-2, center+2, center-3, ...
        val n = indexInSuggestedWords + 1
        val offsetFromCenter = if (n % 2 == 0) -(n / 2) else n / 2
        return centerPositionInStrip + offsetFromCenter
    }

    /** The share of the row the word at [positionInStrip] gets. */
    @JvmStatic
    fun suggestionWeight(positionInStrip: Int, centerPositionInStrip: Int, centerSuggestionWeight: Float, suggestionsCountInStrip: Int): Float =
        if (positionInStrip == centerPositionInStrip) centerSuggestionWeight
        else (1.0f - centerSuggestionWeight) / (suggestionsCountInStrip - 1)

    /** Width available to the word text at [positionInStrip], after the paddings and dividers of the row. */
    @JvmStatic
    fun suggestionWidth(
        positionInStrip: Int, maxWidth: Int, padding: Int, dividerWidth: Int,
        centerPositionInStrip: Int, centerSuggestionWeight: Float, suggestionsCountInStrip: Int,
    ): Int {
        val paddings = padding * suggestionsCountInStrip
        val dividers = dividerWidth * (suggestionsCountInStrip - 1)
        val availableWidth = maxWidth - paddings - dividers
        return (availableWidth * suggestionWeight(positionInStrip, centerPositionInStrip, centerSuggestionWeight, suggestionsCountInStrip)).toInt()
    }

    /** How far the text has to be squeezed horizontally to fit [maxWidth]; 1 when it fits. */
    @JvmStatic
    fun textScaleX(textWidth: Int, maxWidth: Int): Float {
        if (textWidth <= maxWidth || maxWidth <= 0) return 1.0f
        return maxWidth / textWidth.toFloat()
    }

    @JvmStatic
    fun applyAlpha(color: Int, alpha: Float): Int {
        val newAlpha = (Color.alpha(color) * alpha).toInt()
        return Color.argb(newAlpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    /** The row's text, after scaling or ellipsizing it into one slot. */
    class FittedText(val text: String, val scaleX: Float)

    /**
     * The word squeezed to [maxWidth]: scaled down to [MIN_TEXT_XSCALE] and, past that, ellipsized
     * in the middle. Widths are measured the way the View strip did (glyph by glyph, rounded up,
     * bold when the word is highlighted bold).
     */
    @JvmStatic
    fun fit(text: String, bold: Boolean, maxWidth: Int, paint: TextPaint): FittedText {
        val scaleX = textScaleX(textWidth(text, bold, paint), maxWidth)
        if (scaleX >= MIN_TEXT_XSCALE) return FittedText(text, scaleX)
        paint.textScaleX = MIN_TEXT_XSCALE
        val ellipsized = TextUtils.ellipsize(text, paint, maxWidth.toFloat(), TextUtils.TruncateAt.MIDDLE).toString()
        paint.textScaleX = 1.0f
        return FittedText(ellipsized, MIN_TEXT_XSCALE)
    }

    @JvmStatic
    fun textWidth(text: CharSequence?, bold: Boolean, paint: TextPaint): Int {
        if (text.isNullOrEmpty()) return 0
        val widths = FloatArray(text.length)
        val saved = paint.typeface
        val saveScale = paint.textScaleX
        val count = try {
            paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            paint.textScaleX = 1.0f
            paint.getTextWidths(text, 0, text.length, widths)
        } finally {
            paint.typeface = saved
            paint.textScaleX = saveScale
        }
        var width = 0
        for (i in 0 until count) width += (widths[i] + 0.5f).roundToInt()
        return width
    }

    /**
     * Which words go where. [stripWidth] is the width of the row the words share, [padding] the
     * horizontal padding of one word and [dividerWidth] the width of a divider, all in px. A
     * stripWidth that is not known yet (0) lays the three slots out and corrects itself once the row
     * has been measured.
     */
    fun layout(
        style: Style, suggestedWords: SuggestedWords, stripWidth: Int, padding: Int, dividerWidth: Int,
        paint: TextPaint, debug: Boolean,
    ): Layout {
        if (suggestedWords.isPunctuationSuggestions) return layoutPunctuation(style, suggestedWords as PunctuationSuggestions)

        val wordCountToShow = suggestedWords.wordCountToShow
        val slots = arrayOfNulls<WordSlot>(style.suggestionsCountInStrip)
        val settings = Settings.getValues()
        val omitTypedWord = shouldOmitTypedWord(suggestedWords.mInputStyle, settings.mGestureFloatingPreviewTextEnabled, true)
        // the index after the last word that was put into the row; what "more suggestions" starts at
        var count = 0
        var indexInSuggestedWords = 0
        while (indexInSuggestedWords < suggestedWords.size() && count < style.suggestionsCountInStrip) {
            val position = getPositionInSuggestionStrip(
                indexInSuggestedWords, suggestedWords.mWillAutoCorrect, omitTypedWord,
                style.centerPositionInStrip, style.typedWordPositionWhenAutocorrect
            )
            if (position >= 0 && position < slots.size) {
                slots[position] = slot(style, suggestedWords, indexInSuggestedWords, debug)
                count++
            } else if (position >= slots.size) {
                // more slots asked for than the row has: nothing to show there, but it still counts as used
                count++
            }
            indexInSuggestedWords++
        }
        val start = indexInSuggestedWords

        val center = slots[style.centerPositionInStrip]
        val centerWidth = suggestionWidth(
            style.centerPositionInStrip, stripWidth, padding, dividerWidth,
            style.centerPositionInStrip, style.centerSuggestionWeight, style.suggestionsCountInStrip
        )
        val centerScale = textScaleX(textWidth(center?.label, center?.bold == true, paint), centerWidth)
        if (wordCountToShow == 1 || centerScale < MIN_TEXT_XSCALE) {
            // only the most relevant word, centred, over every slot of the row
            val lastIndex = center?.indexInSuggestedWords
            return Layout(slots.asList(), false, true, wordCountToShow > 1, (lastIndex ?: 0) + 1)
        }
        return Layout(slots.asList(), false, false, wordCountToShow > style.suggestionsCountInStrip, start)
    }

    private fun layoutPunctuation(style: Style, punctuationSuggestions: PunctuationSuggestions): Layout {
        val countInStrip = min(punctuationSuggestions.size(), PUNCTUATIONS_IN_STRIP)
        val slots = ArrayList<WordSlot?>(countInStrip)
        for (position in 0 until countInStrip) {
            val punctuation = punctuationSuggestions.getLabel(position)
            slots.add(WordSlot(position, punctuation, style.colorAutoCorrect, false, false, null))
        }
        return Layout(slots, true, false, punctuationSuggestions.size() > countInStrip, countInStrip)
    }

    private fun slot(style: Style, suggestedWords: SuggestedWords, index: Int, debug: Boolean): WordSlot {
        val isAutoCorrection = suggestedWords.mWillAutoCorrect && index == SuggestedWords.INDEX_OF_AUTO_CORRECTION
        val isTypedWordValid = suggestedWords.mTypedWordValid && index == SuggestedWords.INDEX_OF_TYPED_WORD
        val options = style.options
        val bold = (isAutoCorrection && options and AUTO_CORRECT_BOLD != 0) ||
            (isTypedWordValid && options and VALID_TYPED_WORD_BOLD != 0)
        val underline = isAutoCorrection && options and AUTO_CORRECT_UNDERLINE != 0
        return WordSlot(
            index, suggestedWords.getLabel(index), textColor(style, suggestedWords, index), bold, underline,
            if (debug) suggestedWords.getDebugString(index) else null
        )
    }

    private fun textColor(style: Style, suggestedWords: SuggestedWords, index: Int): Int {
        val isTypedWord = suggestedWords.getInfo(index).isKindOf(SuggestedWordInfo.KIND_TYPED)
        val color = when {
            index == SuggestedWords.INDEX_OF_AUTO_CORRECTION && suggestedWords.mWillAutoCorrect -> style.colorAutoCorrect
            isTypedWord && suggestedWords.mTypedWordValid -> style.colorValidTypedWord
            isTypedWord -> style.colorTypedWord
            else -> style.colorSuggested
        }
        return if (suggestedWords.mIsObsoleteSuggestions && !isTypedWord) applyAlpha(color, style.alphaObsoleted) else color
    }

    /** The theme-driven numbers of the strip (attributes of the strip's style, plus the keyboard colours). */
    class Style(context: Context, attrs: AttributeSet?, defStyle: Int) {
        val options: Int
        val alphaObsoleted: Float
        val suggestionsCountInStrip: Int
        val centerSuggestionWeight: Float
        val minMoreSuggestionsWidth: Float
        var maxMoreSuggestionsRow: Int
            private set
        val centerPositionInStrip: Int
        val typedWordPositionWhenAutocorrect: Int
        val moreSuggestionsBottomGap: Int
        val moreSuggestionsRowHeight: Int
        val colorValidTypedWord: Int
        val colorTypedWord: Int
        val colorAutoCorrect: Int
        val colorSuggested: Int
        val colorMoreSuggestionsHint: Int

        /** The divider between two words: the strip style's `android:src`, drawn at its intrinsic size. */
        val dividerDrawable: Drawable?
        val dividerWidth: Int get() = dividerDrawable?.intrinsicWidth ?: 0

        init {
            val res = context.resources
            val a = context.obtainStyledAttributes(attrs, R.styleable.SuggestionStripView, defStyle, R.style.SuggestionStripView)
            options = a.getInt(R.styleable.SuggestionStripView_suggestionStripOptions, 0)
            alphaObsoleted = ResourceUtils.getFraction(a, R.styleable.SuggestionStripView_alphaObsoleted, 1.0f)
            suggestionsCountInStrip = a.getInt(R.styleable.SuggestionStripView_suggestionsCountInStrip, DEFAULT_SUGGESTIONS_COUNT_IN_STRIP)
            centerSuggestionWeight = ResourceUtils.getFraction(a, R.styleable.SuggestionStripView_centerSuggestionPercentile, DEFAULT_CENTER_SUGGESTION_PERCENTILE)
            maxMoreSuggestionsRow = a.getInt(R.styleable.SuggestionStripView_maxMoreSuggestionsRow, DEFAULT_MAX_MORE_SUGGESTIONS_ROW)
            minMoreSuggestionsWidth = ResourceUtils.getFraction(a, R.styleable.SuggestionStripView_minMoreSuggestionsWidth, 1.0f)
            a.recycle()

            val divider = context.obtainStyledAttributes(attrs, intArrayOf(android.R.attr.src), defStyle, R.style.SuggestionStripView)
            dividerDrawable = divider.getDrawable(0)?.mutate()
            divider.recycle()

            val colors = Settings.getValues().mColors
            colorValidTypedWord = colors.get(ColorType.SUGGESTION_VALID_WORD)
            colorTypedWord = colors.get(ColorType.SUGGESTION_TYPED_WORD)
            colorAutoCorrect = colors.get(ColorType.SUGGESTION_AUTO_CORRECT)
            colorSuggested = colors.get(ColorType.SUGGESTED_WORD)
            colorMoreSuggestionsHint = colors.get(ColorType.MORE_SUGGESTIONS_HINT)

            centerPositionInStrip = suggestionsCountInStrip / 2
            // Assuming there are at least three suggestions. The suggestions are laid out according to
            // script direction, so this is left of the centre for LTR scripts and right of it for RTL ones.
            typedWordPositionWhenAutocorrect = centerPositionInStrip - 1
            moreSuggestionsBottomGap = res.getDimensionPixelOffset(R.dimen.config_more_suggestions_bottom_gap)
            moreSuggestionsRowHeight = res.getDimensionPixelSize(R.dimen.config_more_suggestions_row_height)
        }

        private val moreSuggestionsHeight get() = maxMoreSuggestionsRow * moreSuggestionsRowHeight + moreSuggestionsBottomGap

        /** Fewer more-suggestion rows when the room above the keyboard cannot hold the usual number. */
        fun setMoreSuggestionsHeight(remainingHeight: Int) {
            if (moreSuggestionsHeight <= remainingHeight) return
            maxMoreSuggestionsRow = (remainingHeight - moreSuggestionsBottomGap) / moreSuggestionsRowHeight
        }
    }
}
