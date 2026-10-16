/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */
package helium314.keyboard.keyboard.emoji

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import helium314.keyboard.keyboard.Key
import helium314.keyboard.keyboard.KeyDetector
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.keyboard.KeyboardView
import helium314.keyboard.keyboard.PopupKeysKeyboard
import helium314.keyboard.keyboard.PopupKeysKeyboardView
import helium314.keyboard.keyboard.PopupKeysPanel
import helium314.keyboard.keyboard.PopupTextView
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.InlineAutofillUtils
import helium314.keyboard.latin.utils.Log
import java.util.WeakHashMap

private const val TAG = "EmojiKeyGrid"
private const val KEY_PRESS_DELAY_TIME = 250L // msec
private const val KEY_RELEASE_DELAY_TIME = 30L // msec
// patch 0010: hold 5 seconds on any emoji to pin/unpin it
private const val EMOJI_PIN_HOLD_TIME = 5000L // msec

/**
 * The popup machinery of the emoji keys: the skin-tone / variant popup keys keyboard and the
 * emoji description, shown above the pressed key. They are the keyboard's own popup panels (Views,
 * placed in a layer over the window like the main keyboard's popups), so this keeps exactly the
 * code that placed them when the emoji page was a View: it inflates the theme's popup layout, owns a
 * placer layer and is the panels' controller.
 */
internal class EmojiKeyPopups(private val context: Context) : PopupKeysPanel.Controller {
    private val container: View
    private val descriptionView: PopupTextView
    private val popupKeysKeyboardView: PopupKeysKeyboardView
    private val popupKeysPlacerView: ViewGroup = InlineAutofillUtils.newHost(context)
    private val popupKeysKeyboardCache = WeakHashMap<Key, Keyboard>()
    private var panel: PopupKeysPanel? = null
    private var anchor: View? = null

    /** Whether the popup is placed at the touch point rather than centred over the key. */
    val showAtTouchedPoint: Boolean

    init {
        val keyboardViewAttr = context.obtainStyledAttributes(null, R.styleable.MainKeyboardView, R.attr.keyboardViewStyle, R.style.MainKeyboardView)
        val popupKeysKeyboardLayoutId = keyboardViewAttr.getResourceId(R.styleable.MainKeyboardView_popupKeysKeyboardLayout, 0)
        showAtTouchedPoint = keyboardViewAttr.getBoolean(R.styleable.MainKeyboardView_showPopupKeysKeyboardAtTouchedPoint, false)
        keyboardViewAttr.recycle()
        container = LayoutInflater.from(context).inflate(popupKeysKeyboardLayoutId, null)
        descriptionView = container.requireViewById(R.id.description_view)
        popupKeysKeyboardView = container.requireViewById(R.id.popup_keys_keyboard_view)
    }

    val isShowingPopupKeysPanel get() = panel != null

    /** A popup keys keyboard (not just the description) is up, and takes over the finger. */
    val isShowingPopupKeyboard get() = panel != null && popupKeysKeyboardView.visibility == View.VISIBLE

    fun clearCache() = popupKeysKeyboardCache.clear()

    /** The popup keys of [key] (skin tones and the like), ready to show, or null when it has none. */
    fun showPopupKeysKeyboard(key: Key, pageKeyboard: Keyboard, labelPaint: android.graphics.Paint): PopupKeysPanel? {
        popupKeysKeyboardView.visibility = View.GONE
        if (key.popupKeys == null) return null
        var popupKeysKeyboard = popupKeysKeyboardCache[key]
        if (popupKeysKeyboard == null) {
            popupKeysKeyboard = PopupKeysKeyboard.Builder(context, key, pageKeyboard, false, 0, 0, labelPaint).build()
            popupKeysKeyboardCache[key] = popupKeysKeyboard
        }
        popupKeysKeyboardView.setKeyboard(popupKeysKeyboard)
        popupKeysKeyboardView.visibility = View.VISIBLE
        return popupKeysKeyboardView
    }

