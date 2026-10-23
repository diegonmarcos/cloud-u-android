package com.diegonmarcos.cloudme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Test tags of the top bar; [entry] is one visible Settings entry. */
object MeTopBar {
    const val TITLE = "me:topbar:title"
    fun entry(sectionId: String) = "me:settings:$sectionId"
}

/**
 * The bar at the top of every page: the section's title on the left and, at the right, the Settings
 * entries (`ui.settings`: Profile and Configs) as two VISIBLE entries, each an icon with its name.
 * They replace the Settings dropdown (a gear that hid its entries behind a tap) and the hamburger: Cloud Me
 * has no drawer and no swipe-from-the-edge gesture of any kind, so a page scrolls freely under a thumb
 * that drifts sideways. An entry opens its section through [onOpen]; the one on screen is lit.
 */
@Composable
fun MeTopBar(title: String, currentSectionId: String?, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    Row(modifier.fillMaxWidth().height(56.dp).padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).testTag(MeTopBar.TITLE), color = colorResource(R.color.me_text), fontSize = 20.sp, maxLines = 1)
        for (s in Sections.settings()) {
            val on = s.id == currentSectionId
            @Suppress("DiscouragedApi")
            val icon = ctx.resources.getIdentifier(s.icon, "drawable", ctx.packageName)
            val ink = colorResource(if (on) R.color.me_primary else R.color.me_text_dim)
            Row(
                Modifier.padding(start = 6.dp).height(40.dp).clip(RoundedCornerShape(50))
                    .background(colorResource(if (on) R.color.me_surface_high else R.color.me_surface))
                    .clickable { onOpen(s.id) }
                    .semantics { role = Role.Button; selected = on }
                    .padding(horizontal = 12.dp)
                    .testTag(MeTopBar.entry(s.id)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != 0) Icon(painterResource(icon), null, Modifier.size(18.dp), tint = ink)
                Text(s.label, Modifier.padding(start = if (icon != 0) 6.dp else 0.dp), color = ink, fontSize = 13.sp, maxLines = 1)
            }
        }
    }
}
