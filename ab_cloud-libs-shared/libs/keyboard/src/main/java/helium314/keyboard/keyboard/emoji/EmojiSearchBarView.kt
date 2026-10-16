// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.emoji

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.InputConnection
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.inputmethod.InputContentInfoCompat
import com.diegonmarcos.superapp.media.GifProvider
import com.diegonmarcos.superapp.media.MediaCommit
import com.diegonmarcos.superapp.media.MediaItem
import com.diegonmarcos.superapp.media.MediaRemoteImage
import com.diegonmarcos.superapp.media.MediaRuntime
import com.diegonmarcos.superapp.media.StickerRepo
import com.diegonmarcos.superapp.uikit.KitImeComposeView
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import helium314.keyboard.keyboard.internal.keyboard_parser.getEmojiDefaultVersion
import helium314.keyboard.latin.SingleDictionaryFacilitator
import helium314.keyboard.latin.common.splitOnWhitespace
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.dictionary.DictionaryFactory
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.DictionaryInfoUtils
import helium314.keyboard.latin.utils.toKitPalette
import java.util.Locale
import java.util.concurrent.Executors

/**
 * SuperApp addition (patch 0011) — replaces the always-visible in-panel emoji
 * search field (patch 0010's `emoji_search_bar`, which permanently ate vertical
 * space and only searched emoji) with a Gboard/translate-bar-style toggle: tap
 * the search icon in the emoji tab strip, a bar is drawn above the keyboard
 * (keyboard stays visible, same hosting mechanism as [helium314.keyboard.latin.LatinIME.toggleTranslateBar]),
 * with three result rows below it — Emoji / Stickers / GIFs, all sized to match
 * -- tapping any result inserts it (text commit for emoji, commitContent for
 * sticker/gif) and closes the bar.
 *
 * Like TranslateBarView, key presses are routed here manually via
 * appendCodePoint/backspace (LatinIME.onEvent) rather than via a focusable
 * EditText, because the on-screen keyboard only ever commits to the app's own
 * InputConnection, not to arbitrary child views of the IME's window.
 *
 * Compose under CloudKitTheme (the keyboard theme's palette roles); the public surface the IME drives
 * (bind / onShown / appendCodePoint / backspace) is unchanged.
 */
class EmojiSearchBarView(context: Context) : KitImeComposeView(context) {

    fun interface IcProvider { fun get(): InputConnection? }
    fun interface MediaPicker { fun commit(info: InputContentInfoCompat) }

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val ui = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    private var icp: IcProvider? = null
    private var mediaPicker: MediaPicker? = null
    private var onClose: Runnable? = null
    private var locale: Locale = Locale.getDefault()

    private val buffer = StringBuilder()
    private var typed by mutableStateOf("")
    private var emojis by mutableStateOf<List<String>>(emptyList())
    private var stickers by mutableStateOf<List<MediaItem>>(emptyList())
    private var gifs by mutableStateOf<List<MediaItem>>(emptyList())
    private var emptyText by mutableStateOf<String?>(HINT)

    private var dictionaryFacilitator: SingleDictionaryFacilitator? = null
    private var dictionaryLocale: Locale? = null
    private var searchToken = 0

    companion object {
        private const val CELL_DP = 40
        private const val HINT = "Type to search emoji, stickers and GIFs…"
    }

    override fun palette(): KitPalette = Settings.getValues().mColors.toKitPalette()

