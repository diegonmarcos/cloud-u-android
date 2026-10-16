/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */
package helium314.keyboard.keyboard.emoji

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.diegonmarcos.superapp.media.MediaPanelView
import com.diegonmarcos.superapp.media.MediaRuntime
import com.diegonmarcos.superapp.media.MediaType
import com.diegonmarcos.superapp.uikit.KitImeComposeView
import com.diegonmarcos.superapp.uikit.KitPalette
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.keyboard.Key
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.keyboard.KeyboardId
import helium314.keyboard.keyboard.KeyboardLayoutSet
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.MainKeyboardView
import helium314.keyboard.keyboard.PointerTracker
import helium314.keyboard.keyboard.internal.KeyVisualAttributes
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.keyboard.internal.keyboard_parser.getEmojiNeutralVersion
import helium314.keyboard.latin.AudioAndHapticFeedbackManager
import helium314.keyboard.latin.R
import helium314.keyboard.latin.RichInputMethodManager
import helium314.keyboard.latin.RichInputMethodSubtype
import helium314.keyboard.latin.SingleDictionaryFacilitator
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.dictionary.DictionaryFactory
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.DictionaryInfoUtils
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.latin.utils.toKitPalette

/**
 * The emoji palette: the category pages (swipe sideways to change category, scroll down a
 * category's pages), the thin page indicator, the Emoji / Sticker / GIF switch when the media panel
 * is on, and the bottom row (back to ABC, space, delete) which is a keyboard and so stays a View.
 *
 * Compose under the keyboard theme. The emoji keys themselves are drawn and handled by
 * [EmojiKeyGrid]; the category tabs live in [EmojiCategoryTabs], in the strip's place.
 */
