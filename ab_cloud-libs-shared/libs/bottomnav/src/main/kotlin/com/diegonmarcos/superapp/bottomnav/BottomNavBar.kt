package com.diegonmarcos.superapp.bottomnav

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

/**
 * The floating bottom-navigation island (#465): five icon-only items in a fully rounded shape that
 * floats above the screen content, inset from the bottom edge. The selected destination's icon is
 * tinted primary; the others sit in the surface's onSurfaceVariant tone. (This toolchain's material3
 * does not expose the navigation-bar item with a selectable, label-free pill this bar could rely on,
 * so selection is a color, not a pill — the geometry and icon-only rules are what is load-bearing.)
 *
 * It is drawn once per destination the bar owns — the caller passes the route it is on, so the bar
 * needs no live read of the navigation back stack. [currentRoute] names the screen it overlays;
 * [BottomNavAction.DESTINATION] items navigate inside the NavHost and set the selection,
 * [BottomNavAction.LAUNCH] items hand off to another app and do NOT move it.
 *
 * Shared by reference (#493): the bar and its geometry live in this module, the consuming app adds
 * it as a dependency and renders [BottomNavBar] against its own NavHost. There is no per-app copy.
 */

/** The island geometry (#465): a floating rounded shape with fully semicircular ends, declared once
 *  so no call site restates the radius. */
public val bottomNavIslandShape: RoundedCornerShape = RoundedCornerShape(32.dp)

/** How far the floating navigation island sits above the screen's bottom edge (#465). */
public val bottomNavIslandInset: Dp = 12.dp

@Composable
public fun BottomNavBar(nav: NavController, currentRoute: String) {
    val context = LocalContext.current
    // The sanctioned hand-off guard for the two LAUNCH items — a double tap while leaving must not
    // fire the launch twice. rememberLeaveOnce is @Composable, so it is resolved here and handed to
    // the tap handler.
    val leaveOnce = rememberLeaveOnce()
    // Resolved here, once, in the composable's own scope — stringResource must run where the
    // resource is available, never inside a tap callback, so the launch item's "not installed"
    // sentence is read through the context (a plain method, safe in this map lambda) and handed
    // to the toast pre-resolved.
    val missing = bottomNavItems.associateWith { item -> context.getString(missingResource(item.id)) }

    val islandColor = MaterialTheme.colorScheme.surfaceContainer
    // A full-size Box of the bar's own so the island's Row can `.align(BottomCenter)` against it —
    // `align` is a BoxScope method in this compose, and BottomNavBar is its own function with no
    // enclosing layout scope to inherit one from.
    Box(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 0.dp, bottom = bottomNavIslandInset)
                .height(64.dp)
                .clip(bottomNavIslandShape)
                .background(color = islandColor, shape = bottomNavIslandShape),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            bottomNavItems.forEach { item ->
                val selected = item.action == BottomNavAction.DESTINATION && item.route == currentRoute
                IconButton(
                    onClick = { onItemTap(context, nav, item, currentRoute, leaveOnce, missing[item] ?: "") },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        iconFor(item.id),
                        contentDescription = stringResource(itemDescription(item.id)),
                        tint = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** One item tapped. A destination navigates; a launch hands off and leaves the selection alone. */
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
            // Re-tapping the screen already on top is a no-op, not a re-navigation that would
            // pile a second copy on the back stack.
            if (route == currentRoute) return
            nav.navigate(route)
        }
        BottomNavAction.LAUNCH -> item.packageName?.let {
            leaveOnce { launchInstalledApp(context, it, missingMessage) }
        }
    }
}

/**
 * Launch [packageName]'s front-door activity, reporting whether it exists on this device — the
 * launch intent is resolved against the device rather than assumed, so a device without the app
 * gets the specific honest sentence ([missingMessage]) instead of this bar pretending.
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

/** The string resource id of the "not installed" sentence a launch item shows when its app is
 *  absent. A plain resource id, so the composable can resolve it where strings are available. */
internal fun missingResource(id: String): Int = when (id) {
    "chat" -> R.string.nav_chat_not_installed
    "video" -> R.string.nav_video_not_installed
    else -> R.string.nav_launch_not_installed
}

/** The accessible content description of a nav item: what a screen reader says about the icon. */
internal fun itemDescription(id: String): Int = when (id) {
    "mail" -> R.string.nav_bar_mail
    "chat" -> R.string.nav_bar_chat
    "home" -> R.string.nav_bar_home
    "rss" -> R.string.nav_bar_rss
    "video" -> R.string.nav_bar_video
    else -> R.string.nav_bar_home
}

/** The drawn glyph for each item. Kept local to this file; the data model stays icon-free. */
private fun iconFor(id: String): ImageVector = when (id) {
    "mail" -> Icons.Filled.Mail
    "chat" -> Icons.Filled.ChatBubble
    "home" -> Icons.Filled.Home
    "rss" -> Icons.Filled.RssFeed
    "video" -> Icons.Filled.Videocam
    else -> Icons.Filled.Home
}