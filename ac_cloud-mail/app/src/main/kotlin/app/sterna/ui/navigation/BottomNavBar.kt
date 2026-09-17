package app.sterna.ui.navigation

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import app.sterna.R
import app.sterna.ui.theme.bottomNavIslandInset
import app.sterna.ui.theme.bottomNavIslandShape

/**
 * The floating bottom-navigation island (#465): five icon-only items in a fully rounded shape that
 * floats above the screen content, inset from the bottom edge. The selected indicator is Material
 * 3's stock pill around the icon — the label shapes #462 wrestled with are absent here, so the
 * stock indicator is already the correct shape and a custom drawable would add nothing.
 *
 * [BottomNavAction.DESTINATION] items navigate inside the NavHost; [BottomNavAction.LAUNCH] items
 * handed a package name launch that other app and do NOT move the selection — leaving and coming
 * back must still sit on the destination the user actually visited.
 */
@Composable
fun BottomNavBar(nav: NavController) {
    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    if (currentRoute == null || !bottomNavOwns(currentRoute)) return
    val context = LocalContext.current
    // Resolved here, once, in the composable's own scope — stringResource must run where the
    // resource is available, never inside a tap callback.
    val missing = bottomNavItems.associateWith { item -> missingMessage(item.id) }

    val islandColor = MaterialTheme.colorScheme.surfaceContainer
    Row(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(horizontal = 12.dp, bottom = bottomNavIslandInset)
            .height(64.dp)
            .clip(bottomNavIslandShape)
            .background(color = islandColor, shape = bottomNavIslandShape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        bottomNavItems.forEach { item ->
            val selected = item.action == BottomNavAction.DESTINATION && item.route == currentRoute
            NavigationBarItem(
                selected = selected,
                onClick = { onItemTap(context, nav, item, currentRoute, missing[item] ?: "") },
                icon = {
                    Icon(
                        iconFor(item.id),
                        contentDescription = stringResource(itemDescription(item.id)),
                    )
                },
                label = null,
                alwaysShowLabel = false,
                modifier = Modifier.weight(1f),
                interactionSource = remember(item) { MutableInteractionSource() },
            )
        }
    }
}

/** One item tapped. A destination navigates; a launch hands off and leaves the selection alone. */
private fun onItemTap(
    context: android.content.Context,
    nav: NavController,
    item: BottomNavItem,
    currentRoute: String,
    missingMessage: String,
) {
    when (item.action) {
        BottomNavAction.DESTINATION -> {
            val route = item.route ?: return
            // Re-tapping the screen already on top is a no-op, not a re-navigation that would
            // pile a second copy on the back stack.
            if (route == currentRoute) return
            nav.navigate(route) {
                // Pop up to the start destination to avoid an ever-growing back stack as the
                // user walks the tabs, exactly as the sibling top-level bar does.
                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
        BottomNavAction.LAUNCH -> item.packageName?.let {
            launchInstalledApp(context, it, missingMessage)
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
): Boolean {
    val intent = context.packageManager.getLaunchIntentForPackage(packageName)
    if (intent == null) {
        Toast.makeText(context, missingMessage, Toast.LENGTH_SHORT).show()
        return false
    }
    return runCatching { context.startActivity(intent) } match {
        OK -> true
        ERR -> false
    }
}

/** The "not installed" sentence a launch item shows when its app is absent. */
internal fun missingMessage(id: String): String = stringResource(
    when (id) {
        "chat" -> R.string.nav_chat_not_installed
        "video" -> R.string.nav_video_not_installed
        else -> R.string.nav_launch_not_installed
    },
)

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