    /** The emoji's description (when descriptions are on and the emoji has one), ready to show. */
    fun showDescription(key: Key, callback: EmojiViewCallback, renderer: KeyboardView): PopupKeysPanel? {
        descriptionView.visibility = View.GONE
        val description = callback.getDescription(key.label) ?: return null
        descriptionView.text = description
        descriptionView.setKeyDrawParams(key, renderer.keyDrawParams)
        descriptionView.visibility = View.VISIBLE
        return descriptionView
    }

    fun measureContainer() = container.measure(
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
    )

    /** Shows [shown] over [anchorView] (the Compose root all positions are relative to). */
    fun show(anchorView: View, shown: PopupKeysPanel, pointX: Int, pointY: Int, callback: EmojiViewCallback) {
        anchor = anchorView
        shown.showPopupKeysPanel(anchorView, this, pointX, pointY, callback)
    }

    fun dismiss() {
        panel?.dismissPopupKeysPanel()
    }

    // ---- PopupKeysPanel.Controller ----

    override fun setLayoutGravity(layoutGravity: Int) {
        val layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        layoutParams.gravity = layoutGravity
        container.layoutParams = layoutParams
        descriptionView.layoutParams = layoutParams
    }

    override fun onShowPopupKeysPanel(shown: PopupKeysPanel) {
        // install the placer layer only when needed instead of when the page is attached to the window
        installPopupKeysPlacerView(false)
        shown.showInParent(popupKeysPlacerView)
        panel = shown
    }

    override fun onDismissPopupKeysPanel() {
        val shown = panel ?: return
        shown.removeFromParent()
        panel = null
        installPopupKeysPlacerView(true)
    }

    override fun onCancelPopupKeysPanel() {
        if (isShowingPopupKeysPanel) dismiss()
    }

    private fun installPopupKeysPlacerView(uninstall: Boolean) {
        val rootView = anchor?.rootView
        if (rootView == null) {
            Log.w(TAG, "Cannot find root view")
            return
        }
        val windowContentView = try {
            rootView.requireViewById<ViewGroup>(android.R.id.content)
        } catch (_: IllegalArgumentException) {
            null
        }
        // Note: It'd be very weird if we get null by android.R.id.content.
        if (windowContentView == null) {
            Log.w(TAG, "Cannot find android.R.id.content view to add DrawingPreviewPlacerView")
            return
        }
        if (uninstall) windowContentView.removeView(popupKeysPlacerView) else windowContentView.addView(popupKeysPlacerView)
    }
}

/**
 * What a finger does on a page of emoji keys: a press shows after a short delay (so a fling does
 * not flash a key), a release inputs the emoji, a long press shows the variants and the description,
 * and a five second hold pins or unpins the emoji. The old EmojiPageKeyboardView, minus the View:
 * the finger arrives in page coordinates from the grid's pointer handler.
 */
