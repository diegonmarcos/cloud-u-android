package com.diegonmarcos.superapp.floatingnav

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The floating nav's two windows, in Compose: the collapsed circle and the expanded menu box. FloatingNavService owns the
 * windows (position, drag, outside-touch) and hands these the data; it used to build them from LinearLayouts and TextViews.
 */

/** The collapsed circle: a semi-transparent violet disc with a faint white rim. */
@Composable
internal fun NavBubble() {
    Box(
        Modifier.fillMaxSize().testTag("navbubble").alpha(0.55f)
            .background(Color(0x707C3AED), CircleShape).border(1.dp, Color(0x55FFFFFF), CircleShape),
    )
}

/**
 * The menu box: line 1 the constellation apex (biggest), line 2 the sibling hubs, line 3 the current context's children
 * (smallest, absent when it has none). [boldOf] marks the apex / hub that matches the foreground context; the children are
 * never bold. A tap runs [onPick].
 */
@Composable
internal fun NavBar(
    root: NavItem?, parents: List<NavItem>, children: List<NavItem>,
    boldOf: (NavItem) -> Boolean, onPick: (NavItem) -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.fillMaxWidth().testTag("navbar").background(Color(0xEE16161C), shape).border(1.dp, Color(0x557C3AED), shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (root != null) NavRow(listOf(root), 16f, false, boldOf, onPick)
        NavRow(parents, 13f, true, boldOf, onPick)
        if (children.isNotEmpty()) NavRow(children, 11f, true, { false }, onPick)
    }
}

/** One horizontal row of pipe-separated chips, centred in the box. */
@Composable
private fun NavRow(items: List<NavItem>, size: Float, topGap: Boolean, boldOf: (NavItem) -> Boolean, onPick: (NavItem) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = if (topGap) 6.dp else 0.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { i, item ->
            if (i > 0) Text("|", color = Color(0x55FFFFFF), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 2.dp))
            val bold = boldOf(item)
            Text(
                item.label, fontSize = size.sp, color = if (bold) Color(0xFFE9D8FD) else Color.White,
                fontWeight = if (bold) FontWeight.Bold else null,
                modifier = Modifier.testTag("navchip:${item.label}").clickable(onClick = { onPick(item) })
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}
