package com.diegonmarcos.superapp.bottomnav

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigation.NavController

/**
 * THE bottom nav of the fleet: one Compose declaration that every app renders (#565).
 *
 * This is a PORT of superapp's View-era CloudBottomNavView + bg_nav_island.xml +
 * bg_bottom_nav_item_checked.xml + color/bottom_nav_content.xml, not a wrapper around them.
 * No AndroidView, no View, no drawable: the old toolkit ends here. The geometry comes from this
 * module's res/values/dimens.xml, which ports the tokens that #462/#473/#477/#498 settled.
 *
 *  - #417 the island is a full pill (radius = height/2), so its ends are true semicircles, and
 *    every item is laid out inside it. Nothing overflows the capsule it sits in.
 *  - #462 the SELECTED item is a pill too. It wraps the icon AND the label (Telegram/Revolut
 *    style), light on the dark bar, and it is the only lit capsule. Selection is shown by that
 *    pill, not by a colour change.
 *  - #473 the end capsules are inset by exactly the capsule's vertical inset, so their arcs are
 *    concentric with the island's. The cells are equal weights, so spacing is even. The label
 *    sits bottom_nav_icon_label_gap below the icon.
 *  - #477 the bottom clearance is ONE dimen plus the live system-bar/display-cutout inset.
 *    The inset is READ (getBottom), never applied through windowInsetsPadding or
 *    consumeWindowInsets, so nothing else in the window loses it.
 *  - #532 [collapsed] drops the labels and leaves an icons-only bar ([BottomNavCollapse] derives
 *    it from scrolling). The island is fill only, with no stroke layer, so the visible edge IS
 *    the fill's edge.
 *  - #536 the island is bottom_nav_width_fraction (80%) of the width it is given, and it is
 *    centred, so 10% stays clear on each side.
 *
 * The items are INJECTED as [BottomNavEntry]. The bar knows nothing about any app's menu.
 */

/** One nav item as the bar draws it: a stable id, the label (also the collapsed bar's
 *  accessible name), and the glyph. */
public data class BottomNavEntry(val id: String, val label: String, val icon: Painter)

/** The one pill shape. The island and the selected capsule both use it, so the two stadiums
 *  can never drift apart. 50% = half the shorter side, a true semicircle at any size. */
public val bottomNavPillShape: RoundedCornerShape = RoundedCornerShape(percent = 50)

internal const val TAG_ISLAND = "bottomnav_island"
internal const val TAG_CONTENT = "bottomnav_content"
internal fun itemTag(id: String) = "bottomnav_item_$id"
internal fun iconTag(id: String) = "bottomnav_icon_$id"
internal fun labelTag(id: String) = "bottomnav_label_$id"

/** The insets the island clears: system bars AND the display cutout (#477). */
@Composable
public fun bottomNavInsets(): WindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)