internal class EmojiKeyTouch(
    private val keyboard: Keyboard,
    private val renderer: KeyboardView,
    private val callback: EmojiViewCallback,
    private val popups: EmojiKeyPopups,
    private val anchor: View,
    private val redraw: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val keyDetector = KeyDetector().also { it.setKeyboard(keyboard, 0f, 0f) }

    /** Where this page's top-left corner is in the anchor's coordinates (px); popups are placed in those. */
    var origin = Offset.Zero

    private var pointerId = 0
    private var lastX = 0
    private var lastY = 0
    private var currentKey: Key? = null
    private var pendingKeyDown: Runnable? = null
    private var pendingLongPress: Runnable? = null
    // patch 0010: independent 5-second timer for pin/unpin (additive to the ~500ms skin-tone timer)
    private var pendingPinEmoji: Runnable? = null
    /** The panel that is up (the variants or just the description); it takes the finger's release. */
    private var shownPanel: PopupKeysPanel? = null

    val isShowingPopupKeysPanel get() = popups.isShowingPopupKeysPanel
    val isShowingPopupKeyboard get() = popups.isShowingPopupKeyboard

    private fun getKey(x: Int, y: Int): Key? = keyDetector.detectHitKey(x, y)

    private fun hostX(x: Int) = (x + origin.x).toInt()
    private fun hostY(y: Int) = (y + origin.y).toInt()

    private fun registerPress(key: Key) {
        // Do not trigger key-down effect right now in case this is actually a fling action.
        val press = Runnable { callListenerOnPressKey(key) }
        pendingKeyDown = press
        handler.postDelayed(press, KEY_PRESS_DELAY_TIME)
    }

    private fun registerLongPress(key: Key) {
        val longPress = Runnable { onLongPressed(key) }
        pendingLongPress = longPress
        handler.postDelayed(longPress, Settings.getValues().mKeyLongpressTimeout.toLong())
        // patch 0010: start the independent 5-second pin timer alongside the ~500ms skin-tone timer
        val pin = Runnable { callback.onPinEmoji(key) }
        pendingPinEmoji = pin
        handler.postDelayed(pin, EMOJI_PIN_HOLD_TIME)
    }

    private fun callListenerOnReleaseKey(releasedKey: Key, withKeyRegistering: Boolean) {
        releasedKey.onReleased()
        redraw()
        if (withKeyRegistering) callback.onReleaseKey(releasedKey)
    }

    private fun callListenerOnPressKey(pressedKey: Key) {
        pendingKeyDown = null
        pressedKey.onPressed()
        redraw()
        callback.onPressKey(pressedKey)
    }

    fun releaseCurrentKey(withKeyRegistering: Boolean) {
        pendingKeyDown?.let { handler.removeCallbacks(it) }
        pendingKeyDown = null
        val key = currentKey ?: return
        callListenerOnReleaseKey(key, withKeyRegistering)
        currentKey = null
    }

    fun cancelLongPress() {
        pendingLongPress?.let { handler.removeCallbacks(it) }
        pendingLongPress = null
        // patch 0010: also cancel the pin timer (finger moved off key or released before 5s)
        pendingPinEmoji?.let { handler.removeCallbacks(it) }
        pendingPinEmoji = null
    }

    private fun onLongPressed(key: Key?) {
        if (popups.isShowingPopupKeysPanel) return
        if (key == null) return

        val descriptionPanel = popups.showDescription(key, callback, renderer)
        val popupKeysPanel = popups.showPopupKeysKeyboard(key, keyboard, renderer.newLabelPaint(key))
        val x = lastX
        val y = lastY
        val shown = popupKeysPanel ?: descriptionPanel
        shownPanel = shown
        if (shown != null) {
            popups.measureContainer()
            // The popup keys keyboard is usually horizontally aligned with the center of the parent key.
            // If showPopupKeysKeyboardAtTouchedPoint is true and the key preview is disabled, the more
            // keys keyboard is placed at the touch point of the parent key.
            val pointX = if (popups.showAtTouchedPoint) x else key.x + key.width / 2
            val pointY = key.y - keyboard.mVerticalGap
            popups.show(anchor, shown, hostX(pointX), hostY(pointY), callback)
        }
        if (popupKeysPanel != null) {
            popupKeysPanel.onDownEvent(popupKeysPanel.translateX(hostX(x)), popupKeysPanel.translateY(hostY(y)), pointerId, 0)
        }
    }

    /** The finger went down at [x], [y]; false when there is no key under it (the touch is not ours). */
    fun onDown(x: Int, y: Int): Boolean {
        val key = getKey(x, y)
        releaseCurrentKey(false)
        currentKey = key
        if (key == null) return false
        registerPress(key)
        registerLongPress(key)
        lastX = x
        lastY = y
        return true
    }

    fun onUp(x: Int, y: Int, eventTime: Long) {
        val key = getKey(x, y)
        val pendingKeyDownNow = pendingKeyDown
        val current = currentKey
        releaseCurrentKey(false)

        val shown = shownPanel
        if (popups.isShowingPopupKeysPanel) {
            // the variants take the release as the chosen key; a description alone inputs its own emoji
            shown?.onUpEvent(shown.translateX(hostX(x)), shown.translateY(hostY(y)), pointerId, eventTime)
            popups.dismiss()
        } else if (key != null && key === current && pendingKeyDownNow != null) {
            pendingKeyDownNow.run()
            // Trigger key-release event a little later so that a user can see visual feedback.
            handler.postDelayed({ callListenerOnReleaseKey(key, true) }, KEY_RELEASE_DELAY_TIME)
        } else if (key != null) {
            callListenerOnReleaseKey(key, true)
        }
        shownPanel = null
        cancelLongPress()
    }

    fun onCancel() {
        releaseCurrentKey(false)
        popups.dismiss()
        shownPanel = null
        cancelLongPress()
    }

    fun onMove(x: Int, y: Int, eventTime: Long) {
        val key = getKey(x, y)
        val showingPopupKeyboard = popups.isShowingPopupKeyboard

        // Touched key has changed, release previous key's callbacks and re-register them for the new key.
        if (key !== currentKey && !showingPopupKeyboard) {
            releaseCurrentKey(false)
            currentKey = key
            cancelLongPress()
            if (popups.isShowingPopupKeysPanel) popups.onCancelPopupKeysPanel()
            if (key == null) return
            registerPress(key)
            registerLongPress(key)
        }

        val shown = shownPanel
        if (showingPopupKeyboard && shown != null) {
            shown.onMoveEvent(shown.translateX(hostX(x)), shown.translateY(hostY(y)), pointerId, eventTime)
        }
        lastX = x
        lastY = y
    }

    /** Page leaves the composition: nothing may be left pressed or pending. */
    fun dispose() {
        releaseCurrentKey(false)
        cancelLongPress()
    }
}