class EmojiPalettesView(context: Context, attrs: AttributeSet?, defStyle: Int) :
    KitImeComposeView(context, attrs), EmojiViewCallback {

    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, R.attr.emojiPalettesViewStyle)

    private val colors = Settings.getValues().mColors
    private val emojiCategory: EmojiCategory
    private var emojiLayout by mutableStateOf(EmojiLayoutParams(context.resources))
    private var keyboardWidthPx by mutableIntStateOf(0)
    private var secondaryHeightPx by mutableIntStateOf(0)

    private var keyboardActionListener: KeyboardActionListener = KeyboardActionListener.EMPTY_LISTENER
    private var tabs: EmojiCategoryTabs? = null
    private val popups by lazy { EmojiKeyPopups(context) }
    private val listStates = HashMap<Int, LazyListState>()

    // set by initialize(); the content draws nothing before
    private var ready by mutableStateOf(false)
    private var initialCategoryId = EmojiCategory.ID_RECENTS
    private var initialCategoryPageId = 0
    private var currentCategoryId by mutableIntStateOf(EmojiCategory.ID_RECENTS)
    private var settledCategoryId = EmojiCategory.ID_RECENTS
    /** Bumped when everything cached about the pages is dropped (the keyboard layout changed). */
    private var cacheEpoch by mutableIntStateOf(0)
    /** Bumped for one category when its keys changed in place (a recent used, an emoji pinned). */
    private val categoryEpochs = mutableStateMapOf<Int, Int>()
    private var sideLeftPx by mutableIntStateOf(0)
    private var sideRightPx by mutableIntStateOf(0)

    // SuperApp (patch 0007): Emoji/Sticker/GIF type-tab row + the Sticker/GIF body.
    private var typeTabRowShown by mutableStateOf(false)
    private var stickersAvailable by mutableStateOf(false)
    private var gifsAvailable by mutableStateOf(false)
    private var mediaType by mutableStateOf(MediaType.EMOJI)
    private val mediaPanel by lazy { MediaPanelView(context) }

    /** The bottom row keyboard; created here, handed to the Compose tree when it asks for it. */
    private val bottomRow by lazy { MainKeyboardView(context, null) }

    init {
        val builder = KeyboardLayoutSet.Builder(context, null)
        builder.setSubtype(RichInputMethodSubtype.emojiSubtype)
        builder.setKeyboardGeometry(
            ResourceUtils.getKeyboardWidth(context, Settings.getValues()),
            emojiLayout.emojiKeyboardHeight
        )
        val layoutSet = builder.build()
        val emojiPalettesViewAttr = context.obtainStyledAttributes(attrs, R.styleable.EmojiPalettesView, defStyle, R.style.EmojiPalettesView)
        emojiCategory = EmojiCategory(context, layoutSet, emojiPalettesViewAttr)
        emojiPalettesViewAttr.recycle()
        fitsSystemWindows = true
        refreshGeometry()
    }

    override fun palette(): KitPalette = Settings.getValues().mColors.toKitPalette()

    // ---- setup --------------------------------------------------------------------------------

    private fun refreshGeometry() {
        val sv = Settings.getValues()
        emojiLayout = EmojiLayoutParams(resources)
        keyboardWidthPx = ResourceUtils.getKeyboardWidth(context, sv)
        secondaryHeightPx = ResourceUtils.getSecondaryKeyboardHeight(resources, sv)
    }

    fun initialize() { // needs to be delayed for access to the category tabs, which are not a child of this view
        if (ready) return
        emojiCategory.initialize()
        val strip = KeyboardSwitcher.getInstance().emojiTabStrip as? EmojiCategoryTabs
        tabs = strip
        if (Settings.getValues().mSecondaryStripVisible) {
            strip?.setTabs(
                emojiCategory.shownCategories.map {
                    EmojiCategoryTabs.Tab(it.mCategoryId, emojiCategory.getCategoryTabIcon(it.mCategoryId),
                        emojiCategory.getAccessibilityDescription(it.mCategoryId))
                },
                ::onCategoryTabClick
            )
        }
        initialCategoryId = emojiCategory.currentCategoryId
        initialCategoryPageId = emojiCategory.currentCategoryPageId
        settledCategoryId = initialCategoryId
        setCurrentCategoryId(initialCategoryId, true)
        setupMediaPanel()
        ready = true
    }

    // SuperApp (patch 0007): wire the Emoji/Sticker/GIF type tabs + media body.
    // No-op (row stays hidden) unless build.json::keyboard_media.enabled and at
    // least one of stickers / GIFs is available — so pure-emoji behavior is
    // unchanged when the feature is off.
    private fun setupMediaPanel() {
        val stickers = MediaRuntime.stickersEnabled
        val gifs = MediaRuntime.gif != null
        if (!MediaRuntime.enabled || (!stickers && !gifs)) {
            typeTabRowShown = false
            return
        }
        typeTabRowShown = true
        stickersAvailable = stickers
        gifsAvailable = gifs
    }

    // SuperApp (patch 0007): swap the surface between the emoji pager and the
    // Sticker/GIF body. While a media type is active, hide the (external) emoji
    // category strip so only the media panel's own category row (row 2) shows.
    private fun showMediaType(type: MediaType) {
        val emoji = type == MediaType.EMOJI
        mediaType = type
        // #843: the category strip belongs to KeyboardSwitcher's view-state model.
        KeyboardSwitcher.getInstance().setEmojiCategoryStripShown(emoji)
        if (!emoji) mediaPanel.showType(type)
    }

    // patch 0011: patch 0010's in-panel EditText search (always-visible, emoji-only)
    // is removed entirely — replaced by EmojiSearchBarView, toggled from the existing
    // bottom-row magnifier key (see KeyCode.EMOJI_SEARCH in InputLogic).

    /** A tap on a category tab (the tabs are in the strip, outside this view). */
    private fun onCategoryTabClick(categoryId: Int) {
        AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, this, HapticEvent.KEY_PRESS)
        if (categoryId != emojiCategory.currentCategoryId) setCurrentCategoryId(categoryId, false)
    }

    private fun setCurrentCategoryId(categoryId: Int, initial: Boolean) {
        val oldCategoryId = emojiCategory.currentCategoryId
        if (initial || oldCategoryId != categoryId) {
            emojiCategory.currentCategoryId = categoryId
            currentCategoryId = categoryId
            tabs?.selectedCategoryId = categoryId
        }
    }

    // ---- EmojiViewCallback ----------------------------------------------------------------------

    /**
     * Called from [EmojiKeyGrid] to handle touch events on the emoji keys.
     */
    override fun onPressKey(key: Key) {
        val code = key.code
        keyboardActionListener.onPressKey(code, 0, true, HapticEvent.KEY_PRESS)
    }

    /**
     * Called from [EmojiKeyGrid] to handle touch events on the emoji keys.
     * This may be called without any prior call to [onPressKey].
     */
    override fun onReleaseKey(key: Key) {
        addRecentKey(key)
        val code = key.code
        if (code == KeyCode.MULTIPLE_CODE_POINTS) {
            keyboardActionListener.onTextInput(key.outputText)
        } else {
            keyboardActionListener.onCodeInput(code, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
        }
        keyboardActionListener.onReleaseKey(code, false)
        if (Settings.getValues().mAlphaAfterEmojiInEmojiView)
            keyboardActionListener.onCodeInput(KeyCode.ALPHA, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
    }

    override fun getDescription(emoji: String): String? {
        val facilitator = sDictionaryFacilitator ?: return null
        val wordProperty = facilitator.getWordProperty(getEmojiNeutralVersion(emoji))
        if (wordProperty == null || !wordProperty.mHasShortcuts) return null
        return wordProperty.mShortcutTargets[0].mWord
    }

    // patch 0010: EmojiViewCallback.onPinEmoji — toggle pin/unpin, persist, refresh tab, toast.
    override fun onPinEmoji(key: Key) {
        if (Settings.getValues().mIncognitoModeEnabled) return
        val pinned = pinnedKeyboard.addOrRemovePinnedKey(key)
        if (ready) bumpCategory(EmojiCategory.ID_PINNED)
        val msgRes = if (pinned) R.string.emoji_pinned_toast else R.string.emoji_unpinned_toast
        Toast.makeText(context, msgRes, Toast.LENGTH_SHORT).show()
    }

    fun addRecentKey(key: Key) {
        if (Settings.getValues().mIncognitoModeEnabled) {
            // We do not want to log recent keys while being in incognito
            return
        }
        if (visibility == VISIBLE && emojiCategory.isInRecentTab) {
            recentsKeyboard.addPendingKey(key)
            return
        }
        recentsKeyboard.addKeyFirst(key)
        if (ready) bumpCategory(EmojiCategory.ID_RECENTS)
    }

    private fun bumpCategory(categoryId: Int) {
        categoryEpochs[categoryId] = (categoryEpochs[categoryId] ?: 0) + 1
    }

    // ---- what KeyboardSwitcher drives -----------------------------------------------------------

    fun setHardwareAcceleratedDrawingEnabled(enabled: Boolean) {
        // The pages are drawn in the Compose canvas, which is hardware accelerated when the window is.
    }

    fun startEmojiPalettes(keyVisualAttr: KeyVisualAttributes?, editorInfo: EditorInfo?, keyboardActionListener: KeyboardActionListener) {
        initialize()
        // the sizes follow the settings, and change when the keyboard goes floating
        refreshGeometry()

        // SuperApp (patch 0007): route Sticker/GIF picks through the keyboard's
        // existing rich-content path, and always (re)open on the Emoji tab.
        if (typeTabRowShown) {
            mediaPanel.bind { info -> keyboardActionListener.onContent(info) }
            showMediaType(MediaType.EMOJI)
        }

        setupBottomRowKeyboard(editorInfo, keyboardActionListener)
        setupSidePadding()
        initDictionaryFacilitator()
    }

    private fun setupBottomRowKeyboard(editorInfo: EditorInfo?, keyboardActionListener: KeyboardActionListener) {
        bottomRow.setKeyboardActionListener(keyboardActionListener)
        PointerTracker.switchTo(bottomRow)
        val kls = KeyboardLayoutSet.Builder.buildEmojiClipBottomRow(context, editorInfo)
        val keyboard = kls.getKeyboard(KeyboardId.ELEMENT_EMOJI_BOTTOM_ROW)
        bottomRow.setKeyboard(keyboard)
    }

    private fun setupSidePadding() {
        val sv = Settings.getValues()
        val keyboardWidth = ResourceUtils.getKeyboardWidth(context, sv)
        val keyboardAttr = context.obtainStyledAttributes(null, R.styleable.Keyboard, R.attr.keyboardStyle, R.style.Keyboard)
        val leftPadding = keyboardAttr.getFraction(R.styleable.Keyboard_keyboardLeftPadding, keyboardWidth, keyboardWidth, 0f) * sv.mSidePaddingScale
        val rightPadding = keyboardAttr.getFraction(R.styleable.Keyboard_keyboardRightPadding, keyboardWidth, keyboardWidth, 0f) * sv.mSidePaddingScale
        keyboardAttr.recycle()
        sideLeftPx = leftPadding.toInt()
        sideRightPx = rightPadding.toInt()
    }

    fun stopEmojiPalettes() {
        if (!ready) return
        recentsKeyboard.flushPendingRecentKeys()
    }

    private val recentsKeyboard get() = emojiCategory.getKeyboard(EmojiCategory.ID_RECENTS, 0)

    // patch 0010
    private val pinnedKeyboard get() = emojiCategory.getKeyboard(EmojiCategory.ID_PINNED, 0)

    fun setKeyboardActionListener(listener: KeyboardActionListener) {
        keyboardActionListener = listener
    }

    private fun isAnimationsDisabled() = android.provider.Settings.Global.getFloat(
        context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1.0f
    ) == 0.0f

    fun clearKeyboardCache() {
        if (!ready) return
        emojiCategory.clearKeyboardCache()
        popups.clearCache()
        cacheEpoch++
        closeDictionaryFacilitator()
    }

    private fun initDictionaryFacilitator() {
        if (Settings.getValues().mShowEmojiDescriptions) {
            val locale = RichInputMethodManager.getInstance().currentSubtype.locale
            val current = sDictionaryFacilitator
            if (current == null || !current.isForLocale(locale)) {
                closeDictionaryFacilitator()
                val dictFile = DictionaryInfoUtils.getCachedDictForLocaleAndType(locale, Dictionary.TYPE_EMOJI, context)
                val dictionary = if (dictFile != null) DictionaryFactory.getDictionary(dictFile, locale) else null
                sDictionaryFacilitator = if (dictionary != null) SingleDictionaryFacilitator(dictionary) else null
            }
        } else {
            closeDictionaryFacilitator()
        }
    }

    // ---- drawing --------------------------------------------------------------------------------

    @Composable
    override fun Body() {
        if (!ready) return
        val density = LocalDensity.current
        val categories = emojiCategory.shownCategories
        val pagerState = rememberPagerState(initialPage = emojiCategory.getTabIdFromCategoryId(initialCategoryId)) { categories.size }

        // A swipe that settled on a category makes it the current one; the tab strip follows.
        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.settledPage }.collect { page ->
                val newId = categories.getOrNull(page)?.mCategoryId ?: return@collect
                if (settledCategoryId == EmojiCategory.ID_RECENTS && newId != EmojiCategory.ID_RECENTS) {
                    // Needs to save pending updates for recent keys when we get out of the recents
                    // category because we don't want to move the recent emojis around while the user
                    // is in the recents category.
                    recentsKeyboard.flushPendingRecentKeys()
                    bumpCategory(EmojiCategory.ID_RECENTS)
                }
                settledCategoryId = newId
                if (newId != emojiCategory.currentCategoryId) setCurrentCategoryId(newId, false)
            }
        }
        // A category chosen another way (a tab) moves the pager there.
        LaunchedEffect(currentCategoryId) {
            val target = emojiCategory.getTabIdFromCategoryId(currentCategoryId)
            if (pagerState.currentPage != target && !pagerState.isScrollInProgress) {
                if (isAnimationsDisabled()) pagerState.scrollToPage(target) else pagerState.animateScrollToPage(target)
            }
        }

        val tabRowHeight = if (typeTabRowShown) TYPE_TAB_ROW_HEIGHT else 0.dp
        Column(Modifier
            .width(with(density) { keyboardWidthPx.toDp() })
            .height(with(density) { secondaryHeightPx.toDp() } + tabRowHeight)) {
            if (typeTabRowShown) TypeTabRow()
            if (mediaType == MediaType.EMOJI) {
                HorizontalPager(
                    pagerState,
                    Modifier.fillMaxWidth()
                        .height(with(density) { emojiLayout.emojiKeyboardHeight.toDp() })
                        .padding(start = with(density) { sideLeftPx.toDp() }, end = with(density) { sideRightPx.toDp() }),
                    beyondViewportPageCount = 1,
                    key = { categories[it].mCategoryId },
                ) { page -> CategoryPages(categories[page].mCategoryId) }
                Box(Modifier.fillMaxWidth().height(with(density) { emojiLayout.categoryPageIdViewHeight.toDp() })) {
                    PageIndicator()
                }
            } else {
                AndroidView(
                    factory = { (mediaPanel.parent as? ViewGroup)?.removeView(mediaPanel); mediaPanel },
                    modifier = Modifier.fillMaxWidth().height(with(density) { emojiLayout.emojiKeyboardHeight.toDp() }),
                )
            }
            AndroidView(
                factory = { (bottomRow.parent as? ViewGroup)?.removeView(bottomRow); bottomRow },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    @Composable
    private fun TypeTabRow() {
        val textColor = Color(colors.get(ColorType.EMOJI_KEY_TEXT))
        Row(Modifier.fillMaxWidth().height(TYPE_TAB_ROW_HEIGHT)) {
            TypeTab(MediaType.EMOJI, stringResource(R.string.emoji_type_tab_emoji), textColor)
            if (stickersAvailable) TypeTab(MediaType.STICKER, stringResource(R.string.emoji_type_tab_sticker), textColor)
            if (gifsAvailable) TypeTab(MediaType.GIF, stringResource(R.string.emoji_type_tab_gif), textColor)
        }
    }

    @Composable
    private fun androidx.compose.foundation.layout.RowScope.TypeTab(type: MediaType, label: String, textColor: Color) {
        Box(
            Modifier.weight(1f).fillMaxHeight().alpha(if (mediaType == type) 1f else 0.55f).clickable { showMediaType(type) },
            contentAlignment = Alignment.Center,
        ) {
            Text(label.uppercase(), color = textColor, fontSize = 14.sp, textAlign = TextAlign.Center)
        }
    }

    /** The pages of one category: a column the finger scrolls, one [EmojiKeyGrid] per page. */
    @Composable
    private fun CategoryPages(categoryId: Int) {
        val epoch = cacheEpoch * 1000 + (categoryEpochs[categoryId] ?: 0)
        val pageCount = remember(epoch, categoryId) { emojiCategory.getCategoryPageCount(categoryId) }
        val listState = listStateFor(categoryId)
        LaunchedEffect(listState, categoryId) {
            snapshotFlow { firstCompleteVisiblePage(listState) }.collect {
                if (categoryId == emojiCategory.currentCategoryId) emojiCategory.currentCategoryPageId = it
            }
        }
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            items(pageCount) { index ->
                val keyboard = remember(epoch, index) { emojiCategory.getKeyboardFromAdapterPosition(categoryId, index) }
                if (keyboard != null) EmojiKeyGrid(keyboard, this@EmojiPalettesView, popups, epoch)
            }
        }
    }

    private fun listStateFor(categoryId: Int): LazyListState = listStates.getOrPut(categoryId) {
        LazyListState(if (categoryId == initialCategoryId) initialCategoryPageId else 0, 0)
    }

    private fun firstCompleteVisiblePage(state: LazyListState): Int {
        val firstComplete = state.layoutInfo.visibleItemsInfo.firstOrNull { it.offset >= 0 }?.index ?: 0
        return if (firstComplete > 0) firstComplete else state.firstVisibleItemIndex
    }

    @Composable
    private fun PageIndicator() {
        val density = LocalDensity.current
        val categoryId = currentCategoryId
        val pageCount = remember(cacheEpoch, categoryEpochs[categoryId], categoryId) { emojiCategory.getCategoryPageCount(categoryId) }
        EmojiPageIndicator(
            pageCount = pageCount,
            position = {
                val state = listStateFor(categoryId)
                val heights = IntArray(pageCount) { emojiCategory.getKeyboardFromAdapterPosition(categoryId, it)?.occupiedHeight ?: 0 }
                val extent = state.layoutInfo.viewportEndOffset - state.layoutInfo.viewportStartOffset
                val offset = (0 until state.firstVisibleItemIndex.coerceAtMost(pageCount)).sumOf { heights[it] } + state.firstVisibleItemScrollOffset
                EmojiPageIndicatorMath.position(offset, heights.sum(), extent, pageCount)
            },
            foreground = Color(colors.get(ColorType.EMOJI_CATEGORY_SELECTED)),
            background = Color(colors.get(ColorType.STRIP_BACKGROUND)),
            paddingStart = sideLeftPx.toFloat(),
            paddingEnd = sideRightPx.toFloat(),
        )
    }

    companion object {
        private val TYPE_TAB_ROW_HEIGHT = 40.dp
        private var sDictionaryFacilitator: SingleDictionaryFacilitator? = null

        @JvmStatic
        fun closeDictionaryFacilitator() {
            sDictionaryFacilitator?.closeDictionaries()
            sDictionaryFacilitator = null
        }
    }
}
