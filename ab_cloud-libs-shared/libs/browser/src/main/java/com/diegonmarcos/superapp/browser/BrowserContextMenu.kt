package com.diegonmarcos.superapp.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import java.net.URLEncoder

/**
 * Long-press on page content: what WebView's hit test found becomes a short list of actions.
 * Pure (ints and strings in, enums out) so the mapping is JVM-tested; the host performs them.
 * Selected text and form fields are NOT ours: the system selection actions stay.
 */
object BrowserContextMenu {

    // android.webkit.WebView.HitTestResult.*_TYPE, restated so this file needs no framework to test.
    const val UNKNOWN = 0
    const val PHONE = 2
    const val GEO = 3
    const val EMAIL = 4
    const val IMAGE = 5
    const val SRC_ANCHOR = 7
    const val SRC_IMAGE_ANCHOR = 8
    const val EDIT_TEXT = 9

    enum class Action(val id: String, val label: String) {
        OPEN_TAB("open_tab", "Open in new tab"),
        OPEN_IN_GROUP("open_in_group", "Open in new tab in this group"),
        OPEN_BACKGROUND("open_background", "Open in background tab"),
        OPEN_PRIVATE("open_private", "Open in private tab"),
        COPY_LINK("copy_link", "Copy link address"),
        COPY_LINK_TEXT("copy_link_text", "Copy link text"),
        SHARE_LINK("share_link", "Share link"),
        DOWNLOAD_LINK("download_link", "Download link"),
        ADD_FAV("add_fav", "Add to Fav"),
        IMAGE_OPEN("image_open", "Open image in new tab"),
        IMAGE_DOWNLOAD("image_download", "Download image"),
        IMAGE_COPY("image_copy", "Copy image address"),
        IMAGE_SHARE("image_share", "Share image"),
        IMAGE_SEARCH("image_search", "Search image"),
    }

    /** What was pressed: a link, an image, or an image that is a link. Null url = not that. */
    data class Target(val linkUrl: String?, val linkText: String, val imageUrl: String?)

    /**
     * The target for a hit of [type] with [extra]; for an image link, [hrefUrl]/[hrefTitle] are the
     * answer of requestFocusNodeHref (the hit's own extra is the IMAGE there). Null when the system
     * actions should stand: text, edit fields, phone/geo/email, unknown, or an empty extra.
     */
    fun target(type: Int, extra: String?, hrefUrl: String? = null, hrefTitle: String? = null): Target? {
        val e = extra?.trim().orEmpty()
        return when (type) {
            SRC_ANCHOR -> if (e.isEmpty()) null else Target(e, hrefTitle?.trim().orEmpty(), null)
            IMAGE -> if (e.isEmpty()) null else Target(null, "", e)
            SRC_IMAGE_ANCHOR -> {
                val link = hrefUrl?.trim().orEmpty()
                if (e.isEmpty() && link.isEmpty()) null
                else Target(link.ifEmpty { null }, hrefTitle?.trim().orEmpty(), e.ifEmpty { null })
            }
            else -> null
        }
    }

    fun isWeb(url: String?): Boolean = url != null && (url.startsWith("http://", true) || url.startsWith("https://", true))

    /** The rows, in order. Opening and downloading need a web address; copying and sharing take any. */
    fun actions(t: Target, hasPrivate: Boolean): List<Action> = buildList {
        val link = t.linkUrl
        if (link != null) {
            if (isWeb(link)) {
                add(Action.OPEN_TAB); add(Action.OPEN_IN_GROUP); add(Action.OPEN_BACKGROUND)
                if (hasPrivate) add(Action.OPEN_PRIVATE)
            }
            add(Action.COPY_LINK)
            if (t.linkText.isNotBlank()) add(Action.COPY_LINK_TEXT)
            add(Action.SHARE_LINK)
            if (isWeb(link)) { add(Action.DOWNLOAD_LINK); add(Action.ADD_FAV) }
        }
        val img = t.imageUrl
        if (img != null) {
            if (isWeb(img)) { add(Action.IMAGE_OPEN); add(Action.IMAGE_DOWNLOAD) }
            add(Action.IMAGE_COPY); add(Action.IMAGE_SHARE)
            if (isWeb(img)) add(Action.IMAGE_SEARCH)
        }
    }

    /**
     * How an open-in-tab action places its tab: [activate] takes focus, [group] joins (or starts) the
     * group of the tab it was opened from, [private] makes it a private tab. Null: not an open action.
     * [fromPrivate]: a link opened from a private tab stays private.
     */
    data class Placement(val activate: Boolean, val group: Boolean, val isPrivate: Boolean)

    fun placement(a: Action, fromPrivate: Boolean): Placement? = when (a) {
        Action.OPEN_TAB, Action.IMAGE_OPEN -> Placement(activate = true, group = false, isPrivate = fromPrivate)
        Action.OPEN_IN_GROUP -> Placement(activate = true, group = true, isPrivate = fromPrivate)
        Action.OPEN_BACKGROUND -> Placement(activate = false, group = false, isPrivate = fromPrivate)
        Action.OPEN_PRIVATE -> Placement(activate = true, group = false, isPrivate = true)
        else -> null
    }

    /**
     * Where "Search image" goes: Google Lens for Google, Bing's visual search for Bing; any other
     * engine has no address-based reverse search, so Lens.
     */
    fun imageSearchUrl(engineId: String, imageUrl: String): String {
        val u = URLEncoder.encode(imageUrl, "UTF-8")
        return if (engineId == "bing") "https://www.bing.com/images/search?view=detailv2&iss=sbi&q=imgurl:$u"
        else "https://lens.google.com/uploadbyurl?url=$u"
    }
}

/** A dense bottom sheet: the pressed address on top, then one line per action (no minimum row height). */
@Composable
fun BrowserContextSheet(title: String, actions: List<BrowserContextMenu.Action>, onPick: (BrowserContextMenu.Action) -> Unit, onDismiss: () -> Unit) {
    val p = LocalKitPalette.current
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(onClick = onDismiss)) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = 420.dp)
                .background(p.surface, RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                .clickable(remember { MutableInteractionSource() }, null) {}
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .testTag("browser:ctxmenu"),
        ) {
            Text(title, color = p.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 4.dp))
            actions.forEach { a ->
                Text(a.label, color = p.textPrimary, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().clickable { onPick(a) }.padding(vertical = 6.dp)
                        .testTag("browser:ctx:${a.id}"))
            }
        }
    }
}