/**
 * One page of emoji keys. The keys are drawn by a plain KeyboardView (the keyboard's key renderer,
 * so a key looks exactly as it always did) into the Compose canvas; what a finger does is
 * [EmojiKeyTouch]. [version] changes when the keys change in place (a search result, a recent).
 */
@Composable
internal fun EmojiKeyGrid(
    keyboard: DynamicGridKeyboard,
    callback: EmojiViewCallback,
    popups: EmojiKeyPopups,
    version: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val anchor = LocalView.current
    val density = LocalDensity.current
    var tick by remember { mutableIntStateOf(0) }
    val renderer = remember(keyboard) {
        KeyboardView(context, null).also {
            it.setDrawsEmojiPage(true)
            it.setKeyboard(keyboard)
        }
    }
    val touch = remember(keyboard, callback, popups, anchor) { EmojiKeyTouch(keyboard, renderer, callback, popups, anchor) { tick++ } }
    DisposableEffect(touch) {
        onDispose {
            touch.dispose()
            renderer.deallocateMemory()
        }
    }
    val width = keyboard.mOccupiedWidth
    val height = keyboard.occupiedHeight
    Canvas(
        modifier
            .size(with(density) { width.toDp() }, with(density) { height.toDp() })
            .onGloballyPositioned { touch.origin = it.positionInRoot() }
            // the keys are not individually reachable by a screen reader: the page says what it is
            .clearAndSetSemantics { }
            .pointerInput(touch) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!touch.onDown(down.position.x.toInt(), down.position.y.toInt())) return@awaitEachGesture
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null) {
                            touch.onCancel()
                            break
                        }
                        val x = change.position.x.toInt()
                        val y = change.position.y.toInt()
                        if (change.changedToUp()) {
                            // an open popup took the finger, so the release is not a scroll or a tap for anyone else
                            if (touch.isShowingPopupKeysPanel) change.consume()
                            touch.onUp(x, y, change.uptimeMillis)
                            break
                        }
                        if (touch.isShowingPopupKeysPanel) {
                            // no scroll while a popup is open: the finger belongs to the popup until it is lifted
                            change.consume()
                        } else if ((change.position - down.position).getDistance() > slop) {
                            // a scroll starts (the page or the pager takes the finger): the key press is cancelled
                            touch.onCancel()
                            break
                        }
                        touch.onMove(x, y, change.uptimeMillis)
                    }
                }
            }
    ) {
        tick // redraw when a key is pressed or released
        version // ... and when the keys changed
        if (renderer.width != width || renderer.height != height) {
            renderer.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            )
            renderer.layout(0, 0, width, height)
        }
        drawIntoCanvas { renderer.draw(it.nativeCanvas) }
    }
}