    @Composable
    override fun Body() {
        val p = LocalKitPalette.current
        Column(Modifier.fillMaxWidth().background(p.surface).padding(start = 10.dp, top = 6.dp, end = 10.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(typed, color = p.textPrimary, fontSize = 16.sp, maxLines = 1,
                    modifier = Modifier.weight(1f).padding(4.dp))
                Text("✕", color = p.textSecondary, fontSize = 16.sp,
                    modifier = Modifier.clickable { onClose?.run() }.padding(start = 10.dp, top = 2.dp, end = 4.dp, bottom = 2.dp))
            }
            emptyText?.let {
                Text(it, color = p.textSecondary, fontStyle = FontStyle.Italic, textAlign = TextAlign.Start,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp, end = 4.dp, bottom = 4.dp))
            }
            ResultRow {
                emojis.forEach { emoji ->
                    Box(Modifier.size(CELL_DP.dp).clickable { commitEmoji(emoji) }, contentAlignment = Alignment.Center) {
                        Text(emoji, fontSize = 22.sp, color = p.textPrimary)
                    }
                }
            }
            ResultRow { stickers.forEach { MediaCell(it) } }
            ResultRow { gifs.forEach { MediaCell(it) } }
        }
    }

    @Composable
    private fun ResultRow(content: @Composable () -> Unit) {
        Row(Modifier.fillMaxWidth().height((CELL_DP + 8).dp).horizontalScroll(rememberScrollState())) { content() }
    }

    @Composable
    private fun MediaCell(item: MediaItem) {
        MediaRemoteImage(item.previewUrl, Modifier.padding(end = 4.dp).size(CELL_DP.dp).clickable { commitMedia(item) })
    }

    /** Wire the bar to the IME. Mirrors TranslateBarView.bind. */
    fun bind(provider: IcProvider, picker: MediaPicker, currentLocale: Locale, onCloseAction: Runnable) {
        icp = provider
        mediaPicker = picker
        locale = currentLocale
        onClose = onCloseAction
    }

    fun onShown() {
        buffer.setLength(0)
        typed = ""
        clearResults()
    }

    fun appendCodePoint(cp: Int) { buffer.appendCodePoint(cp); onChanged() }
    fun backspace() {
        if (buffer.isNotEmpty()) {
            val last = buffer.length - 1
            if (last > 0 && Character.isLowSurrogate(buffer[last]) && Character.isHighSurrogate(buffer[last - 1]))
                buffer.setLength(last - 1) else buffer.setLength(last)
        }
        onChanged()
    }

    private fun onChanged() {
        typed = buffer.toString()
        pending?.let { ui.removeCallbacks(it) }
        val text = buffer.toString().trim()
        if (text.isEmpty()) { clearResults(); return }
        val job = Runnable { search(text) }
        pending = job
        ui.postDelayed(job, 200)
    }

    private fun clearResults() {
        emojis = emptyList(); stickers = emptyList(); gifs = emptyList()
        emptyText = HINT
    }

    private fun search(query: String) {
        val token = ++searchToken
        io.submit {
            val foundEmojis = searchEmoji(query)
            val foundStickers = searchStickers(query)
            val foundGifs = searchGifs(query)
            main.post {
                if (token != searchToken) return@post
                emptyText = if (foundEmojis.isEmpty() && foundStickers.isEmpty() && foundGifs.isEmpty()) "No results." else null
                emojis = foundEmojis
                stickers = foundStickers
                gifs = foundGifs
            }
        }
    }

    // ── emoji (on-device dictionary — same source as EmojiSearchActivity) ────
    private fun searchEmoji(query: String): List<String> {
        if (dictionaryFacilitator == null || dictionaryLocale != locale) {
            dictionaryFacilitator?.closeDictionaries()
            dictionaryFacilitator = DictionaryInfoUtils.getCachedDictForLocaleAndType(locale, Dictionary.TYPE_EMOJI, context)
                ?.let { DictionaryFactory.getDictionary(it, locale) }?.let { SingleDictionaryFacilitator(it) }
            dictionaryLocale = locale
        }
        val facilitator = dictionaryFacilitator ?: return emptyList()
        return facilitator.getSuggestions(query.splitOnWhitespace())
            .filter { it.isEmoji }
            .map { getEmojiDefaultVersion(it.word) }
            .take(30)
    }

    // ── stickers — no per-sticker metadata exists, so match by pack name ─────
    private fun searchStickers(query: String): List<MediaItem> {
        val packs = StickerRepo.packs(context).filter { it.name.contains(query, ignoreCase = true) }
        return packs.flatMap { StickerRepo.stickers(context, "${it.authority} ${it.identifier}") }.take(24)
    }

    // ── gifs — real query against the configured provider ────────────────────
    private fun searchGifs(query: String): List<MediaItem> {
        val conf = MediaRuntime.gif ?: return emptyList()
        if (conf.apiKey.isBlank()) return emptyList()
        return runCatching { GifProvider.from(conf).fetch(query, 24) }.getOrDefault(emptyList())
    }

    private fun commitEmoji(emoji: String) {
        icp?.get()?.commitText(emoji, 1)
        onClose?.run()
    }

    private fun commitMedia(item: MediaItem) {
        val picker = mediaPicker ?: return
        io.submit {
            val info = MediaCommit.toContent(context, item) ?: return@submit
            main.post { picker.commit(info); onClose?.run() }
        }
    }

    /** Called from LatinIME.onDestroy, mirrors EmojiSearchActivity.Companion.closeDictionaryFacilitator. */
    fun closeDictionaryFacilitator() {
        dictionaryFacilitator?.closeDictionaries()
        dictionaryFacilitator = null
    }
}
