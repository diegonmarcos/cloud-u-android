package com.diegonmarcos.superapp.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.annotation.DrawableRes
import androidx.compose.material3.Icon
import com.diegonmarcos.superapp.bottomnav.BottomNavIsland
import com.diegonmarcos.superapp.bottomnav.NavDecl
import com.diegonmarcos.superapp.bottomnav.islandEntries
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ─── Top-level tabs ───────────────────────────────────────────────────────────

/** Top-level wallet tabs. Events is a container with its own inner nav
 *  (Tickets / Bookings / Passes / Cal). [section] is the tab's id in build.json::ui.sections (#868). */
enum class WalletTab(val label: String, val section: String) {
    IDs("IDs", "ids"),
    /** Payment cards — the tab is called Pay because that is what you open it
     *  to do; the deck it holds is still the banking cards. */
    Pay("Pay", "pay"),
    /** Vcards sits between the documents you carry and the events you go to:
     *  it is the public half of the same wallet — one personal card per social,
     *  mirroring the pages of front-diegonmarcos/b-Media/mySocials. Declared,
     *  not stored: it holds no WalletStore cards, so it is the one tab whose
     *  content the user cannot add to from the app. */
    Vcards("Vcards", "vcards"),
    Tickets("Events", "events"),
    /** Still a destination, no longer a pill: it is the gear at the right end
     *  of the strip. Kept in the enum because it is where [WalletFragment]
     *  routes to render WalletSystemConfigTab — a mode, not a menu entry. */
    Config("Config", "config"),
}

/** Sub-tabs rendered inside the Events section. */
enum class TicketsSubTab(val label: String, val page: String) {
    Events("Tickets", "tickets"),
    Bookings("Bookings", "bookings"),
    Passes("Passes", "passes"),
    Calendar("Cal", "cal"),
}

// ─── Tab strips ───────────────────────────────────────────────────────────────

/**
 * The wallet's bottom-nav items are build.json::ui.bottom_nav (#868): IDs · Pay · Me · Vcards ·
 * Events (#533), read through [NavDecl] ([WalletHost.nav]), never listed here.
 *
 * The strip that used to sit at the TOP is gone (#531): the wallet's top-level navigation is
 * the fleet's one bottom nav, libs:bottomnav's island, at the bottom like every other app.
 * [walletTabOf] maps an item to its destination; Me has none, because it LEAVES this app for
 * cloud-me, the app that owns the personal-administration surface the wallet deliberately does
 * not. Config has an item in ui.sections but none in ui.bottom_nav: it is [WalletConfigGear],
 * floating in the content's corner.
 */
internal fun walletTabOf(sectionId: String): WalletTab? = WalletTab.entries.firstOrNull { it.section == sectionId }

/** The Events strip's page → sub-tab; the first one for an id the declaration does not name. */
internal fun walletSubTabOf(pageId: String): TicketsSubTab =
    TicketsSubTab.entries.firstOrNull { it.page == pageId } ?: TicketsSubTab.Events

/** The declared icon name → the wallet's drawable. */
@DrawableRes
internal fun walletIcon(name: String): Int = when (name) {
    "ids" -> R.drawable.ic_tab_ids
    "pay" -> R.drawable.ic_tab_pay
    "me" -> R.drawable.ic_tab_me
    "vcards" -> R.drawable.ic_tab_vcards
    "events" -> R.drawable.ic_tab_events
    else -> R.drawable.ic_tab_config
}

/**
 * [nav]'s bar on the shared island. A destination tap moves the pill; Me launches cloud-me
 * and the pill stays on the tab you are still on. Config lights no item.
 */
