package com.diegonmarcos.superapp.media

import android.content.Context
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.inputmethod.InputContentInfoCompat
import coil.request.ImageRequest
import com.diegonmarcos.superapp.uikit.KitImeComposeView
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * The Sticker / GIF body of the emoji surface. Owns ROW 2 — the per-type
 * category tab strip (GIF categories from build.json, or one tab per installed
 * sticker pack) — and the preview grid below it. ROW 1 (Emoji/Sticker/GIF) is
 * owned by the keyboard's EmojiPalettesView (patch 0007), which toggles this
 * view's visibility and calls [showType].
 *
 * Hosted by libs:keyboard; picks are committed via [bind]'s callback, which the
 * keyboard wires to KeyboardActionListener.onContent → commitContent.
 *
 * 2026-10-16 owner decision: all apps must be Compose. The panel is a Compose view under
 * CloudKitTheme (libs:ui-kit's IME host, which gives the input-method window the owners Compose
 * needs); the keyboard still inflates it from XML and drives it through [bind] / [showType].
 */
class MediaPanelView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : KitImeComposeView(context, attrs) {

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** SAM so the keyboard's Java EmojiPalettesView can wire it as a lambda. */
    fun interface Committer { fun commit(info: InputContentInfoCompat) }

    private var committer: Committer? = null
    private var type by mutableStateOf(MediaType.GIF)
    private var categories by mutableStateOf<List<MediaCategory>>(emptyList())
    private var selected by mutableStateOf<String?>(null)
    private var items by mutableStateOf<List<MediaItem>>(emptyList())
    private var message by mutableStateOf<String?>(null)
    private var stickerPacks: List<StickerRepo.Pack> = emptyList()
    private var loadToken = 0  // guards against stale async results after a tab switch

    /** Keyboard calls this once, handing us the commitContent sink. */
    fun bind(committer: Committer) { this.committer = committer }

    /** Switch the whole body to STICKER or GIF; rebuilds row-2 tabs. */
    fun showType(type: MediaType) {
        this.type = type
        val cats = when (type) {
            MediaType.GIF -> MediaRuntime.gif?.categories ?: emptyList()
            MediaType.STICKER -> {
                stickerPacks = StickerRepo.packs(context) // cheap (cursor), fine on main
                stickerPacks.map { StickerRepo.packCategory(it) }
            }
            MediaType.EMOJI -> emptyList()
        }
        categories = cats
        if (cats.isEmpty()) { selected = null; items = emptyList(); message = emptyMessageFor(type) }
        else selectCategory(cats.first())
    }

    private fun selectCategory(cat: MediaCategory) {
        selected = cat.label
        val token = ++loadToken
        message = null; items = emptyList()
        io.submit {
            val loaded = when (type) {
                MediaType.GIF -> MediaRuntime.gif?.let {
                    if (it.apiKey.isBlank()) emptyList()
                    else GifProvider.from(it).fetch(cat.query, it.limit)
                } ?: emptyList()
                MediaType.STICKER -> StickerRepo.stickers(context, cat.id)
                MediaType.EMOJI -> emptyList()
            }
            main.post {
                if (token != loadToken) return@post  // a newer tab won
                if (loaded.isEmpty()) { items = emptyList(); message = emptyMessageFor(type) }
                else { message = null; items = loaded }
            }
        }
    }

    private fun onPick(item: MediaItem) {
        val sink = committer ?: return
        io.submit {
            val info = MediaCommit.toContent(context, item) ?: return@submit
            main.post { sink.commit(info) }
        }
    }

    private fun emptyMessageFor(type: MediaType) = when (type) {
        MediaType.GIF ->
            if (MediaRuntime.gif == null) "GIFs are disabled."
            else if (!MediaRuntime.gifEnabled) "No GIF API key configured."
            else "No GIFs found."
        MediaType.STICKER -> "No sticker packs installed."
        MediaType.EMOJI -> ""
    }

    /** The tab text follows the keyboard theme's primary text colour, as the TextViews it replaces did. */
    private fun themeTextColor(): Int {
        val a = context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
        return try { a.getColor(0, res(R.color.media_text_fallback)) } finally { a.recycle() }
    }

    private fun res(id: Int) = androidx.core.content.ContextCompat.getColor(context, id)

    override fun palette(): KitPalette = KitPalette.fromArgb(
        surface = res(R.color.media_surface),
        surfaceSelected = res(R.color.media_selected),
        textPrimary = themeTextColor(),
        textSecondary = res(R.color.media_text_dim),
        accent = res(R.color.media_accent),
        hairline = res(R.color.media_hairline),
        tileInk = res(R.color.media_ink),
    )

    @Composable
    override fun Body() {
        val p = LocalKitPalette.current
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                categories.forEach { cat ->
                    Text(cat.label, color = p.textPrimary, fontSize = 14.sp, maxLines = 1,
                        modifier = Modifier.alpha(if (cat.label == selected) 1f else 0.55f)
                            .clickable { selectCategory(cat) }
                            .padding(horizontal = 10.dp, vertical = 7.dp))
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                LazyVerticalGrid(GridCells.Fixed(if (type == MediaType.GIF) 3 else 4), Modifier.fillMaxSize()) {
                    itemsIndexed(items, key = { i, m -> "$i:${m.previewUrl}" }) { _, item ->
                        RemoteImage(item.previewUrl, Modifier.fillMaxWidth().aspectRatio(1f).padding(2.dp)
                            .clickable { onPick(item) })
                    }
                }
                message?.let {
                    Text(it, color = p.textPrimary, textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 8.dp, vertical = 16.dp))
                }
            }
        }
    }

    /** A thumbnail loaded by the shared Coil loader (its GIF/WebP decoders animate the drawable). */
    @Composable
    private fun RemoteImage(url: String, modifier: Modifier) {
        val ctx = LocalContext.current
        var drawable by remember(url) { mutableStateOf<Drawable?>(null) }
        DisposableEffect(url) {
            val request = ImageRequest.Builder(ctx).data(url).target(onSuccess = { drawable = it }).build()
            val handle = MediaRuntime.imageLoader(ctx).enqueue(request)
            onDispose { handle.dispose() }
        }
        val d = drawable
        if (d != null) {
            val painter = remember(d) { DrawablePainter(d) }
            DisposableEffect(painter) {
                painter.start()
                onDispose { painter.stop() }
            }
            Image(painter, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
        } else {
            Box(modifier)
        }
    }
}

/** Draws an Android [Drawable] (animated ones included) into a Compose canvas. */
private class DrawablePainter(private val d: Drawable) : Painter() {
    private var tick by mutableIntStateOf(0)
    private val handler = Handler(Looper.getMainLooper())
    private val callback = object : Drawable.Callback {
        override fun invalidateDrawable(who: Drawable) { tick++ }
        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) { handler.postAtTime(what, `when`) }
        override fun unscheduleDrawable(who: Drawable, what: Runnable) { handler.removeCallbacks(what) }
    }

    fun start() { d.callback = callback; (d as? Animatable)?.start() }
    fun stop() { (d as? Animatable)?.stop(); d.callback = null }

    override val intrinsicSize: Size
        get() = if (d.intrinsicWidth > 0 && d.intrinsicHeight > 0)
            Size(d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat()) else Size.Unspecified

    override fun DrawScope.onDraw() {
        tick // read, so a frame the drawable invalidates redraws
        drawIntoCanvas { c ->
            d.setBounds(0, 0, size.width.roundToInt(), size.height.roundToInt())
            d.draw(c.nativeCanvas)
        }
    }
}