@Composable
public fun BottomNavIsland(
    entries: List<BottomNavEntry>,
    selectedId: String?,
    onSelect: (BottomNavEntry) -> Unit,
    modifier: Modifier = Modifier,
    collapsed: Boolean = false,
    insets: WindowInsets = bottomNavInsets(),
) {
    val density = LocalDensity.current
    val res = LocalContext.current.resources
    val widthFraction = res.getFraction(R.fraction.bottom_nav_width_fraction, 1, 1)
    // Read, never consumed: getBottom is a plain snapshot read that recomposes when the inset
    // changes. It is the Compose form of a non-consuming insets listener.
    val bottom = dimensionResource(R.dimen.bottom_nav_island_bottom_margin) +
        with(density) { insets.getBottom(this).toDp() }
    val pillInset = dimensionResource(R.dimen.bottom_nav_pill_inset)
    val pad = dimensionResource(R.dimen.bottom_nav_item_vertical_pad)
    val gap = dimensionResource(R.dimen.bottom_nav_icon_label_gap)
    val iconSize = dimensionResource(R.dimen.bottom_nav_icon_size)
    // An sp dimen comes back in px with the font scale applied. px -> sp undoes exactly that.
    val labelStyle = TextStyle(fontSize = with(density) { res.getDimension(R.dimen.bottom_nav_label_text_size).toSp() })
    val scheme = MaterialTheme.colorScheme

    Box(modifier.fillMaxWidth().padding(bottom = bottom), contentAlignment = Alignment.BottomCenter) {
        Row(
            Modifier
                .fillMaxWidth(widthFraction)
                .testTag(TAG_ISLAND)
                .clip(bottomNavPillShape)
                .background(colorResource(R.color.bottom_nav_island_fill))
                .padding(horizontal = dimensionResource(R.dimen.bottom_nav_end_inset)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            entries.forEach { entry ->
                val selected = entry.id == selectedId
                val ink = if (selected) scheme.inverseOnSurface else scheme.onSurfaceVariant
                Column(
                    Modifier
                        .weight(1f)
                        .padding(vertical = pillInset)
                        .testTag(itemTag(entry.id))
                        .clip(bottomNavPillShape)
                        .background(if (selected) scheme.inverseSurface else Color.Transparent)
                        .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(entry) })
                        .padding(vertical = pad),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Top,
                ) {
                    Icon(
                        entry.icon,
                        // With the label hidden, the icon has to carry the item's name.
                        contentDescription = if (collapsed) entry.label else null,
                        tint = ink,
                        modifier = Modifier.size(iconSize).testTag(iconTag(entry.id)),
                    )
                    // ponytail: no expand/collapse animation. Add animateContentSize if it jars.
                    if (!collapsed) {
                        // Laid out across the whole capsule and centred, not at its own
                        // intrinsic width. A one-line ellipsized paragraph exactly as wide as its
                        // text can round itself into an ellipsis: 'Mail' at 23px came out
                        // ellipsized in a 56px capsule in CI run 36024784089.
                        Text(
                            entry.label,
                            modifier = Modifier.padding(top = gap).fillMaxWidth().testTag(labelTag(entry.id)),
                            color = ink,
                            style = labelStyle,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * #532 scroll-collapse. Attach it with Modifier.nestedScroll(collapse) on the scrolling
 * content and pass [collapsed] to [BottomNavIsland]. Scrolling down the content collapses the
 * bar to icons only, and scrolling back up restores the labels. It only observes: it consumes
 * nothing, so the list still gets every pixel of the scroll.
 */
public class BottomNavCollapse : NestedScrollConnection {
    public var collapsed: Boolean by mutableStateOf(false)
        private set

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (available.y < 0f) collapsed = true else if (available.y > 0f) collapsed = false
        return Offset.Zero
    }
}

@Composable
public fun rememberBottomNavCollapse(): BottomNavCollapse = remember { BottomNavCollapse() }

// ── cloud-mail's bar: its item table (BottomNav.kt) rendered through the shared island ──

/** Mail's five items as the island draws them. Labels are the accessible names in strings.xml. */
@Composable
internal fun mailEntries(): List<BottomNavEntry> = bottomNavItems.map {
    BottomNavEntry(it.id, stringResource(itemDescription(it.id)), rememberVectorPainter(iconFor(it.id)))
}

/**
 * cloud-mail's bottom nav (#465): [bottomNavItems] on the shared island, with the screen's
 * [content] laid out ABOVE it (#534). The content ends where the island's clearance begins, so
 * nothing the screen draws can hide under the bar. Scrolling the content collapses the island to
 * icons (#532). DESTINATION items navigate and move the selected pill. LAUNCH items hand off to
 * another app and leave the pill where it is.
 */
@Composable
public fun BottomNavBar(nav: NavController, currentRoute: String, content: @Composable () -> Unit) {
    val context = LocalContext.current
    // The sanctioned hand-off guard: a double tap while leaving must not fire the launch twice.
    val leaveOnce = rememberLeaveOnce()
    // Resolved here, in the composable's scope, and handed to the tap handler already resolved.
    val missing = bottomNavItems.associateWith { item -> context.getString(missingResource(item.id)) }
    val selected = bottomNavItems.firstOrNull {
        it.action == BottomNavAction.DESTINATION && it.route == currentRoute
    }?.id
    val collapse = rememberBottomNavCollapse()
    val insets = bottomNavInsets()
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // The island below already clears the bottom system bar, so the content must not clear it
        // a second time: consumed for the content ONLY. The island still reads it (#477).
        Box(
            Modifier
                .fillMaxSize()
                .testTag(TAG_CONTENT),
        ) { content() }
        BottomNavIsland(
            entries = mailEntries(),
            selectedId = selected,
            onSelect = { entry ->
                val item = bottomNavItems.first { it.id == entry.id }
                onItemTap(context, nav, item, currentRoute, leaveOnce, missing[item] ?: "")
            },
            modifier = Modifier.align(Alignment.BottomCenter),
            collapsed = collapse.collapsed,
            insets = insets,
        )
    }
}

/** One item tapped. A destination navigates, and a launch hands off without moving the selection. */
private fun onItemTap(
    context: android.content.Context,
    nav: NavController,
    item: BottomNavItem,
    currentRoute: String,
    leaveOnce: (() -> Boolean) -> Unit,
    missingMessage: String,
) {
    when (item.action) {
        BottomNavAction.DESTINATION -> {
            val route = item.route ?: return
            // Re-tapping the screen already on top is a no-op, not a second copy on the back stack.
            if (route == currentRoute) return
            nav.navigate(route)
        }
        BottomNavAction.LAUNCH -> item.packageName?.let {
            leaveOnce { launchInstalledApp(context, it, missingMessage) }
        }
    }
}

/**
 * Launch [packageName]'s front-door activity, reporting whether it exists on this device. The
 * launch intent is resolved against the device, so a device without the app shows the specific,
 * honest [missingMessage] instead of a bar that pretends.
 */
internal fun launchInstalledApp(
    context: android.content.Context,
    packageName: String,
    missingMessage: String,
): Boolean =
    try {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            Toast.makeText(context, missingMessage, Toast.LENGTH_SHORT).show()
            false
        } else {
            context.startActivity(intent)
            true
        }
    } catch (_: Exception) {
        false
    }

/** The "not installed" sentence a launch item shows when its app is absent. */
internal fun missingResource(id: String): Int = when (id) {
    "chat" -> R.string.nav_chat_not_installed
    "video" -> R.string.nav_video_not_installed
    else -> R.string.nav_launch_not_installed
}

/** A nav item's label, which is also its accessible name. */
internal fun itemDescription(id: String): Int = when (id) {
    "mail" -> R.string.nav_bar_mail
    "chat" -> R.string.nav_bar_chat
    "home" -> R.string.nav_bar_home
    "rss" -> R.string.nav_bar_rss
    "video" -> R.string.nav_bar_video
    else -> R.string.nav_bar_home
}

/** The glyph for each of mail's items. The data model stays icon-free. */
private fun iconFor(id: String): ImageVector = when (id) {
    "mail" -> Icons.Filled.Mail
    "chat" -> Icons.Filled.ChatBubble
    "home" -> Icons.Filled.Home
    "rss" -> Icons.Filled.RssFeed
    "video" -> Icons.Filled.Videocam
    else -> Icons.Filled.Home
}
