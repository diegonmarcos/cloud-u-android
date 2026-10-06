package app.sterna.ui

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import app.sterna.R
import com.diegonmarcos.superapp.bottomnav.BottomNavEntry
import com.diegonmarcos.superapp.bottomnav.BottomNavHost
import com.diegonmarcos.superapp.bottomnav.rememberLeaveOnce

/**
 * Mail's five items as the island draws them, from the declaration ([mailNav], build.json::ui): the
 * ids, order and icons are the declared sections'. Labels are the localised accessible names in
 * strings.xml, keyed by section id, so a translated bar stays translated.
 */
@Composable
internal fun mailEntries(): List<BottomNavEntry> = mailNav.bottomSections().map {
    BottomNavEntry(it.id, stringResource(itemDescription(it.id)), rememberVectorPainter(iconFor(it.icon)))
}

/**
 * cloud-mail's bottom nav (#465): [bottomNavItems] on the shared host. DESTINATION items navigate
 * and move the selected pill. LAUNCH items hand off to another app and leave the pill where it is.
 */
@Composable
fun BottomNavBar(nav: NavController, currentRoute: String, content: @Composable () -> Unit) {
    val context = LocalContext.current
    // The sanctioned hand-off guard: a double tap while leaving must not fire the launch twice.
    val leaveOnce = rememberLeaveOnce()
    // Resolved here, in the composable's scope, and handed to the tap handler already resolved.
    val missing = bottomNavItems.associateWith { item -> context.getString(missingResource(item.id)) }
    val selected = bottomNavItems.firstOrNull {
        it.action == BottomNavAction.DESTINATION && it.route == currentRoute
    }?.id
    BottomNavHost(
        entries = mailEntries(),
        selectedId = selected,
        onSelect = { entry ->
            val item = bottomNavItems.first { it.id == entry.id }
            onItemTap(context, nav, item, currentRoute, leaveOnce, missing[item] ?: "")
        },
        content = content,
    )
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
            // Settled-only, like every other navigation action here: a double tap mid-slide lands once.
            val top = nav.currentBackStackEntry
            top?.navigateOnce { nav.navigate(route) }
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
