/*
 * Copyright (C) 2011 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */
package helium314.keyboard.latin.suggestions

import android.content.Context
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.diegonmarcos.superapp.uikit.KitImeComposeView
import com.diegonmarcos.superapp.uikit.KitPalette
import helium314.keyboard.keyboard.Key
import helium314.keyboard.keyboard.KeyDetector
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.keyboard.KeyboardTypeface
import helium314.keyboard.keyboard.MainKeyboardView
import helium314.keyboard.keyboard.PopupKeysDetector
import helium314.keyboard.keyboard.PopupKeysPanel
import helium314.keyboard.keyboard.emoji.EmojiViewCallback
import helium314.keyboard.latin.R
import helium314.keyboard.latin.SuggestedWords
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.CoordinateUtils
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.suggestions.MoreSuggestions.MoreSuggestionKey
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.TypefaceUtils
import helium314.keyboard.latin.utils.toKitPalette
import androidx.compose.ui.unit.IntOffset
import kotlin.math.abs

/**
 * The "more suggestions" panel: the suggestions that did not fit the strip, in a popup over the
 * keyboard. The words are laid out by [MoreSuggestions] (the same keyboard model as ever) and
 * drawn here in Compose under the keyboard theme. Picking works exactly as it did when this was a
 * popup-keys keyboard view, because it still is a [PopupKeysPanel] that the main keyboard view
 * places in its popup layer: the strip forwards a sliding touch to [touchEvent], and a finger
 * lifted without sliding leaves the panel up for a tap (the modal mode).
 */
class MoreSuggestionsView(context: Context) : KitImeComposeView(context), PopupKeysPanel {

    lateinit var listener: SuggestionStripView.Listener
    lateinit var mainKeyboardView: MainKeyboardView
    lateinit var gestureDetector: GestureDetector

    private val moreSuggestionsListener = object : KeyboardActionListener.Adapter() {
        override fun onCancelInput() {
            dismissPopupKeysPanel()
        }
    }

    private val moreSuggestionsController: PopupKeysPanel.Controller = object : PopupKeysPanel.Controller {
        override fun onDismissPopupKeysPanel() {
            mainKeyboardView.onDismissPopupKeysPanel()
        }

        override fun onShowPopupKeysPanel(panel: PopupKeysPanel) {
            mainKeyboardView.onShowPopupKeysPanel(panel)
        }

        override fun onCancelPopupKeysPanel() {
            dismissPopupKeysPanel()
        }
    }

    private val colors = Settings.getValues().mColors
    private val moreSuggestionsModalTolerance = context.resources.getDimensionPixelOffset(R.dimen.config_more_suggestions_modal_tolerance)
    private val keyDetector: KeyDetector = PopupKeysDetector(context.resources.getDimension(R.dimen.config_popup_keys_keyboard_slide_allowance))
    private val labelSize = context.resources.getDimension(R.dimen.config_suggestion_text_size)

    // the look of a popup panel, from the keyboard theme's popup style
    private val panelPadding = Rect()
    private val panelBackground: Drawable?
    private val keyBackground: Drawable
    private val keyBackgroundPadding = Rect()
    private val verticalCorrection: Float

    init {
        val panel = context.obtainStyledAttributes(
            null, intArrayOf(android.R.attr.background), R.attr.popupKeysKeyboardViewStyle, R.style.PopupKeysKeyboardView
        )
        panelBackground = panel.getDrawable(0)?.mutate()
        panel.recycle()
        panelBackground?.let {
            // MORE_SUGGESTIONS_BACKGROUND is applied to a view's background; hand it a stand-in view
            val standIn = View(context)
            standIn.background = it
            colors.setBackground(standIn, ColorType.MORE_SUGGESTIONS_BACKGROUND)
            it.getPadding(panelPadding)
        }
        val keys = context.obtainStyledAttributes(null, R.styleable.KeyboardView, R.attr.popupKeysKeyboardViewStyle, R.style.KeyboardView)
        keyBackground = colors.selectAndColorDrawable(keys, ColorType.MORE_SUGGESTIONS_WORD_BACKGROUND)
        keyBackground.getPadding(keyBackgroundPadding)
        verticalCorrection = keys.getDimension(R.styleable.KeyboardView_verticalCorrection, 0f)
        keys.recycle()
    }