@Composable
internal fun WalletBottomNav(
    nav: NavDecl,
    selected: WalletTab,
    onSelect: (WalletTab) -> Unit,
    onOpenMe: () -> Unit,
    modifier: Modifier = Modifier,
    collapsed: Boolean = false,
) {
    val entries = nav.islandEntries { painterResource(walletIcon(it)) }
    // The island is the fleet's, in the fleet's colours: nothing about it is the wallet's to set.
    BottomNavIsland(
        entries = entries,
        selectedId = nav.bottomSections().firstOrNull { walletTabOf(it.id) == selected }?.id,
        onSelect = { entry -> walletTabOf(entry.id)?.let(onSelect) ?: onOpenMe() },
        modifier = modifier,
        collapsed = collapsed,
    )
}

/**
 * Config, as a gear in the bottom-right corner.
 *
 * It floats over the content rather than taking a row of its own: a wallet is
 * a stack of cards and one settings button does not deserve a permanent 44dp
 * band under them. The one control it could cover is [WalletArchiveToggle],
 * which reserves this corner for exactly that reason.
 */
@Composable
internal fun WalletConfigGear(active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(end = 16.dp, bottom = 16.dp)
            .clip(CircleShape)
            .background(if (active) Color(0xFF7C3AED) else Color(0xE62A2140))
            .clickable { onClick() }
            .padding(11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_tab_config),
            contentDescription = "Config",
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Inner sub-tab strip inside the Events section: that section's `pages`, drawn by the host
 *  ([WalletHost.PageStrip]) on the fleet's PageTabs. */
@Composable
internal fun TicketsSubTabStrip(
    nav: NavDecl,
    host: WalletHost?,
    selected: TicketsSubTab,
    onSelect: (TicketsSubTab) -> Unit,
) {
    host?.PageStrip(
        pages = nav.section(WalletTab.Tickets.section)?.pages.orEmpty(),
        selectedId = selected.page,
        onSelect = { onSelect(walletSubTabOf(it.id)) },
    )
}

// ─── Archive toggle ───────────────────────────────────────────────────────────

@Composable
internal fun WalletArchiveToggle(
    showingArchive: Boolean,
    upcomingCount: Int,
    archiveCount: Int,
    onToggle: () -> Unit,
) {
    val label = if (showingArchive) "← Back to Upcoming  ($upcomingCount)"
                else               "Archive  ($archiveCount)"
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // end reserves WalletConfigGear's corner, which floats over this
            // bar's right edge — a settings tap that lands on Archive instead
            // is worse than an off-centre bar.
            .padding(start = 16.dp, end = 68.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(if (showingArchive) Color(0xFF7C3AED) else Color(0x22FFFFFF))
                .clickable(onClick = onToggle)
                .padding(vertical = 11.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = if (showingArchive) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

// ─── Calendar agenda ─────────────────────────────────────────────────────────

@Composable
internal fun WalletCalendarView(
    tickets: List<WalletStore.Card>,
    bookings: List<WalletStore.Card>,
    showArchive: Boolean,
    upcomingCount: Int,
    archiveCount: Int,
    onToggleArchive: () -> Unit,
    onTicketTap: (WalletStore.Card) -> Unit,
    onBookingTap: (WalletStore.Card) -> Unit,
) {
    val tFiltered = remember(tickets, showArchive) {
        (if (showArchive) tickets.filter { it.isPastTicket } else tickets.filter { !it.isPastTicket })
            .sortedBy { it.eventAt }
    }
    val bFiltered = remember(bookings, showArchive) {
        (if (showArchive) bookings.filter { it.isPastBooking } else bookings.filter { !it.isPastBooking })
            .sortedBy { it.eventAt }
    }
    val grouped = remember(tFiltered) {
        val dayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        tFiltered.groupBy { dayKey.format(Date(it.eventAt)) }.toSortedMap()
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // Left — event tickets, grouped by day
            CalendarColumn(
                title = "Tickets",
                empty = if (showArchive) "No past events." else "No upcoming events.",
                isEmpty = tFiltered.isEmpty(),
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) {
                grouped.forEach { (dateKey, dayTickets) ->
                    item(key = "h-$dateKey") {
                        Text(
                            text = humanDate(dayTickets.first().eventAt),
                            color = Color(0xCCFFFFFF), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                    }
                    items(dayTickets, key = { it.id }) { t ->
                        CalendarRow(ticket = t, onClick = { onTicketTap(t) })
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }
            }
            Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(Color(0x22FFFFFF)))
            // Right — bookings as date-range bars
            CalendarColumn(
                title = "Bookings",
                empty = if (showArchive) "No past stays." else "No upcoming stays.",
                isEmpty = bFiltered.isEmpty(),
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) {
                items(bFiltered, key = { it.id }) { b ->
                    BookingRangeRow(booking = b, onClick = { onBookingTap(b) })
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
        WalletArchiveToggle(
            showingArchive = showArchive,
            upcomingCount  = upcomingCount,
            archiveCount   = archiveCount,
            onToggle       = onToggleArchive,
        )
    }
}

/** One calendar column with a sticky-ish header and a scrolling body.
 *  [content] supplies LazyColumn items; empties render the [empty] hint. */
@Composable
private fun CalendarColumn(
    title: String,
    empty: String,
    isEmpty: Boolean,
    modifier: Modifier = Modifier,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    Column(modifier = modifier) {
        Text(
            title.uppercase(),
            color = Color(0x88FFFFFF), fontSize = 10.sp, fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
        if (isEmpty) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(empty, color = Color(0x99FFFFFF), fontSize = 12.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) {
                content()
            }
        }
    }
}

/** Compact booking row for the calendar column — check-in → check-out
 *  with the nights count, distinct from the point-in-time ticket rows. */
@Composable
private fun BookingRangeRow(booking: WalletStore.Card, onClick: () -> Unit) {
    val dayFmt = remember { SimpleDateFormat("MMM d", Locale.US) }
    val accent = Color(booking.accent.toULong().toLong())
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x22FFFFFF))
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        Text(booking.brand, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(dayFmt.format(Date(booking.eventAt)), color = Color.White, fontSize = 11.sp)
            Box(modifier = Modifier.weight(1f).padding(horizontal = 4.dp).height(3.dp)
                .clip(RoundedCornerShape(2.dp)).background(accent))
            Text(dayFmt.format(Date(booking.checkOutAt)), color = Color.White, fontSize = 11.sp)
        }
        Text("${booking.nights} night${if (booking.nights == 1) "" else "s"}",
            color = Color(0x88FFFFFF), fontSize = 10.sp)
    }
}

@Composable
private fun CalendarRow(ticket: WalletStore.Card, onClick: () -> Unit) {
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.US) }
    val accent  = Color(ticket.accent.toULong().toLong())
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x22FFFFFF))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.width(60.dp)) {
            Text(timeFmt.format(Date(ticket.eventAt)), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(ticket.kind.uppercase(), color = Color(0x88FFFFFF), fontSize = 9.sp)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(ticket.brand,   color = Color.White,       fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(ticket.tagline, color = Color(0xCCFFFFFF), fontSize = 12.sp)
            if (ticket.eventLocation.isNotBlank()) Text(ticket.eventLocation, color = Color(0x88FFFFFF), fontSize = 11.sp)
        }
        Box(modifier = Modifier.width(6.dp).height(36.dp).clip(RoundedCornerShape(3.dp)).background(accent))
    }
}

private fun humanDate(millis: Long): String {
    val today    = Calendar.getInstance().apply { clearTime() }
    val tomorrow = Calendar.getInstance().apply { clearTime(); add(Calendar.DAY_OF_YEAR, 1) }
    val target   = Calendar.getInstance().apply { timeInMillis = millis; clearTime() }
    val pretty   = SimpleDateFormat("EEE, MMM d · yyyy", Locale.US).format(Date(millis))
    return when (target.timeInMillis) {
        today.timeInMillis    -> "Today · $pretty"
        tomorrow.timeInMillis -> "Tomorrow · $pretty"
        else                  -> pretty
    }
}

private fun Calendar.clearTime() {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0);      set(Calendar.MILLISECOND, 0)
}