    private val labelPaint = Paint().apply {
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        textSize = labelSize * Settings.getValues().mFontSizeMultiplier
    }

    /** What the [MoreSuggestions] model measures its words with: the panel's own text size. */
    private val measuringPaint = Paint().apply {
        isAntiAlias = true
        typeface = Typeface.DEFAULT
        textSize = labelSize
    }

    private val moreSuggestionsBuilder by lazy { MoreSuggestions.Builder(context, measuringPaint) }

    private var keyboard by mutableStateOf<MoreSuggestions?>(null)
    private var pressedKey by mutableStateOf<Key?>(null)
    private var isInModalMode = false

    private var controller: PopupKeysPanel.Controller = PopupKeysPanel.EMPTY_CONTROLLER
    private var originX = 0
    private var originY = 0
    private val coordinates = CoordinateUtils.newInstance()

    // Working variables for shouldInterceptTouchEvent.
    private var lastX = 0
    private var lastY = 0
    private var touchOriginX = 0
    private var touchOriginY = 0

    override fun palette(): KitPalette = Settings.getValues().mColors.toKitPalette()

    // ---- drawing --------------------------------------------------------------------------------

    @Composable
    override fun Body() {
        val kb = keyboard ?: return
        val pressed = pressedKey
        val textColor = colors.get(ColorType.POPUP_KEY_TEXT)
        val density = LocalDensity.current
        Box(Modifier.fillMaxSize().pointerInput(kb) { handlePointers() }) {
            Canvas(Modifier.fillMaxSize()) {
                drawIntoCanvas { canvas ->
                    val c = canvas.nativeCanvas
                    panelBackground?.let {
                        it.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                        it.draw(c)
                    }
                    c.save()
                    c.translate(panelPadding.left.toFloat(), panelPadding.top.toFloat())
                    for (key in kb.sortedKeys) drawKey(c, kb, key, key === pressed, textColor)
                    c.restore()
                }
            }
            // what a screen reader walks: one button per suggestion, picking it like a tap does
            for (key in kb.sortedKeys) {
                if (key !is MoreSuggestionKey) continue
                val label = key.label ?: continue
                Box(Modifier
                    .offset { IntOffset(key.x + panelPadding.left, key.y + panelPadding.top) }
                    .requiredSize(with(density) { key.width.toDp() }, with(density) { key.height.toDp() })
                    .semantics {
                        contentDescription = label
                        role = Role.Button
                        onClick(label) { onKeyInput(key); true }
                    })
            }
        }
    }

    private fun drawKey(canvas: android.graphics.Canvas, kb: MoreSuggestions, key: Key, pressed: Boolean, textColor: Int) {
        canvas.save()
        canvas.translate(key.drawX.toFloat(), key.y.toFloat())
        val keyWidth = key.drawWidth
        val keyHeight = key.height
        if (key.isSpacer) {
            // the divider between two words
            key.getIcon(kb.mIconsSet, 255)?.let { icon ->
                val iconWidth = minOf(icon.intrinsicWidth, keyWidth)
                val iconHeight = icon.intrinsicHeight
                val left = (keyWidth - iconWidth) / 2
                val top = (keyHeight - iconHeight) / 2
                icon.setBounds(left, top, left + iconWidth, top + iconHeight)
                icon.draw(canvas)
            }
        } else {
            keyBackground.setState(if (pressed) PRESSED else UNPRESSED)
            keyBackground.setBounds(-keyBackgroundPadding.left, -keyBackgroundPadding.top,
                keyWidth + keyBackgroundPadding.right, keyHeight + keyBackgroundPadding.bottom)
            keyBackground.draw(canvas)
            val label = key.label
            if (label != null) {
                labelPaint.typeface = KeyboardTypeface.resolve(label, Typeface.DEFAULT)
                labelPaint.color = textColor
                val baseline = keyHeight * 0.5f + TypefaceUtils.getReferenceCharHeight(labelPaint) / 2.0f
                canvas.drawText(label, 0, label.length, keyWidth * 0.5f, baseline, labelPaint)
            }
        }
        canvas.restore()
    }

    // ---- touches the panel gets itself (the modal mode: a tap after the finger was lifted) -------

    private suspend fun PointerInputScope.handlePointers() {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            onDownEvent(down.position.x.toInt(), down.position.y.toInt(), POINTER, down.uptimeMillis)
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                val x = change.position.x.toInt()
                val y = change.position.y.toInt()
                if (change.changedToUp()) {
                    onUpEvent(x, y, POINTER, change.uptimeMillis)
                    break
                }
                if (change.positionChanged()) onMoveEvent(x, y, POINTER, change.uptimeMillis)
            }
        }
    }

    // ---- PopupKeysPanel -------------------------------------------------------------------------

    override fun getContainerView(): View = this

    override fun showPopupKeysPanel(parentView: View, controller: PopupKeysPanel.Controller, pointX: Int, pointY: Int, listener: KeyboardActionListener) {
        showPopupKeysPanelInternal(parentView, controller, pointX, pointY)
    }

    override fun showPopupKeysPanel(parentView: View, controller: PopupKeysPanel.Controller, pointX: Int, pointY: Int, emojiViewCallback: EmojiViewCallback) {
        showPopupKeysPanelInternal(parentView, controller, pointX, pointY)
    }

    private fun showPopupKeysPanelInternal(parentView: View, controller: PopupKeysPanel.Controller, pointX: Int, pointY: Int) {
        this.controller = controller
        val kb = keyboard ?: return
        val panelWidth = kb.mOccupiedWidth + panelPadding.left + panelPadding.right
        val panelHeight = kb.mOccupiedHeight + panelPadding.top + panelPadding.bottom
        // Exact size: a Compose view cannot be measured before it is attached.
        layoutParams = ViewGroup.LayoutParams(panelWidth, panelHeight)

        // The coordinates of the panel's left-top corner in parentView's coordinate system; the
        // background drawable's paddings are part of the size.
        val x = pointX - kb.mOccupiedWidth / 2 - panelPadding.left
        val y = pointY - panelHeight + panelPadding.bottom
        parentView.getLocationInWindow(coordinates)
        setY((y + CoordinateUtils.y(coordinates)).toFloat())
        // Keep the panel inside the parent's width.
        val panelMaxX = parentView.measuredWidth - panelWidth
        val panelFinalX = maxOf(0, minOf(panelMaxX, x))
        setX((panelFinalX + CoordinateUtils.x(coordinates)).toFloat())

        originX = panelFinalX
        originY = y + panelPadding.top
        controller.onShowPopupKeysPanel(this)
    }

    override fun dismissPopupKeysPanel() {
        if (!isShowingInParent) return
        controller.onDismissPopupKeysPanel()
        pressedKey = null
    }

    override fun onDownEvent(x: Int, y: Int, pointerId: Int, eventTime: Long) {
        pressedKey = detectKey(x, y)
    }

    override fun onMoveEvent(x: Int, y: Int, pointerId: Int, eventTime: Long) {
        val hadKey = pressedKey != null
        pressedKey = detectKey(x, y)
        // A popup panel is canceled when no key is detected any more.
        if (hadKey && pressedKey == null) controller.onCancelPopupKeysPanel()
    }

    override fun onUpEvent(x: Int, y: Int, pointerId: Int, eventTime: Long) {
        // Detecting again is harmless: the last move event and the up event share their coordinates.
        val key = detectKey(x, y)
        pressedKey = null
        if (key != null) onKeyInput(key)
    }

    override fun translateX(x: Int): Int = x - originX

    override fun translateY(y: Int): Int = y - originY

    private fun detectKey(x: Int, y: Int): Key? = keyDetector.detectHitKey(x, y)

    private fun setDetectorCorrection(vertical: Float) {
        val kb = keyboard ?: return
        keyDetector.setKeyboard(kb, -panelPadding.left.toFloat(), -panelPadding.top.toFloat() + vertical)
    }

    private fun onKeyInput(key: Key) {
        if (key !is MoreSuggestionKey) {
            Log.e(TAG, "Expected key is MoreSuggestionKey, but found ${key.javaClass.name}")
            return
        }
        val suggestedWords = keyboard?.mSuggestedWords ?: return
        val index = key.mSuggestedWordIndex
        if (index < 0 || index >= suggestedWords.size()) {
            Log.e(TAG, "Selected suggestion has an illegal index: $index")
            return
        }
        listener.pickSuggestionManually(suggestedWords.getInfo(index))
        dismissPopupKeysPanel()
    }

    private fun setModalMode() {
        isInModalMode = true
        // Vertical correction to zero: the popup keys' slide allowance does not apply to a tap.
        setDetectorCorrection(0f)
    }

    // ---- what the strip drives ------------------------------------------------------------------

    internal fun show(suggestedWords: SuggestedWords, fromIndex: Int, parentView: View, style: SuggestionStripLayout.Style): Boolean {
        val maxWidth = parentView.width
        val parentKeyboard = mainKeyboardView.keyboard ?: return false
        keyboard = moreSuggestionsBuilder.layout(
            suggestedWords, fromIndex, maxWidth,
            (maxWidth * style.minMoreSuggestionsWidth).toInt(),
            style.maxMoreSuggestionsRow, parentKeyboard
        ).build()
        isInModalMode = false
        setDetectorCorrection(verticalCorrection)

        val pointX = parentView.width / 2
        val pointY = -style.moreSuggestionsBottomGap
        showPopupKeysPanel(parentView, moreSuggestionsController, pointX, pointY, moreSuggestionsListener)
        touchOriginX = lastX
        touchOriginY = lastY
        return true
    }

    fun shouldInterceptTouchEvent(motionEvent: MotionEvent): Boolean {
        if (!isShowingInParent) {
            lastX = motionEvent.x.toInt()
            lastY = motionEvent.y.toInt()
            return gestureDetector.onTouchEvent(motionEvent)
        }
        if (isInModalMode) return false

        val index = motionEvent.actionIndex
        if (abs(motionEvent.getX(index).toInt() - touchOriginX) >= moreSuggestionsModalTolerance
            || touchOriginY - motionEvent.getY(index).toInt() >= moreSuggestionsModalTolerance
        ) {
            // Decided to be in the sliding suggestion mode only when the touch point has been moved
            // upward. Further MotionEvents will be delivered to SuggestionStripView.onTouchEvent.
            return true
        }

        if (motionEvent.action == MotionEvent.ACTION_UP || motionEvent.action == MotionEvent.ACTION_POINTER_UP) {
            // Decided to be in the modal input mode.
            setModalMode()
        }
        return false
    }

    /** A sliding touch the strip forwards (in the strip's coordinates) while the panel is up. */
    fun touchEvent(motionEvent: MotionEvent) {
        if (!isShowingInParent) return // Ignore any touch event while the panel hasn't been shown.
        val index = motionEvent.actionIndex
        val x = translateX(motionEvent.getX(index).toInt())
        val y = translateY(motionEvent.getY(index).toInt())
        when (motionEvent.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> onDownEvent(x, y, POINTER, motionEvent.eventTime)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> onUpEvent(x, y, POINTER, motionEvent.eventTime)
            MotionEvent.ACTION_MOVE -> onMoveEvent(x, y, POINTER, motionEvent.eventTime)
        }
    }

    companion object {
        private val TAG = MoreSuggestionsView::class.java.simpleName
        private const val POINTER = 0
        private val PRESSED = intArrayOf(android.R.attr.state_pressed)
        private val UNPRESSED = intArrayOf()
    }
}
